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
import stroom.pathways.shared.FindPathwayMutationCriteria;
import stroom.pathways.shared.PathwayAsAt;
import stroom.pathways.shared.PathwayMutationResultPage;
import stroom.pathways.shared.PathwaysDoc;
import stroom.pathways.shared.otel.trace.NanoDuration;
import stroom.pathways.shared.otel.trace.Span;
import stroom.pathways.shared.otel.trace.SpanKind;
import stroom.pathways.shared.otel.trace.Trace;
import stroom.pathways.shared.pathway.PathNode;
import stroom.pathways.shared.pathway.PathwayMutation;
import stroom.planb.impl.PlanBPaths;
import stroom.planb.impl.dao.LmdbWriter;
import stroom.planb.impl.dao.ShardKeyRouter;
import stroom.planb.impl.dao.trace.IgnoredSpans;
import stroom.planb.impl.dao.trace.PathwaysDb;
import stroom.planb.impl.fs.MergeCompletionStrategy;
import stroom.planb.impl.fs.SharedFileStorePublisher;
import stroom.planb.shared.SharedFileStoreSettings;
import stroom.planb.shared.StateType;
import stroom.query.api.TimeRange;
import stroom.util.io.SimplePathCreator;
import stroom.util.shared.NullSafe;
import stroom.util.shared.PageRequest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reading a model as it stood at a time whose changes have left the live shard.
 *
 * <p>The narrowing moves changes the window has passed into the bucket for the week they were
 * applied in. A wind-back has to pass through every change between the time asked for and now, so
 * the reader has to find them wherever they are. These say it gives the same answer either way.
 */
class TestReadingArchivedChanges {


    private static final ByteBufferFactoryImpl BYTE_BUFFER_FACTORY = new ByteBufferFactoryImpl();
    private static final ByteBuffers BYTE_BUFFERS = new ByteBuffers(BYTE_BUFFER_FACTORY);
    private static final String OPERATION = "ProcessorTaskCreatorJob.run";
    private static final long DAY_MS = 86_400_000L;
    private static final int SHARD_COUNT = 4;

    @TempDir
    Path tempDir;

    private PathwaysDoc doc;
    private PathwaysShardStore shardStore;
    private ShardedPathwayReader reader;
    private MutationLog mutationLog;
    private BucketArchiver bucketArchiver;

    @BeforeEach
    void setUp() throws IOException {
        final Path shared = Files.createDirectories(tempDir.resolve("pathways_shared"));
        doc = PathwaysDoc.builder()
                .uuid(UUID.randomUUID().toString())
                .name("Test Pathways")
                .allowPathwayCreation(true)
                .allowPathwayMutation(true)
                .allowConstraintCreation(true)
                .allowConstraintMutation(true)
                .sharedFileStore(new SharedFileStoreSettings(SHARD_COUNT, shared.toString()))
                .build();
        shardStore = new PathwaysShardStore(
                new SharedFileStorePublisher(
                        () -> "test-node",
                        BYTE_BUFFERS,
                        BYTE_BUFFER_FACTORY,
                        new PlanBPaths(tempDir.resolve("local_state")),
                        Map.<StateType, MergeCompletionStrategy>of()),
                BYTE_BUFFERS,
                new SimplePathCreator(() -> tempDir.resolve("local"), () -> tempDir.resolve("temp")));
        final PathwaySerde serde = new PathwaySerde(BYTE_BUFFER_FACTORY);
        mutationLog = new MutationLog(BYTE_BUFFERS, serde);
        reader = new ShardedPathwayReader(shardStore, serde, mutationLog);
        bucketArchiver = new BucketArchiver(shardStore, BYTE_BUFFERS, serde);
    }

