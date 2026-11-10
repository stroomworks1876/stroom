/*
 * Copyright 2026 Crown Copyright
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package stroom.pathways.impl;

import stroom.bytebuffer.impl6.ByteBuffers;
import stroom.pathways.shared.PathwaysDoc;
import stroom.planb.impl.dao.LmdbWriter;
import stroom.planb.impl.dao.trace.PathwaysDb.SimpleDb;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.function.Consumer;

/**
 * Where a record goes when the narrowing window has passed it: the bucket for the week it falls in,
 * on the shared store.
 *
 * <p>Apart from the narrowing, which knows only that a record has fallen out and must be stored
 * somewhere, and apart from the processor, so the same route a running node takes is the route a
 * test takes.
 */
@Singleton
public class BucketArchiver {

    private final PathwaysShardStore shardStore;
    private final ByteBuffers byteBuffers;

    @Inject
    public BucketArchiver(final PathwaysShardStore shardStore, final ByteBuffers byteBuffers) {
        this.shardStore = shardStore;
        this.byteBuffers = byteBuffers;
    }

    /**
     * An archiver for one shard, to be used while this node holds that shard's lock - so nothing else
     * is moving the same days at the same time.
     *
     * <p>Anything thrown stops the narrowing deleting the records it handed over, and the shard is
     * left as it was: the next pass finds them still there and tries again.
     */
    PathwayNarrower.ExpiredArchiver forShard(final PathwaysDoc doc, final int shardIndex) {
        return (dateLabel, dayRecords, logRows) -> {
            try {
                shardStore.withBucket(doc, shardIndex, dateLabel, db -> {
                    try (final LmdbWriter writer = db.createWriter()) {
                        put(writer, db.getObservedValues(), dayRecords);
                        put(writer, db.getMutations(), logRows);
                        writer.commit();
                    }
                });
            } catch (final IOException e) {
                throw new UncheckedIOException(e);
            }
        };
    }

    // Written under the keys they already had, so a bucket holds them exactly as the live shard did
    // and whatever reads one reads them the same way.
    private void put(final LmdbWriter writer, final SimpleDb db, final List<MutationLog.Row> rows) {
        rows.forEach(row -> byteBuffers.useBytes(row.key(), (Consumer<ByteBuffer>) keyByteBuffer ->
                byteBuffers.useBytes(row.value(), (Consumer<ByteBuffer>) valueByteBuffer ->
                        db.insert(writer, keyByteBuffer, valueByteBuffer))));
    }
}
