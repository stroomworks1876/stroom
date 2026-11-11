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
import stroom.pathways.shared.pathway.NamePathKey;
import stroom.pathways.shared.pathway.Pathway;
import stroom.planb.impl.PlanBConstants;
import stroom.planb.impl.PlanBPaths;
import stroom.planb.impl.dao.trace.NanoTimeUtil;
import stroom.planb.impl.dao.trace.PathwaysDb;
import stroom.planb.impl.dao.trace.QueueItemWriter;
import stroom.planb.impl.dao.trace.TraceDb;
import stroom.planb.impl.data.value.SpanKV;
import stroom.planb.impl.fs.MergeCompletionStrategy;
import stroom.planb.impl.fs.MergeContext;
import stroom.planb.impl.fs.SharedFileStorePublisher;
import stroom.planb.impl.serde.trace.HexStringUtil;
import stroom.planb.impl.serde.trace.SpanKey;
import stroom.planb.impl.serde.trace.SpanValue;
import stroom.planb.shared.PlanBDoc;
import stroom.planb.shared.SharedFileStoreSettings;
import stroom.planb.shared.StateType;
import stroom.planb.shared.TraceSettings;
import stroom.util.io.SimplePathCreator;
import stroom.util.shared.time.SimpleDuration;
import stroom.util.shared.time.TimeUnit;

import com.codahale.metrics.MetricRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Loading traces whose work ran long before the window, which is what the whole rebuild is for.
 *
 * <p>Such a trace teaches the model nothing - it would be narrowed straight back out - so it must
 * change no model, write no change row, and leave nothing in the live shard. All it leaves is an
 * account of the day its work ran, in the bucket for that week.
 *
 * <p>End to end through the real processor, with a real shard store and real queue items, because
 * the parts of this have their own tests and what was untested is the way they join up. That
 * includes reading them back: a write nothing can read is not a feature.
 */
class TestLoadingOldTraces {

    private static final ByteBufferFactoryImpl BYTE_BUFFER_FACTORY = new ByteBufferFactoryImpl();
    private static final ByteBuffers BYTE_BUFFERS = new ByteBuffers(BYTE_BUFFER_FACTORY);
    private static final String OPERATION = "GET /orders";
    private static final String ROOT_SPAN = "0f3a000000000001";

    @TempDir
    Path tempDir;

    private PathwaysDoc doc;
    private PlanBDoc tracesDoc;
    private PathwaysShardStore shardStore;
    private PathwaysProcessor processor;
    private Path shared;
    private Path bucketDir;
    private int nextTrace;

    @BeforeEach
    void setUp() throws IOException {
        shared = Files.createDirectories(tempDir.resolve("pathways_shared"));
        bucketDir = Files.createDirectories(tempDir.resolve("traces_bucket"));
        doc = PathwaysDoc.builder()
                .uuid(UUID.randomUUID().toString())
                .name("Test Pathways")
                .allowPathwayCreation(true)
                .allowPathwayMutation(true)
                .allowConstraintCreation(true)
                .allowConstraintMutation(true)
                .narrowingWindow(new SimpleDuration(7L, TimeUnit.DAYS))
                .sharedFileStore(new SharedFileStoreSettings(1, shared.toString()))
                .build();
        tracesDoc = PlanBDoc.builder()
                .uuid(UUID.randomUUID().toString())
                .name("test-traces")
                .stateType(StateType.TRACE)
                .settings(new TraceSettings.Builder().build())
                .build();

        final SharedFileStorePublisher publisher = new SharedFileStorePublisher(
                () -> "test-node", BYTE_BUFFERS, BYTE_BUFFER_FACTORY,
                new PlanBPaths(tempDir.resolve("local_state")),
                Map.<StateType, MergeCompletionStrategy>of());
        shardStore = new PathwaysShardStore(publisher, BYTE_BUFFERS,
                new SimplePathCreator(() -> tempDir.resolve("local"), () -> tempDir.resolve("temp")));
        final PathwaySerde serde = new PathwaySerde(BYTE_BUFFER_FACTORY);
        processor = new PathwaysProcessor(
                new MutationLog(BYTE_BUFFERS, serde),
                shardStore,
                null,
                serde,
                new PathwayNarrower(serde, new MutationLog(BYTE_BUFFERS, serde), BYTE_BUFFERS),
                new BucketArchiver(shardStore, BYTE_BUFFERS, serde),
                BYTE_BUFFERS,
                BYTE_BUFFER_FACTORY,
                MetricRegistry::new);
    }