    @Test
    void aTimeIsAnsweredTheSameOnceItsChangesHaveMovedToABucket() throws IOException {
        apply(trace("a", 10));
        apply(trace("b", 20));
        apply(trace("c", 30));

        final long asAtMs = secondOfTheAppliedTimes();
        final Object wasWoundBackTo = durationOf(asAt(asAtMs));
        assertThat(wasWoundBackTo)
                .as("the fixture only says something if the time asked for is behind the model")
                .isNotEqualTo(durationOf(asAt(null)));

        final PathwayAsAt before = asAt(asAtMs);

        archiveChanges();

        assertThat(liveChanges())
                .as("the premise: the live shard no longer holds any of them")
                .isEmpty();
        final PathwayAsAt after = asAt(asAtMs);
        assertThat(durationOf(after))
                .as("the same answer, read out of the bucket instead")
                .isEqualTo(wasWoundBackTo);

        // Everything a drawing takes its sizes and colours from, not just the model's shape.
        // NodeChange has no equals, so compare what it carries.
        assertThat(changeCounts(after))
                .as("how busy each node has been with change, which is its size")
                .isEqualTo(changeCounts(before));
        assertThat(after.getUsage())
                .as("how much each node had been used, which is the link widths and the colours")
                .isEqualTo(before.getUsage());
        assertThat(after.getChangeCeiling()).isEqualTo(before.getChangeCeiling());
        assertThat(after.getUsageCeiling()).isEqualTo(before.getUsageCeiling());
        assertThat(after.getAsAt()).isEqualTo(before.getAsAt());
    }

    @Test
    void aMomentReadFromBothPlacesAtOnceIsStillRight() throws IOException {
        // The real shape once a window has passed: the older changes are in a bucket, the newer ones
        // are still in the shard, and the moment asked for sits among the archived ones.
        apply(withChildren("a", 10, 5, 7));
        apply(withChildren("b", 20, 9, 4));
        apply(withChildren("c", 30, 6, 12));
        final long asAtMs = secondOfTheAppliedTimes();
        final PathwayAsAt before = asAt(asAtMs);
        assertThat(before.getPathway().getRoot().getChildren())
                .as("the fixture only says something if there is more than one node to size")
                .hasSizeGreaterThan(1);

        archiveChanges();
        apply(withChildren("d", 40, 11, 3));
        apply(withChildren("e", 50, 2, 15));

        final PathwayAsAt after = asAt(asAtMs);
        assertThat(nodePaths(after))
                .as("the same model, assembled from the bucket and the shard together")
                .isEqualTo(nodePaths(before));
        assertThat(changeCounts(after))
                .as("how busy each node has been with change, which is its size")
                .isEqualTo(changeCounts(before));
        assertThat(after.getUsage())
                .as("how much each node had been used, which is the link widths and the colours")
                .isEqualTo(before.getUsage());
    }

    @Test
    void aChangeMadeAfterArchivingIsNumberedPastTheArchivedOnes() throws IOException {
        // Numbering used to come from the last change row still in the shard. Once the narrowing
        // takes them all away that is nothing, so the next change started at one again and collided
        // with the archived rows it would later be read beside.
        apply(trace("a", 10));
        apply(trace("b", 20));
        apply(trace("c", 30));
        final long highestArchived = liveChanges().stream()
                .mapToLong(PathwayMutation::getSequence)
                .max()
                .orElseThrow();

        archiveChanges();
        assertThat(liveChanges()).isEmpty();
        apply(trace("d", 40));

        assertThat(liveChanges().stream().mapToLong(PathwayMutation::getSequence).min().orElseThrow())
                .as("the history carries on from where it got to, not from what is left here")
                .isGreaterThan(highestArchived);
    }

    @Test
    void theModelAsItStandsIsAnsweredWithoutOpeningABucket() throws IOException {
        apply(trace("a", 10));
        final Object asItStands = durationOf(asAt(null));

        archiveChanges();

        assertThat(durationOf(asAt(null)))
                .as("no wind-back, so nothing has to be found")
                .isEqualTo(asItStands);
    }

