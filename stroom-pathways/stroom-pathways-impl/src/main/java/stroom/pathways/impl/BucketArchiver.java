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
import stroom.planb.impl.dao.trace.PathwaysDb;
import stroom.planb.impl.dao.trace.PathwaysDb.SimpleDb;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
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
    private final PathwaySerde pathwaySerde;

    @Inject
    public BucketArchiver(final PathwaysShardStore shardStore,
                          final ByteBuffers byteBuffers,
                          final PathwaySerde pathwaySerde) {
        this.shardStore = shardStore;
        this.byteBuffers = byteBuffers;
        this.pathwaySerde = pathwaySerde;
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
                        // Day records fold rather than overwrite, the same way the other route in
                        // does. A day can already be in a bucket - an old trace put it there - and
                        // writing this one over it would throw that away.
                        //
                        // Read through a pooled buffer because the serde reads off-heap addresses
                        // and refuses anything else, and these arrive as bytes read off the shard.
                        dayRecords.forEach(row -> byteBuffers.useBytes(row.value(),
                                (Consumer<ByteBuffer>) value -> fold(writer, db, row.key(),
                                        pathwaySerde.readObservedValues(value))));
                        put(writer, db.getMutations(), logRows);
                        writer.commit();
                    }
                    return true;
                });
            } catch (final IOException e) {
                throw new UncheckedIOException(e);
            }
        };
    }

    /**
     * Folds the days of one week into that week's bucket.
     *
     * <p>The route a trace takes when its work ran before the window: it changes no model, writes no
     * change row, and never touches the live shard. What is already in the bucket for a day is read
     * first and the new account folded into it, the same way a hold folds a trace into the day it is
     * building - so giving the same traces again leaves the record as it was apart from the counts.
     *
     * <p>A week at a time rather than a day or a trace, because opening a bucket is copying it down
     * and publishing it back, and a load of old history is thousands of traces over a handful of
     * weeks.
     */
    void archive(final PathwaysDoc doc,
                 final int shardIndex,
                 final String dateLabel,
                 final Map<TraceProcessor.DayOfPathway, DayRecord> days) throws IOException {
        shardStore.withBucket(doc, shardIndex, dateLabel, db -> {
            try (final LmdbWriter writer = db.createWriter()) {
                days.forEach((key, seen) -> fold(writer, db, key.bytes(), seen));
                writer.commit();
            }
            return true;
        });
    }

    // One day into a bucket, under the key it already had. What is there is read first and this is
    // folded into it, so whichever route put a day here and whichever order they arrived in, the
    // bucket holds the union - which is what everything reading one assumes.
    private void fold(final LmdbWriter writer,
                      final PathwaysDb db,
                      final byte[] key,
                      final DayRecord seen) {
        byteBuffers.useBytes(key, (Consumer<ByteBuffer>) keyByteBuffer -> {
            final DayRecord held = db.getObservedValues().get(writer.getWriteTxn(),
                    keyByteBuffer.duplicate(),
                    value -> value == null
                            ? DayRecord.empty()
                            : pathwaySerde.readObservedValues(value));
            pathwaySerde.writeObservedValues(DayRecord.merge(held, seen),
                    valueByteBuffer -> db.getObservedValues()
                            .insert(writer, keyByteBuffer, valueByteBuffer));
        });
    }

    // Written under the keys they already had, so a bucket holds them exactly as the live shard did
    // and whatever reads one reads them the same way.
    private void put(final LmdbWriter writer, final SimpleDb db, final List<MutationLog.Row> rows) {
        rows.forEach(row -> byteBuffers.useBytes(row.key(), (Consumer<ByteBuffer>) keyByteBuffer ->
                byteBuffers.useBytes(row.value(), (Consumer<ByteBuffer>) valueByteBuffer ->
                        db.insert(writer, keyByteBuffer, valueByteBuffer))));
    }
}