    @Test
    void oldTracesLeaveNothingInTheLiveShard() throws IOException {
        load(daysAgo(400), 3);

        assertThat(liveShard(db -> count(db, db.getPathways())))
                .as("no model: it would have been narrowed straight back out")
                .isZero();
        assertThat(liveShard(db -> count(db, db.getMutations())))
                .as("no change rows either, because nothing changed")
                .isZero();
        assertThat(liveShard(db -> count(db, db.getObservedValues())))
                .as("and the day belongs in a bucket, not here")
                .isZero();
        assertThat(liveShard(db -> count(db, db.getProcessingStatus())))
                .as("no marker per trace - that is what makes loading a year affordable")
                .isZero();
    }

    @Test
    void whatThoseTracesGaveIsInTheWeeksBucket() throws IOException {
        final Instant ran = daysAgo(400);
        load(ran, 3);

        final String week = weekOf(ran);
        assertThat(Files.isDirectory(shared.resolve(PlanBConstants.ARCHIVE_DIR_NAME)
                .resolve(doc.getUuid()).resolve("0000").resolve(week)))
                .as("the bucket for the week the work ran in")
                .isTrue();
        final DayRecord record = onlyRecordIn(week);
        assertThat(record.nodes().keySet()).containsExactly(List.of(OPERATION));
        assertThat(record.nodes().get(List.of(OPERATION)).timesUsed())
                .as("three traces ran that one node")
                .isEqualTo(3L);
    }

    @Test
    void loadingTheSameMonthTwiceLeavesItAsItWas() throws IOException {
        // There is no marker saying these were applied, so nothing stops them being offered again.
        // Everything but the counts has to come back the same, or a reload would change history.
        final Instant ran = daysAgo(400);
        load(ran, 2);
        final String week = weekOf(ran);
        final DayRecord once = onlyRecordIn(week);

        load(ran, 2);
        final DayRecord twice = onlyRecordIn(week);

        assertThat(twice.nodes().keySet()).isEqualTo(once.nodes().keySet());
        assertThat(twice.nodes().get(List.of(OPERATION)).constraints())
                .as("the same values, justified by the same traces")
                .isEqualTo(once.nodes().get(List.of(OPERATION)).constraints());
        assertThat(twice.paths().getPaths())
                .as("and one path, not two")
                .hasSize(1);
    }

    @Test
    void aTraceInsideTheWindowStillGoesToTheModel() throws IOException {
        load(Instant.now(), 1);

        assertThat(liveShard(db -> count(db, db.getPathways())))
                .as("the fixture only says something if the guard is a boundary rather than a wall")
                .isEqualTo(1);
    }

    @Test
    void aWindowSpanningTheBoundaryReadsBothStores() throws IOException {
        // The one that matters. Old days are in a bucket and recent ones are still in the live
        // shard, so a fold that reads either alone answers with part of the window missing - and
        // silently, because what comes back is a perfectly good model of the wrong thing.
        final Instant old = daysAgo(400);
        load(old, 3);
        load(Instant.now(), 2);

        final DayRecord folded = reader().daysOfWindow(doc, 0, keyBytes(), dayOf(old), today());

        assertThat(folded.nodes().get(List.of(OPERATION)).timesUsed())
                .as("all five traces, three out of the bucket and two out of the live shard")
                .isEqualTo(5L);
    }

    @Test
    void aWindowOfOnlyOldDaysStillBuildsAModel() throws IOException {
        final Instant old = daysAgo(400);
        load(old, 3);

        final DayRecord folded = reader().daysOfWindow(doc, 0, keyBytes(), dayOf(old), dayOf(old));
        final Pathway model = PathwayFold.of(folded).model(new NamePathKey(OPERATION), OPERATION);

        assertThat(model.getRoot()).as("a model out of buckets alone").isNotNull();
        assertThat(model.getRoot().getNodePath()).isEqualTo(List.of(OPERATION));
        assertThat(model.getRoot().getTimesUsed()).isEqualTo(3L);
    }