    @Test
    void theChangesListReadsTheBucketsToo() throws IOException {
        apply(trace("a", 10));
        apply(trace("b", 20));
        apply(trace("c", 30));
        final PathwayMutationResultPage before = changesListed();
        assertThat(before.getValues()).isNotEmpty();

        archiveChanges();

        final PathwayMutationResultPage after = changesListed();
        assertThat(after.getValues().stream().map(PathwayMutation::getSequence))
                .as("every change is still listed once, from wherever it now lives")
                .containsExactlyElementsOf(before.getValues().stream()
                        .map(PathwayMutation::getSequence)
                        .toList());
        assertThat(after.getPageResponse().getTotal())
                .as("and the total counts the joined list, not the live shard")
                .isEqualTo(before.getPageResponse().getTotal());
    }

    @Test
    void aChangeInBothPlacesIsListedOnce() throws IOException {
        // What a narrowing interrupted between storing and dropping leaves behind. The rows go to
        // the bucket and stay in the shard.
        apply(trace("a", 10));
        apply(trace("b", 20));
        final long total = changesListed().getPageResponse().getTotal();

        archiveToBucketWithoutDropping();

        assertThat(changesListed().getPageResponse().getTotal()).isEqualTo(total);
    }

    @Test
    void onlyTheWeeksTheRangeTouchesAreOpened() {
        assertThat(ShardedPathwayReader.covers("week-2026-11-09",
                Instant.parse("2026-11-10T00:00:00Z").toEpochMilli(),
                Instant.parse("2026-11-11T00:00:00Z").toEpochMilli()))
                .as("a range inside the week")
                .isTrue();
        assertThat(ShardedPathwayReader.covers("week-2026-11-02",
                Instant.parse("2026-11-10T00:00:00Z").toEpochMilli(),
                Instant.parse("2026-11-11T00:00:00Z").toEpochMilli()))
                .as("the week before it is not touched")
                .isFalse();
        assertThat(ShardedPathwayReader.covers("week-2026-11-02",
                Instant.parse("2026-11-01T00:00:00Z").toEpochMilli(),
                Instant.parse("2026-11-12T00:00:00Z").toEpochMilli()))
                .as("a span crossing a Monday takes both weeks")
                .isTrue();
        assertThat(ShardedPathwayReader.covers("not-a-week",
                0L, 1L))
                .as("a label nothing can read is opened rather than skipped")
                .isTrue();
    }

    @Test
    void theDayRecordsTheWindowHasPassedGoToTheBucketToo() throws IOException {
        // The narrowing hands over day records as the bytes it read off the live shard, not as
        // records, and the bucket has to read them back to fold them in. Driven through the real
        // narrowing rather than by handing the archiver a record, because that reading is the step
        // nothing else exercises.
        apply(trace("a", 10));

        final int tomorrow = (int) ((System.currentTimeMillis() + DAY_MS) / DAY_MS);
        final PathwayNarrower narrower = new PathwayNarrower(
                new PathwaySerde(BYTE_BUFFER_FACTORY), mutationLog, BYTE_BUFFERS);
        shardStore.withShard(doc, shard(), localDir -> narrower.narrow(localDir, tomorrow,
                (severity, message) -> {
                }, bucketArchiver.forShard(doc, shard())));

        final DayRecord record = onlyDayRecordIn(PathwayNarrower.weekOf(tomorrow - 1));
        assertThat(record.nodes().keySet())
                .as("what that day was given, read back out of the bucket")
                .contains(List.of(OPERATION));
    }

    // -----------------------------------------------------------------------
    // Fixture
    // -----------------------------------------------------------------------
    private DayRecord onlyDayRecordIn(final String week) throws IOException {
        final List<DayRecord> found = new ArrayList<>();
        shardStore.readBucket(doc, shard(), week, db -> {
            db.getObservedValues().iterate((key, value) ->
                    found.add(new PathwaySerde(BYTE_BUFFER_FACTORY).readObservedValues(value)));
            return null;
        });
        assertThat(found).hasSize(1);
        return found.get(0);
    }


