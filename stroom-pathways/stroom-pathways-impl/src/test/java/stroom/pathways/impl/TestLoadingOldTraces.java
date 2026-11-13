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
import stroom.pathways.shared.pathway.DifferenceType;
import stroom.pathways.shared.pathway.HistoryBar;
import stroom.pathways.shared.pathway.NamePathKey;
import stroom.pathways.shared.pathway.NodeChange;
import stroom.pathways.shared.pathway.PathNode;
import stroom.pathways.shared.pathway.Pathway;
import stroom.pathways.shared.pathway.PathwayDifference;
import stroom.pathways.shared.pathway.PathwayMutation;
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
import stroom.query.api.TimeRange;
import stroom.query.api.TimeRanges;
import stroom.util.io.SimplePathCreator;
import stroom.util.shared.NullSafe;
import stroom.util.shared.PageRequest;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
        // The one that matters. The narrowing moves a day out once the window has passed it, so a
        // window ending a few days back straddles the move: its older days are in a bucket and its
        // newer ones are still in the live shard. A fold that reads either alone answers with part
        // of the window missing, and silently, because what comes back is a good model of the wrong
        // thing.
        load(daysAgo(10), 3);
        load(daysAgo(5), 2);

        // Ten days ago was already past the seven day window when it was loaded, so it went to a
        // bucket. Five days ago was inside it, so it is still in the shard. The window ending four
        // days ago reaches back eleven days and so covers both.
        final PathwayAsAt asAt = fetchAt(daysAgo(4));

        assertThat(asAt.isFolded()).isTrue();
        assertThat(asAt.getPathway().getRoot().getTimesUsed())
                .as("all five traces, three out of the bucket and two out of the live shard")
                .isEqualTo(5L);
    }

    @Test
    void aWindowThatMissesEveryDayDrawsNothing() throws IOException {
        load(daysAgo(400), 3);
        // Traffic today as well, because a pathway whose traces are all old history has no model
        // row and so cannot be fetched at all - a limit of its own, not the one being tested here.
        load(Instant.now(), 1);

        final PathwayAsAt asAt = fetchAt(daysAgo(200));

        assertThat(asAt.isFolded())
                .as("it took the folding route, because no change was made that long ago")
                .isTrue();
        assertThat(asAt.getPathway().getRoot())
                .as("the days are real but none of them is in this window")
                .isNull();
    }

    // -----------------------------------------------------------------------
    // Fixture
    // -----------------------------------------------------------------------

    private static String weekOf(final Instant ran) {
        return PathwayNarrower.weekOf((int) (ran.toEpochMilli() / TraceProcessor.MILLIS_PER_DAY));
    }

    @Test
    void aDateTheHistoryDoesNotReachIsAnsweredFromTheDayRecords() throws IOException {
        // The whole point of the rebuild. Traffic today teaches a model, history from a year ago
        // teaches nothing, and picking a date in that history still has to draw something.
        final Instant old = daysAgo(400);
        load(old, 3);
        load(Instant.now(), 1);

        final PathwayAsAt asAt = fetchAt(old);

        assertThat(asAt.isFolded())
                .as("built from day records, because no change was made that long ago")
                .isTrue();
        assertThat(asAt.getPathway().getRoot()).isNotNull();
        assertThat(asAt.getPathway().getRoot().getNodePath()).isEqualTo(List.of(OPERATION));
        assertThat(asAt.getPathway().getRoot().getTimesUsed())
                .as("the three traces that ran that day, not the one that ran today")
                .isEqualTo(3L);
    }

    @Test
    void todayIsStillAnsweredFromTheChangeHistory() throws IOException {
        load(daysAgo(400), 3);
        load(Instant.now(), 1);

        final PathwayAsAt asAt = fetchAt(Instant.now());

        assertThat(asAt.isFolded())
                .as("wound back, because the history reaches today")
                .isFalse();
        assertThat(asAt.getPathway().getRoot())
                .as("and it is the learnt model, which the fold is not")
                .isNotNull();
    }

    @Test
    void aFoldedModelIsScaledAgainstItsOwnWindow() throws IOException {
        final Instant old = daysAgo(400);
        load(old, 3);
        load(Instant.now(), 1);

        final PathwayAsAt asAt = fetchAt(old);

        assertThat(asAt.getUsageCeiling())
                .as("the busiest node over the window folded, not over the model's whole life")
                .isEqualTo(3L);
        assertThat(asAt.getChangeCeiling())
                .as("the most any node changed shape over the window, not over the model's life")
                .isEqualTo(1L);
        assertThat(asAt.getUsage().keySet())
                .as("named the way the model names its nodes, or the drawing finds neither")
                .isEqualTo(Set.of(asAt.getPathway().getRoot().getUuid()));
    }

    @Test
    void aFoldedNodeIsSizedByWhatChangedShape() throws IOException {
        // The drawing sizes a node by how much it has changed and colours it by how long ago. A
        // folded node went through the learner for neither, so both are worked out by comparing the
        // days in its window.
        final Instant old = daysAgo(400);
        load(old, 1);
        load(Instant.now(), 1);

        final PathwayAsAt asAt = fetchAt(old);

        assertThat(asAt.getChanges().keySet())
                .as("named the way the folded model names its nodes, or the drawing finds nothing")
                .isEqualTo(Set.of(asAt.getPathway().getRoot().getUuid()));
        final NodeChange change = asAt.getChanges().get(asAt.getPathway().getRoot().getUuid());
        assertThat(change.getCount())
                .as("the node arrived that day, which is one change of shape")
                .isEqualTo(1L);
        assertThat(change.getLastUpdated())
                .as("and when the trace that brought it ran, which is the colour")
                .isNotNull();
    }

    @Test
    void aBoundDriftingIsNotAChangeOfShape() throws IOException {
        // Timing bounds move on nearly every node every day because runs vary. Counting those would
        // give every node much the same figure and the sizes would say nothing.
        final Instant first = daysAgo(400);
        load(first, 1, 20L);
        load(first.plus(1, ChronoUnit.DAYS), 1, 90L);
        load(Instant.now(), 1);

        final PathwayAsAt asAt = fetchAt(first.plus(1, ChronoUnit.DAYS));

        final NodeChange change = asAt.getChanges().get(asAt.getPathway().getRoot().getUuid());
        assertThat(change.getCount())
                .as("the arrival on the first day and nothing for the duration widening on the second")
                .isEqualTo(1L);
    }

    @Test
    void everyDescribedDayIsLaidOutFromTheSameTree() throws IOException {
        // The drawing moves its nodes from one step to the next only where the step wants the same
        // set of them. A folded model holds the nodes its own day was given, so laid out from itself
        // each day is a different set and the picture is built again rather than moved.
        final Instant first = daysAgo(40);
        load(first, 1);
        load(first.plus(1, ChronoUnit.DAYS), 1);
        load(Instant.now(), 1);

        final PathNode one = fetchAt(first).getLayout();
        final PathNode two = fetchAt(first.plus(1, ChronoUnit.DAYS)).getLayout();

        assertThat(nodeIdentities(one))
                .as("the same tree whichever described day is asked for")
                .isEqualTo(nodeIdentities(two))
                .isNotEmpty();
        assertThat(nodeIdentities(one))
                .as("and it holds the day's own nodes, so the drawing has somewhere to put them")
                .containsAll(nodeIdentities(fetchAt(first).getPathway().getRoot()));
    }

    @Test
    void aWoundBackModelCarriesNoLayoutOfItsOwn() throws IOException {
        load(Instant.now(), 2);

        assertThat(reader().fetchPathwayAsAt(doc, OPERATION, null, null).orElseThrow().getLayout())
                .as("it is laid out from the model as it stands, which the browser already holds")
                .isNull();
    }

    private static Set<String> nodeIdentities(final PathNode root) {
        final Set<String> found = new HashSet<>();
        collectIdentities(root, found);
        return found;
    }

    private static void collectIdentities(final PathNode node, final Set<String> found) {
        if (node == null) {
            return;
        }
        found.add(node.getUuid());
        for (final PathNode child : NullSafe.list(node.getChildren())) {
            collectIdentities(child, found);
        }
    }

    private PathwayAsAt fetchAt(final Instant when) {
        return reader().fetchPathwayAsAt(doc, OPERATION,
                new TimeRange(null, null, null, when.toString()), null).orElseThrow();
    }

    @Test
    void aRangeBeforeTheModelExistedListsWhatMovedBetweenItsDays() throws IOException {
        // The point of the whole step. Those days have no change rows, so the table would be empty;
        // what it lists instead is how each day's model differed from the day before's.
        load(daysAgo(30), 1);
        load(daysAgo(20), 2);
        load(Instant.now(), 1);

        final PathwayMutationResultPage listed = reader().findMutations(doc,
                new FindPathwayMutationCriteria(new PageRequest(0, 1000), List.of(), null, OPERATION,
                        new TimeRange(null, null, daysAgo(31).toString(), daysAgo(19).toString())));

        assertThat(listed.getValues())
                .as("rows for days the change history does not reach")
                .isNotEmpty()
                .allMatch(row -> row instanceof PathwayDifference);
        assertThat(listed.getValues().stream()
                .map(row -> ((PathwayDifference) row).getType())
                .distinct())
                .as("the nodes those days first carried")
                .contains(DifferenceType.NODE_ARRIVED);
    }

    @Test
    void todayStillListsWhatTheLearnerDid() throws IOException {
        load(Instant.now(), 2);

        final PathwayMutationResultPage listed = reader().findMutations(doc,
                new FindPathwayMutationCriteria(new PageRequest(0, 1000), List.of(), null, OPERATION,
                        null));

        assertThat(listed.getValues())
                .as("changes, because the history reaches today")
                .isNotEmpty()
                .allMatch(row -> row instanceof PathwayMutation);
    }

    @Test
    void aRangeLeftOpenAtItsStartDoesNotFoldEveryDaySinceTheEpoch() {
        // An open start resolves to the epoch. Nothing before the oldest bucket can hold a record,
        // so folding back to 1970 would be fifty thousand shard reads for nothing.
        final long started = System.currentTimeMillis();
        final PathwayMutationResultPage listed = reader().findMutations(doc,
                new FindPathwayMutationCriteria(new PageRequest(0, 1000), List.of(), null, OPERATION,
                        new TimeRange(null, null, null, Instant.now().toString())));

        assertThat(listed.getValues()).isEmpty();
        assertThat(System.currentTimeMillis() - started)
                .as("bounded by what exists, not by how far back the range reaches")
                .isLessThan(10_000L);
    }

    @Test
    void openingTheListWithNoRangeShowsTheOlderDaysToo() throws IOException {
        // No range means the whole history, which is how the table is first opened. The days before
        // the model existed are part of that history, so they are listed with everything else.
        load(daysAgo(30), 2);
        load(Instant.now(), 1);

        final PathwayMutationResultPage listed = reader().findMutations(doc,
                new FindPathwayMutationCriteria(new PageRequest(0, 1000), List.of(), null, OPERATION,
                        null));

        assertThat(listed.getValues())
                .as("both kinds, because nothing was asked to be left out")
                .anyMatch(row -> row instanceof PathwayDifference)
                .anyMatch(row -> row instanceof PathwayMutation);
    }

    @Test
    void aDayGivenNothingOfItsOwnIsNotDescribed() throws IOException {
        // A day's model is a window of days folded together, so the day the oldest of them falls out
        // behind the window's edge holds less than the day before it. Nothing happened on that day
        // and it is not listed: a row there reads as the work having stopped when all it did was age
        // out of the window.
        load(daysAgo(30), 2);
        load(Instant.now(), 1);

        final PathwayMutationResultPage listed = reader().findMutations(doc,
                new FindPathwayMutationCriteria(new PageRequest(0, 1000), List.of(), null, OPERATION,
                        null));

        assertThat(listed.getValues().stream()
                .filter(row -> row instanceof PathwayDifference)
                .map(row -> ((PathwayDifference) row).getDay())
                .distinct())
                .as("the day the traces ran, and no other")
                .containsExactly(dayOf(daysAgo(30)));
    }

    @Test
    void theAllTimeRangeShowsTheOlderDaysToo() throws IOException {
        // All time is a range with both ends left open, not an absent one, and an open end resolves
        // to the largest time there is. Counted in days that is more than the count will hold, so
        // the end of the range has to be kept at the end rather than wrapping round to the start.
        load(daysAgo(30), 2);
        load(Instant.now(), 1);

        final PathwayMutationResultPage listed = reader().findMutations(doc,
                new FindPathwayMutationCriteria(new PageRequest(0, 1000), List.of(), null, OPERATION,
                        TimeRanges.ALL_TIME));

        assertThat(listed.getValues())
                .as("both kinds, the same as asking with no range at all")
                .anyMatch(row -> row instanceof PathwayDifference)
                .anyMatch(row -> row instanceof PathwayMutation);
    }

    @Test
    void thePlotHasABarPerDescribedDayAndSlicesForTheChanges() throws IOException {
        // The point of the bars: a range crossing the start of the history carries both kinds, and
        // only this end knows where that boundary falls.
        load(daysAgo(30), 2);
        load(daysAgo(20), 1);
        load(Instant.now(), 1);

        final List<HistoryBar> bars = reader().findMutations(doc,
                new FindPathwayMutationCriteria(new PageRequest(0, 1000), List.of(), null, OPERATION,
                        null)).getBars();

        assertThat(bars.stream().filter(HistoryBar::isWholeDay).map(HistoryBar::getFromMs))
                .as("one bar a day from the first that was given something, with no days skipped")
                .startsWith(startOfDay(dayOf(daysAgo(30))))
                .contains(startOfDay(dayOf(daysAgo(25))), startOfDay(dayOf(daysAgo(20))))
                .isSorted();
        assertThat(bars.stream().filter(HistoryBar::isWholeDay).count())
                .as("every day from the first given something to the day before the model existed")
                .isEqualTo((dayOf(Instant.now()) - dayOf(daysAgo(30))));
        assertThat(bars.stream().filter(HistoryBar::isWholeDay))
                .as("each covering the whole of its day and nothing more")
                .allMatch(bar -> (bar.getToMs() - bar.getFromMs()) + 1 == TraceProcessor.MILLIS_PER_DAY);
        assertThat(bars.stream().filter(bar -> !bar.isWholeDay()))
                .as("and the changes cut into slices of their own")
                .isNotEmpty()
                .allMatch(bar -> (bar.getToMs() - bar.getFromMs()) + 1 < TraceProcessor.MILLIS_PER_DAY);
    }

    @Test
    void aDaysBarSaysHowMuchMovedThatDay() throws IOException {
        // Not how many traces ran. A day record keeps one trace against each bound whatever ran, so
        // counting traces would give about five on the busiest day there has ever been. The bar is
        // what opening the day in the table shows, so the two always agree.
        load(daysAgo(30), 2);
        load(daysAgo(20), 1);
        load(Instant.now(), 1);

        final PathwayMutationResultPage listed = reader().findMutations(doc,
                new FindPathwayMutationCriteria(new PageRequest(0, 1000), List.of(), null,
                        OPERATION, null));

        long described = 0L;
        for (final HistoryBar bar : listed.getBars()) {
            if (bar.isWholeDay()) {
                final long rows = listed.getValues().stream()
                        .filter(row -> row instanceof PathwayDifference)
                        .filter(row -> startOfDay(((PathwayDifference) row).getDay()) == bar.getFromMs())
                        .count();
                assertThat(bar.getCount())
                        .as("the rows that day opens to, for the day starting " + bar.getFromMs())
                        .isEqualTo(rows);
                described += rows > 0
                        ? 1L
                        : 0L;
            }
        }
        assertThat(described)
                .as("the two days that were given traces, the rest standing empty between them")
                .isEqualTo(2L);
    }

    @Test
    void aDayGivenNothingStandsEmptyRatherThanBeingLeftOut() throws IOException {
        // Every bar takes the same room on screen, so a day left out would stand one in October next
        // to one in November and say the work ran on both and on nothing in between.
        // Ten days apart, which is wider than the window the days are folded over. Closer together
        // than that, the second day's fold still holds the first day's traces and nothing moved.
        load(daysAgo(30), 1);
        load(daysAgo(20), 1);
        load(Instant.now(), 1);

        final List<HistoryBar> days = reader().findMutations(doc,
                        new FindPathwayMutationCriteria(new PageRequest(0, 1000), List.of(), null,
                                OPERATION, null))
                .getBars().stream()
                .filter(HistoryBar::isWholeDay)
                .toList();

        assertThat(countOn(days, dayOf(daysAgo(30))))
                .as("the first day that was given something")
                .isEqualTo(1L);
        assertThat(countOn(days, dayOf(daysAgo(29))))
                .as("the quiet day after it still has a bar, of no height")
                .isZero();
        assertThat(countOn(days, dayOf(daysAgo(20))))
                .as("and the next day that was given something stands ten bars along")
                .isEqualTo(1L);
    }

    @Test
    void aLongStretchOfDaysIsShownInFewerWiderBars() throws IOException {
        // Every bar takes the same room on screen, so a bar a day would draw a year as hundreds of
        // slivers. The count gives way instead, and each bar covers more days.
        load(daysAgo(400), 1);
        load(daysAgo(200), 1);
        load(Instant.now(), 1);

        final List<HistoryBar> days = reader().findMutations(doc,
                        new FindPathwayMutationCriteria(new PageRequest(0, 1000), List.of(), null,
                                OPERATION, null))
                .getBars().stream()
                .filter(HistoryBar::isWholeDay)
                .toList();

        assertThat(days.size())
                .as("four hundred days shown in a panel's worth of bars, not four hundred of them")
                .isBetween(2, 40);
        assertThat(days.get(0).getToMs() - days.get(0).getFromMs())
                .as("so each one covers more than a day")
                .isGreaterThan(TraceProcessor.MILLIS_PER_DAY);
        assertThat(days.stream().mapToLong(HistoryBar::getCount).sum())
                .as("and nothing that moved is lost in the merging")
                .isEqualTo(listedDifferences());
        assertThat(days.get(days.size() - 1).getToMs())
                .as("stopping at the day before the model came into being, not past it")
                .isLessThan(startOfDay(dayOf(Instant.now())));
    }

    private long listedDifferences() throws IOException {
        return reader().findMutations(doc,
                        new FindPathwayMutationCriteria(new PageRequest(0, 10000), List.of(), null,
                                OPERATION, null))
                .getValues().stream()
                .filter(row -> row instanceof PathwayDifference)
                .count();
    }

    @Test
    void aPathwayWithNoDescribedDaysHasOnlyChangeBars() throws IOException {
        load(Instant.now(), 2);

        final List<HistoryBar> bars = reader().findMutations(doc,
                new FindPathwayMutationCriteria(new PageRequest(0, 1000), List.of(), null, OPERATION,
                        null)).getBars();

        assertThat(bars).isNotEmpty().noneMatch(HistoryBar::isWholeDay);
        assertThat(bars.stream().mapToLong(HistoryBar::getCount).sum())
                .as("every trace that taught the model stands in one bar or another")
                .isEqualTo(1L);
    }

    // What the plot says moved on one day, or nothing where that day has no bar at all.
    private static long countOn(final List<HistoryBar> days, final int day) {
        return days.stream()
                .filter(bar -> bar.getFromMs() == startOfDay(day))
                .mapToLong(HistoryBar::getCount)
                .findFirst()
                .orElse(-1L);
    }

    private static long startOfDay(final int day) {
        return (long) day * TraceProcessor.MILLIS_PER_DAY;
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
        load(ran, traceCount, 20L);
    }

    // The duration is what a second day can move without changing the shape of anything, which is the
    // whole of what a folded node's size leaves out.
    private void load(final Instant ran, final int traceCount, final long durationMs)
            throws IOException {
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
                                    .endTimeUnixNano(
                                            NanoTimeUtil.fromInstant(ran.plusMillis(durationMs)))
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