    @Test
    void aWindowThatMissesEveryDayFoldsToNothing() throws IOException {
        load(daysAgo(400), 3);

        final DayRecord folded = reader().daysOfWindow(doc, 0, keyBytes(), today() - 5, today());

        assertThat(folded.nodes())
                .as("the days are real but none of them is in this window")
                .isEmpty();
    }

    // -----------------------------------------------------------------------
    // Fixture
    // -----------------------------------------------------------------------

    private static String weekOf(final Instant ran) {
        return PathwayNarrower.weekOf((int) (ran.toEpochMilli() / TraceProcessor.MILLIS_PER_DAY));
    }

    private ShardedPathwayReader reader() {
        final PathwaySerde serde = new PathwaySerde(BYTE_BUFFER_FACTORY);
        return new ShardedPathwayReader(shardStore, serde, new MutationLog(BYTE_BUFFERS, serde));
    }

    private static byte[] keyBytes() {
        return OPERATION.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static int dayOf(final Instant when) {
        return (int) (when.toEpochMilli() / TraceProcessor.MILLIS_PER_DAY);
    }

    private static int today() {
        return dayOf(Instant.now());
    }

    private static Instant daysAgo(final int days) {
        return Instant.now().minus(days, ChronoUnit.DAYS);
    }

    // Traces whose work ran at the given moment, written as a real queue item and handed to the real
    // processor the way the merge hands one over.
    private void load(final Instant ran, final int traceCount) throws IOException {
        final List<byte[]> traceIds = new ArrayList<>();
        try (final TraceDb bucket = TraceDb.create(
                bucketDir, BYTE_BUFFERS, BYTE_BUFFER_FACTORY, tracesDoc, false)) {
            bucket.write(writer -> {
                for (int i = 0; i < traceCount; i++) {
                    final String traceId = String.format("%032x", ++nextTrace);
                    traceIds.add(HexStringUtil.decode(traceId));
                    bucket.insert(writer, new SpanKV(
                            SpanKey.builder()
                                    .traceId(traceId)
                                    .spanId(ROOT_SPAN)
                                    .parentSpanId("")
                                    .build(),
                            SpanValue.builder()
                                    .name(OPERATION)
                                    .startTimeUnixNano(NanoTimeUtil.fromInstant(ran))
                                    .endTimeUnixNano(NanoTimeUtil.fromInstant(ran.plusMillis(20)))
                                    .insertTime(NanoTimeUtil.fromInstant(Instant.now()))
                                    .build()));
                }
            });
            final Path queue = Files.createDirectories(tempDir.resolve("queue"));
            new QueueItemWriter(BYTE_BUFFERS, BYTE_BUFFER_FACTORY,
                    Files.createDirectories(tempDir.resolve("local_build")))
                    .write(bucket, traceIds, queue, System.currentTimeMillis(), null);
            processor.mergeShard(new MergeContext(doc, 0, "test-lock", false),
                    List.of(Files.list(queue).findFirst().orElseThrow()));
        }
    }

    private DayRecord onlyRecordIn(final String week) throws IOException {
        final List<DayRecord> found = new ArrayList<>();
        shardStore.readBucket(doc, 0, week, db -> {
            db.getObservedValues().iterate((key, value) ->
                    found.add(new PathwaySerde(BYTE_BUFFER_FACTORY).readObservedValues(value)));
            return null;
        });
        assertThat(found).hasSize(1);
        return found.getFirst();
    }

    private long liveShard(final java.util.function.Function<PathwaysDb, Long> work) throws IOException {
        return shardStore.readShard(doc, 0, work).orElse(0L);
    }

    private static long count(final PathwaysDb db, final PathwaysDb.SimpleDb table) {
        final long[] rows = {0L};
        table.iterate((key, value) -> rows[0]++);
        return rows[0];
    }
}