    private PathwayMutationResultPage changesListed() {
        return reader.findMutations(doc, new FindPathwayMutationCriteria(
                new PageRequest(0, 1000), List.of(), null, OPERATION));
    }

    // The storing half of the narrowing without the dropping half.
    private void archiveToBucketWithoutDropping() throws IOException {
        final int tomorrow = (int) ((System.currentTimeMillis() + DAY_MS) / DAY_MS);
        final PathwayNarrower.ExpiredArchiver archiver = bucketArchiver.forShard(doc, shard());
        shardStore.withShard(doc, shard(), localDir -> {
            try (final PathwaysDb db = PathwaysDb.create(localDir, BYTE_BUFFERS, false);
                    final LmdbWriter writer = db.createWriter()) {
                mutationLog.expired(writer, db.getMutations(), keyBytes(), tomorrow,
                                PathwayNarrower::weekOf)
                        .toStore()
                        .forEach((week, rows) -> archiver.archive(week, List.of(), rows));
                writer.commit();
            }
            return true;
        });
    }

    private PathwayAsAt asAt(final Long asAtMs) {
        return reader.fetchPathwayAsAt(doc, OPERATION, range(asAtMs), null).orElseThrow();
    }

    private static TimeRange range(final Long asAtMs) {
        return asAtMs == null
                ? null
                : new TimeRange("Test", null, Instant.ofEpochMilli(asAtMs).toString());
    }

    private static List<String> nodePaths(final PathwayAsAt asAt) {
        final List<String> paths = new ArrayList<>();
        collect(asAt.getPathway().getRoot(), paths);
        return paths;
    }

    private static void collect(final PathNode node, final List<String> paths) {
        if (node == null) {
            return;
        }
        paths.add(node.getName() + " " + node.getConstraints().keySet());
        NullSafe.list(node.getChildren()).forEach(child -> collect(child, paths));
    }

    private static Map<String, String> changeCounts(final PathwayAsAt asAt) {
        final Map<String, String> counts = new LinkedHashMap<>();
        asAt.getChanges().forEach((uuid, change) ->
                counts.put(uuid, change.getCount() + " at " + change.getLastUpdated()));
        return counts;
    }

    private static Object durationOf(final PathwayAsAt asAt) {
        return asAt.getPathway().getRoot().getConstraints().get("duration").getValue();
    }

    // The second distinct moment the learner took changes in. Picked rather than the first because
    // the first brings the pathway into being, and rather than the last because the last winds
    // nothing off.
    private long secondOfTheAppliedTimes() {
        final List<Long> times = liveChanges().stream()
                .map(change -> change.getTime().toEpochMillis())
                .distinct()
                .sorted()
                .toList();
        assertThat(times)
                .as("the fixture only says something if the changes landed at different times")
                .hasSizeGreaterThan(2);
        return times.get(1);
    }

    private List<PathwayMutation> liveChanges() {
        final List<PathwayMutation> found = new ArrayList<>();
        try {
            shardStore.readShard(doc, shard(), db -> {
                found.addAll(mutationLog.read(db.getMutations(), keyBytes()));
                return null;
            });
        } catch (final IOException e) {
            throw new RuntimeException(e);
        }
        return found;
    }

    // Hands every change row over to its week's bucket and drops it, which is what the narrowing does
    // to the rows the window has passed. The model itself is left alone: what the narrowing does to
    // that is a separate question, answered by its own tests.
    private void archiveChanges() throws IOException {
        final int tomorrow = (int) ((System.currentTimeMillis() + DAY_MS) / DAY_MS);
        final PathwayNarrower.ExpiredArchiver archiver = bucketArchiver.forShard(doc, shard());
        shardStore.withShard(doc, shard(), localDir -> {
            try (final PathwaysDb db = PathwaysDb.create(localDir, BYTE_BUFFERS, false);
                    final LmdbWriter writer = db.createWriter()) {
                final MutationLog.Expired expired = mutationLog.expired(
                        writer, db.getMutations(), keyBytes(), tomorrow, PathwayNarrower::weekOf);
                expired.toStore().forEach((week, rows) -> archiver.archive(week, List.of(), rows));
                expired.toDrop().values()
                        .forEach(rows -> mutationLog.drop(writer, db.getMutations(), rows));
                writer.commit();
            }
            return true;
        });
    }

