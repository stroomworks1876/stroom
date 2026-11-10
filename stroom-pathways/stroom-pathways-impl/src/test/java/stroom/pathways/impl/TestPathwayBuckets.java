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

import stroom.bytebuffer.impl6.ByteBufferFactoryImpl;
import stroom.bytebuffer.impl6.ByteBuffers;
import stroom.pathways.shared.PathwaysDoc;
import stroom.planb.impl.PlanBConstants;
import stroom.planb.impl.PlanBPaths;
import stroom.planb.impl.dao.LmdbWriter;
import stroom.planb.impl.dao.trace.PathwaysDb;
import stroom.planb.impl.fs.MergeCompletionStrategy;
import stroom.planb.impl.fs.SharedFileStorePublisher;
import stroom.planb.shared.SharedFileStoreSettings;
import stroom.planb.shared.StateType;
import stroom.util.io.SimplePathCreator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A week's bucket on the shared file store, written and read back.
 *
 * <p>What the narrowing hands over is tested elsewhere. This is the part that puts it on disk, which
 * is where the copy down, the compressing, the push back up and the version file all happen.
 *
 * <p>The records are plain bytes rather than real day records. Nothing here cares what is in a
 * record, only that what went in comes out.
 */
class TestPathwayBuckets {

    private static final ByteBufferFactoryImpl BYTE_BUFFER_FACTORY = new ByteBufferFactoryImpl();
    private static final ByteBuffers BYTE_BUFFERS = new ByteBuffers(BYTE_BUFFER_FACTORY);
    private static final String WEEK = "week-2026-11-09";

    @TempDir
    Path tempDir;

    @Test
    void whatGoesIntoABucketComesBack() throws IOException {
        final PathwaysShardStore store = store();
        final PathwaysDoc doc = doc();

        store.withBucket(doc, 0, WEEK, db -> put(db, Map.of("one", "first", "two", "second")));

        assertThat(read(store, doc))
                .containsOnlyKeys("one", "two")
                .containsEntry("one", "first")
                .containsEntry("two", "second");
    }

    @Test
    void aSecondBatchJoinsTheFirstRatherThanReplacingIt() throws IOException {
        // Late records keep arriving for an old week, so a bucket rewritten from one batch would lose
        // every batch before it. This is why the bucket is copied down before it is written.
        final PathwaysShardStore store = store();
        final PathwaysDoc doc = doc();

        store.withBucket(doc, 0, WEEK, db -> put(db, Map.of("one", "first")));
        store.withBucket(doc, 0, WEEK, db -> put(db, Map.of("two", "second")));

        assertThat(read(store, doc))
                .containsOnlyKeys("one", "two")
                .containsEntry("one", "first")
                .containsEntry("two", "second");
    }

    @Test
    void eachWeekIsItsOwnBucket() throws IOException {
        final PathwaysShardStore store = store();
        final PathwaysDoc doc = doc();

        store.withBucket(doc, 0, WEEK, db -> put(db, Map.of("one", "first")));
        store.withBucket(doc, 0, "week-2026-11-02", db -> put(db, Map.of("two", "second")));

        assertThat(read(store, doc)).containsOnlyKeys("one");
        assertThat(store.readBucket(doc, 0, "week-2026-11-02", TestPathwayBuckets::contents).orElseThrow())
                .containsOnlyKeys("two");
    }

    @Test
    void aBucketIsStoredCompressedAndVersioned() throws IOException {
        final PathwaysShardStore store = store();
        final PathwaysDoc doc = doc();

        store.withBucket(doc, 0, WEEK, db -> put(db, Map.of("one", "first")));

        final Path bucket = tempDir.resolve("shared")
                .resolve(PlanBConstants.ARCHIVE_DIR_NAME)
                .resolve(doc.getUuid())
                .resolve("0000")
                .resolve(WEEK);
        assertThat(bucket.resolve(PlanBConstants.DATA_ZST_FILE_NAME)).exists();
        assertThat(bucket.resolve(PlanBConstants.VERSION_FILE_NAME)).exists();
        assertThat(bucket.resolve(PlanBConstants.DATA_FILE_NAME))
                .as("only the compressed form is published")
                .doesNotExist();
        assertThat(bucket.resolve(PlanBConstants.LOCK_FILE_NAME))
                .as("a bucket is a data file and a version, nothing else")
                .doesNotExist();
    }

    @Test
    void aWeekThatWasNeverWrittenReadsAsNothing() throws IOException {
        assertThat(store().readBucket(doc(), 0, "week-2000-01-03", TestPathwayBuckets::contents))
                .isEmpty();
    }

    private Map<String, String> read(final PathwaysShardStore store, final PathwaysDoc doc)
            throws IOException {
        return store.readBucket(doc, 0, WEEK, TestPathwayBuckets::contents).orElseThrow();
    }

    private static Map<String, String> contents(final PathwaysDb db) {
        final Map<String, String> found = new LinkedHashMap<>();
        db.getObservedValues().iterate((key, value) -> found.put(text(key), text(value)));
        return found;
    }

    private static void put(final PathwaysDb db, final Map<String, String> records) {
        try (final LmdbWriter writer = db.createWriter()) {
            records.forEach((key, value) ->
                    db.getObservedValues().insert(writer, bytes(key), bytes(value)));
            writer.commit();
        }
    }

    private static ByteBuffer bytes(final String text) {
        final byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        final ByteBuffer buffer = ByteBuffer.allocateDirect(bytes.length);
        buffer.put(bytes).flip();
        return buffer;
    }

    private static String text(final ByteBuffer buffer) {
        final ByteBuffer copy = buffer.duplicate();
        final byte[] bytes = new byte[copy.remaining()];
        copy.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private PathwaysShardStore store() throws IOException {
        final Path home = Files.createDirectories(tempDir.resolve("home"));
        final Path temp = Files.createDirectories(tempDir.resolve("temp"));
        return new PathwaysShardStore(
                new SharedFileStorePublisher(
                        () -> "test-node",
                        BYTE_BUFFERS,
                        BYTE_BUFFER_FACTORY,
                        new PlanBPaths(tempDir.resolve("local_state")),
                        Map.<StateType, MergeCompletionStrategy>of()),
                BYTE_BUFFERS,
                new SimplePathCreator(() -> home, () -> temp));
    }

    private PathwaysDoc doc() throws IOException {
        final Path shared = Files.createDirectories(tempDir.resolve("shared"));
        return PathwaysDoc.builder()
                .uuid(UUID.randomUUID().toString())
                .name("Test Pathways")
                .sharedFileStore(new SharedFileStoreSettings(1, shared.toString()))
                .build();
    }
}