    // Applies one trace through the shard store, as a hold does: copied down, applied, pushed back.
    private void apply(final Trace trace) throws IOException {
        shardStore.withShard(doc, shard(), localDir -> {
            try (final PathwaysDb db = PathwaysDb.create(localDir, BYTE_BUFFERS, false);
                    final LmdbWriter writer = db.createWriter()) {
                final PathwaySerde serde = new PathwaySerde(BYTE_BUFFER_FACTORY);
                final TraceProcessor processor = new TraceProcessor(
                        new MutationLog(BYTE_BUFFERS, serde),
                        BYTE_BUFFERS,
                        serde,
                        new IgnoredAttributes(doc.getIgnoredAttributes()),
                        new IgnoredSpans(List.of()));
                processor.processTrace(writer, db, idBytes(trace), id -> Optional.of(trace), doc,
                        (severity, message) -> {
                        }, null, TraceLoader.WITHIN_THE_WINDOW);
                processor.writeObservedValues(writer, db);
                writer.commit();
            }
            return true;
        });
    }

    private int shard() {
        return ShardKeyRouter.computeShardIndex(OPERATION, SHARD_COUNT);
    }

    private static byte[] keyBytes() {
        return OPERATION.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] idBytes(final Trace trace) {
        final byte[] bytes = new byte[16];
        final byte[] id = trace.getTraceId().getBytes(StandardCharsets.UTF_8);
        System.arraycopy(id, 0, bytes, 0, Math.min(id.length, 16));
        return bytes;
    }

    private static Span span(final String name,
                             final String spanId,
                             final String parentSpanId,
                             final long startNanos,
                             final int durationMillis) {
        return Span.builder()
                .name(name)
                .spanId(spanId)
                .parentSpanId(parentSpanId)
                .kind(SpanKind.SPAN_KIND_INTERNAL)
                .startTimeUnixNano(Long.toString(startNanos))
                .endTimeUnixNano(Long.toString(startNanos + NanoDuration.ofMillis(durationMillis).getNanos()))
                .build();
    }

    // A root running two children, so there is more than one node to size and more than one link to
    // draw. Each one's duration widens the model, which is the change a wind-back takes back off.
    private static Trace withChildren(final String id,
                                      final int rootMillis,
                                      final int firstMillis,
                                      final int secondMillis) {
        final long start = System.currentTimeMillis() * 1_000_000L;
        final String root = "r" + id;
        final Map<String, List<Span>> byParent = new HashMap<>();
        byParent.put("", List.of(span(OPERATION, root, "", start, rootMillis)));
        byParent.put(root, List.of(
                span("Fetch", "f" + id, root, start + NanoDuration.ofMillis(1L).getNanos(), firstMillis),
                span("Commit", "c" + id, root, start + NanoDuration.ofMillis(2L).getNanos(),
                        secondMillis)));
        return new Trace(id + "0".repeat(31), byParent);
    }

    // One root span running now, lasting the given number of milliseconds. The duration constraint
    // widens with each longer one, which is the change a wind-back takes back off.
    private static Trace trace(final String id, final int durationMillis) {
        final long start = System.currentTimeMillis() * 1_000_000L;
        final Span root = Span.builder()
                .name(OPERATION)
                .spanId("r" + id)
                .parentSpanId("")
                .kind(SpanKind.SPAN_KIND_INTERNAL)
                .startTimeUnixNano(Long.toString(start))
                .endTimeUnixNano(Long.toString(start + NanoDuration.ofMillis(durationMillis).getNanos()))
                .build();
        final Map<String, List<Span>> byParent = new HashMap<>();
        byParent.put("", List.of(root));
        return new Trace(id + "0".repeat(31), byParent);
    }
}
