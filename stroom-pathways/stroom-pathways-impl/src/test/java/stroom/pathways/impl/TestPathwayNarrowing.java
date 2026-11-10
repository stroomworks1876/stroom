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

import stroom.bytebuffer.ByteBufferUtils;
import stroom.bytebuffer.impl6.ByteBufferFactoryImpl;
import stroom.bytebuffer.impl6.ByteBuffers;
import stroom.pathways.shared.PathwaysDoc;
import stroom.pathways.shared.otel.trace.AnyValue;
import stroom.pathways.shared.otel.trace.KeyValue;
import stroom.pathways.shared.otel.trace.NanoDuration;
import stroom.pathways.shared.otel.trace.Span;
import stroom.pathways.shared.otel.trace.SpanKind;
import stroom.pathways.shared.otel.trace.Trace;
import stroom.pathways.shared.pathway.ConstraintValue;
import stroom.pathways.shared.pathway.IntegerSet;
import stroom.pathways.shared.pathway.MutationType;
import stroom.pathways.shared.pathway.NanoTimeRange;
import stroom.pathways.shared.pathway.NanoTimeValue;
import stroom.pathways.shared.pathway.PathNode;
import stroom.pathways.shared.pathway.Pathway;
import stroom.pathways.shared.pathway.PathwayMutation;
import stroom.pathways.shared.pathway.PathwayReplay;
import stroom.pathways.shared.pathway.PathwayUsage;
import stroom.planb.impl.dao.LmdbWriter;
import stroom.planb.impl.dao.trace.PathwaysDb;
import stroom.util.shared.time.SimpleDuration;
import stroom.util.shared.time.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Holding a model to what it has been given lately.
 *
 * <p>A trace only ever widens a model, so one outlier leaves an envelope that admits everything from
 * then on. The narrowing puts each constraint back to what the day by day account still covers,
 * which is what makes the next widening worth being told about.
 */
class TestPathwayNarrowing {

    private static final ByteBufferFactoryImpl BYTE_BUFFER_FACTORY = new ByteBufferFactoryImpl();
    private static final ByteBuffers BYTE_BUFFERS = new ByteBuffers(BYTE_BUFFER_FACTORY);
    private static final String OPERATION = "ProcessorTaskCreatorJob.run";
    private static final long DAY_MS = 86_400_000L;
    private static final long BASE = 1_700_000_000_000L / DAY_MS * DAY_MS;

    @Test
    void anOutlierThatHasFallenOutOfTheWindowIsNoLongerAllowedFor(@TempDir final Path tempDir) {
        // Day one is slow, the two after it are not. The model has widened to admit the slow one and
        // can never narrow itself again.
        applied(tempDir, List.of(trace(0, 900), trace(1, 20), trace(2, 25)));

        assertThat(duration(tempDir))
                .as("every trace widens, so the model allows everything it has ever seen")
                .isEqualTo(new NanoTimeRange(millis(20), millis(900)));

        // Held to the two days after the outlier.
        narrow(tempDir, day(1));

        assertThat(duration(tempDir))
                .as("narrowed to what the window still covers")
                .isEqualTo(new NanoTimeRange(millis(20), millis(25)));
    }

    @Test
    void narrowingIsRecordedSoAReplayStillFollowsTheModel(@TempDir final Path tempDir) {
        applied(tempDir, List.of(trace(0, 900), trace(1, 20)));
        narrow(tempDir, day(1));

        final List<PathwayMutation> narrowed = mutations(tempDir).stream()
                .filter(m -> MutationType.CONSTRAINT_NARROWED.equals(m.getType()))
                .toList();

        assertThat(narrowed).hasSize(1);
        assertThat(narrowed.getFirst().getConstraint()).isEqualTo("duration");
        assertThat(narrowed.getFirst().getOldValue()).isEqualTo(new NanoTimeRange(millis(20), millis(900)));
        assertThat(narrowed.getFirst().getNewValue()).isEqualTo(new NanoTimeValue(millis(20)));
        assertThat(narrowed.getFirst().getSource())
                .as("no trace caused it, so it names no trace store")
                .isEqualTo(-1);
    }

    @Test
    void aModelAlreadyAsNarrowAsItsWindowIsLeftAlone(@TempDir final Path tempDir) {
        applied(tempDir, List.of(trace(0, 20), trace(1, 25)));

        assertThat(narrow(tempDir, day(0)))
                .as("nothing to narrow is nothing to write, and nothing to push back")
                .isFalse();
    }

    @Test
    void daysOutsideTheWindowAreNotKept(@TempDir final Path tempDir) {
        applied(tempDir, List.of(trace(0, 900), trace(1, 20), trace(2, 25)));

        assertThat(observedValueRecords(tempDir)).isEqualTo(3);
        narrow(tempDir, day(1));
        assertThat(observedValueRecords(tempDir))
                .as("the only thing that bounds what this table holds")
                .isEqualTo(2);
    }

    @Test
    void aHoldWritesWhatItWasGivenOnceRatherThanOncePerTrace(@TempDir final Path tempDir) {
        // Three traces of one day, the last two inside what the first two already covered.
        assertThat(recordsWritten(tempDir, List.of(trace(0, 20), trace(0, 900), trace(0, 500))))
                .as("one day touched is one record written, however many traces were applied to it")
                .isEqualTo(1);
    }

    @Test
    void aTraceInsideWhatTheDayCoversStillMovesItsCounts(@TempDir final Path tempDir) {
        // This used to write nothing: a trace that widened no value left the day as it was, and
        // writing it back would have serialised the whole day to say so. A day now counts how many
        // traces took each walk and how busy each node was, so every applied trace moves it and the
        // saving is gone. What is still true is that the values do not move.
        applied(tempDir, List.of(trace(0, 20), trace(0, 900)));
        final Object covered = duration(tempDir);

        assertThat(recordsWritten(tempDir, List.of(trace(0, 500)))).isEqualTo(1);
        assertThat(duration(tempDir))
                .as("a trace inside what the day already covers widens nothing")
                .isEqualTo(covered);
    }

    @Test
    void twoDaysInOneHoldAreWrittenApart(@TempDir final Path tempDir) {
        assertThat(recordsWritten(tempDir, List.of(trace(0, 20), trace(1, 900)))).isEqualTo(2);
    }

    @Test
    void aPathNothingTakesAnyMoreIsDropped(@TempDir final Path tempDir) {
        // Two shapes: the first day runs one child, the days after it run two.
        applied(tempDir, List.of(withChildren(0, "Ping"), withChildren(1, "Ping", "Commit"),
                withChildren(2, "Ping", "Commit")));

        assertThat(onlyPathway(tempDir).getPaths().getPaths()).hasSize(2);

        narrow(tempDir, day(1));

        assertThat(onlyPathway(tempDir).getPaths().getPaths())
                .as("the shape nothing has run since is no longer a way through the work")
                .hasSize(1);
        assertThat(mutations(tempDir)).anyMatch(m -> MutationType.PATH_DROPPED.equals(m.getType()));
    }

    @Test
    void aNodeNothingCarriesAnyMoreIsRetiredRatherThanRemoved(@TempDir final Path tempDir) {
        applied(tempDir, List.of(withChildren(0, "Ping", "Commit"), withChildren(1, "Ping"),
                withChildren(2, "Ping")));

        narrow(tempDir, day(1));

        final PathNode commit = child(tempDir, "Commit");
        assertThat(commit)
                .as("kept, because paths name nodes by position and a replay puts them back by uuid")
                .isNotNull();
        assertThat(commit.isRetired()).isTrue();
        assertThat(mutations(tempDir)).anyMatch(m -> MutationType.NODE_RETIRED.equals(m.getType()));
    }

    @Test
    void aSpanAndTheValuesItCoversAreNotADifferentConstraint(@TempDir final Path tempDir) {
        // Carried, then not, then carried again. The model names the two counts it was given; the
        // window summarises the same two as a span, because that is how it summarises anything that
        // orders. The two say the same thing and neither should be written over the other.
        applied(tempDir, List.of(withChildren(0, "Ping"), withChildren(1), withChildren(2, "Ping")));

        assertThat(occurrences(tempDir, "Ping")).isEqualTo(new IntegerSet(Set.of(0, 1)));

        assertThat(narrow(tempDir, day(0)))
                .as("nothing is narrower than anything, so there is nothing to write")
                .isFalse();
        assertThat(mutations(tempDir)).noneMatch(m -> MutationType.CONSTRAINT_NARROWED.equals(m.getType()));
    }

    @Test
    void aSpanTheModelDoesNotCoverInFullIsNoNarrowing(@TempDir final Path tempDir) {
        // Carried once, then three times. The window gives those back as one to three, and the model
        // was never given a two.
        applied(tempDir, List.of(withChildren(0, "Ping"), withChildren(1, "Ping", "Ping", "Ping")));

        assertThat(occurrences(tempDir, "Ping")).isEqualTo(new IntegerSet(Set.of(1, 3)));

        narrow(tempDir, day(0));

        assertThat(occurrences(tempDir, "Ping"))
                .as("holding it to one to three would have it start admitting a two it was never given")
                .isEqualTo(new IntegerSet(Set.of(1, 3)));
    }

    @Test
    void aSetNarrowedToFewerValuesIsStillASet(@TempDir final Path tempDir) {
        // Carried twice, then not at all, then once. The model names all three counts. Drop the day
        // that carried it twice and the window has a nought and a one left, which it summarises as a
        // span because that is how it summarises anything that orders.
        applied(tempDir, List.of(
                withChildren(0, "Ping", "Ping"),
                withChildren(1),
                withChildren(2, "Ping")));

        assertThat(occurrences(tempDir, "Ping")).isEqualTo(new IntegerSet(Set.of(0, 1, 2)));

        narrow(tempDir, day(1));

        assertThat(occurrences(tempDir, "Ping"))
                .as("the two values it was given are named, not handed back as the span they arrived in")
                .isEqualTo(new IntegerSet(Set.of(0, 1)));
    }

    @Test
    void retiringANodeRetiresWhatRanUnderIt(@TempDir final Path tempDir) {
        // Only whatever reaches a node records how many times it carried its children, so a node under
        // one nothing reached has no account of itself at all. Left to its own evidence it would stay
        // lit beneath a parent that is not.
        applied(tempDir, List.of(nested(0, "Commit", "Batch"), withChildren(1, "Ping")));

        narrow(tempDir, day(1));

        final PathNode commit = child(tempDir, "Commit");
        assertThat(commit.isRetired()).isTrue();
        assertThat(commit.getChildren().getFirst().getName()).isEqualTo("Batch");
        assertThat(commit.getChildren().getFirst().isRetired())
                .as("a node is no part of the work when the thing that ran it is no part of it either")
                .isTrue();
    }

    @Test
    void everythingTheNightChangedIsReported(@TempDir final Path tempDir) {
        applied(tempDir, List.of(withChildren(0, "Ping", "Commit"), withChildren(1, "Ping")));
        final int before = mutations(tempDir).size();

        final List<String> lines = new ArrayList<>();
        narrow(tempDir, day(1), (severity, message) -> lines.add(message.get()));

        final int written = mutations(tempDir).size() - before;
        assertThat(written).isPositive();
        assertThat(lines)
                .as("a line for every row the narrowing put in the history, the way a trace says what it"
                    + " widened")
                .hasSize(written);
        assertThat(lines).anyMatch(line -> line.startsWith("Retiring node: "));
    }

    @Test
    void aTraceThatCarriesARetiredNodeBringsItBackThereAndThen(@TempDir final Path tempDir) {
        applied(tempDir, List.of(withChildren(0, "Ping", "Commit"), withChildren(1, "Ping")));
        narrow(tempDir, day(1));
        assertThat(child(tempDir, "Commit").isRetired()).isTrue();

        // Carried again, with no narrowing in between to notice.
        applied(tempDir, List.of(withChildren(2, "Ping", "Commit")));

        assertThat(child(tempDir, "Commit").isRetired())
                .as("the model must not go on calling a node retired while traces run through it")
                .isFalse();
        assertThat(mutations(tempDir))
                .as("and the trace that brought it back is the change that says so")
                .anyMatch(m -> MutationType.NODE_REVIVED.equals(m.getType()) && m.getTraceId() != null);
    }

    @Test
    void aRetiredNodeIsLiveAgainWhereTheReplayStandsBeforeItWasRetired(@TempDir final Path tempDir) {
        // The drawing is laid out from the model as it stands, so every node it places is a node that
        // has been retired by now. What it shows each one as has to come from the model the replay is
        // standing in, or a node reads as retired throughout a history it was working for most of.
        applied(tempDir, List.of(withChildren(0, "Ping", "Commit"), withChildren(1, "Ping")));
        narrow(tempDir, day(1));

        assertThat(child(tempDir, "Commit").isRetired()).isTrue();

        final PathNode before = PathwayReplay.rewind(onlyPathway(tempDir).getRoot(),
                from(tempDir, MutationType.NODE_RETIRED));

        assertThat(child(before, "Commit").isRetired())
                .as("it was still part of the work at the point being looked at")
                .isFalse();
    }

    @Test
    void aNodeThatComesBackIsTheSameNode(@TempDir final Path tempDir) {
        applied(tempDir, List.of(withChildren(0, "Ping", "Commit"), withChildren(1, "Ping")));
        narrow(tempDir, day(1));
        final String uuid = child(tempDir, "Commit").getUuid();

        applied(tempDir, List.of(withChildren(2, "Ping", "Commit")));
        narrow(tempDir, day(2));

        assertThat(child(tempDir, "Commit").isRetired()).isFalse();
        assertThat(child(tempDir, "Commit").getUuid())
                .as("the same node as before, so what it was and how it was used are still its own")
                .isEqualTo(uuid);
        assertThat(mutations(tempDir)).anyMatch(m -> MutationType.NODE_REVIVED.equals(m.getType()));
    }

    @Test
    void aWindowOfSevenDaysKeepsTheSevenDaysBeforeNow() {
        final Instant midnight = Instant.ofEpochMilli(BASE + (10 * DAY_MS));

        assertThat(PathwayNarrower.oldestDayInWindow(windowOf(7, TimeUnit.DAYS), midnight))
                .isEqualTo(day(3));
    }

    @Test
    void aWindowShorterThanADayIsHeldToOneDay() {
        final Instant midnight = Instant.ofEpochMilli(BASE + (10 * DAY_MS));

        assertThat(PathwayNarrower.oldestDayInWindow(windowOf(1, TimeUnit.MINUTES), midnight))
                .as("values are kept a day at a time, so a minute would otherwise mean whichever day"
                    + " the pass happened to run on")
                .isEqualTo(day(9));
        assertThat(PathwayNarrower.oldestDayInWindow(windowOf(1, TimeUnit.DAYS), midnight))
                .as("the same as asking for a day, which is what it is held to")
                .isEqualTo(day(9));
    }

    @Test
    void theDayAPassRunsOnDoesNotChangeHowFarBackItReaches() {
        final Instant midnight = Instant.ofEpochMilli(BASE + (10 * DAY_MS));
        final Instant noon = midnight.plusMillis(DAY_MS / 2);

        assertThat(PathwayNarrower.oldestDayInWindow(windowOf(1, TimeUnit.MINUTES), noon))
                .as("a day back from noon is still yesterday")
                .isEqualTo(day(9));
    }

    private static PathwaysDoc windowOf(final long time, final TimeUnit unit) {
        return doc().copy().narrowingWindow(new SimpleDuration(time, unit)).build();
    }

    @Test
    void aDocumentThatRefusesToLearnIsNotTaughtOvernight(@TempDir final Path tempDir) {
        // Learnt while it was allowed to, then locked and given a value well outside what it holds.
        applied(tempDir, List.of(trace(0, 20)), doc());
        applied(tempDir, List.of(trace(1, 900)), doc().copy().allowPathwayMutation(false).build());

        assertThat(duration(tempDir))
                .as("the trace was refused, as the document asked")
                .isEqualTo(new NanoTimeValue(millis(20)));

        narrow(tempDir, day(1));

        assertThat(duration(tempDir))
                .as("and must stay refused: what a document will not learn from must not reach the"
                    + " model when the narrowing runs either")
                .isEqualTo(new NanoTimeValue(millis(20)));
    }

    @Test
    void anEndLeftOpenOnPurposeIsNotClosedOvernight(@TempDir final Path tempDir) {
        final PathwaysDoc doc = doc().copy().ignoredAttributes(List.of("duration.max")).build();
        applied(tempDir, List.of(trace(0, 20), trace(1, 900)), doc);

        assertThat(duration(tempDir)).isEqualTo(new NanoTimeRange(millis(20), null));

        narrow(tempDir, day(1));

        assertThat(duration(tempDir))
                .as("the bottom is held to what the window still covers, and the top stays unasserted"
                    + " because the configuration asked for it never to be asserted")
                .isEqualTo(new NanoTimeRange(millis(900), null));
    }

    @Test
    void theLearntEndOfAOneSidedDurationIsStillNarrowed(@TempDir final Path tempDir) {
        // Leaving the bottom unasserted is the usual way of saying a node has no floor worth
        // reporting. It says nothing about the top, which is the end that widens on an outlier and so
        // the end that most needs holding to the window.
        final PathwaysDoc doc = doc().copy().ignoredAttributes(List.of("duration.min")).build();
        applied(tempDir, List.of(trace(0, 900), trace(1, 20)), doc);

        assertThat(duration(tempDir)).isEqualTo(new NanoTimeRange(null, millis(900)));

        narrow(tempDir, day(1));

        assertThat(duration(tempDir))
                .as("the outlier has fallen out of the window, so the top comes back to what is left")
                .isEqualTo(new NanoTimeRange(null, millis(20)));
    }

    @Test
    void droppingADayIsEnoughToSayTheShardChanged(@TempDir final Path tempDir) {
        // The model is already as narrow as the window, so nothing about it moves — but the day that
        // has fallen out of the window still has to go, and saying nothing changed would throw the
        // deletion away with the rest of the shard.
        applied(tempDir, List.of(trace(0, 20), trace(1, 20)));

        assertThat(narrow(tempDir, day(1)))
                .as("a day dropped is a shard changed, or it is dropped again on every run for ever")
                .isTrue();
        assertThat(observedValueRecords(tempDir)).isEqualTo(1);

        assertThat(narrow(tempDir, day(1)))
                .as("and once it is gone there is nothing left to change")
                .isFalse();
    }

    private static boolean narrow(final Path dir, final int oldestDay) {
        return narrow(dir, oldestDay, (severity, message) -> {
        });
    }

    @Test
    void aDayThatFallsOutOfTheWindowIsHandedOverBeforeItIsDeleted(@TempDir final Path tempDir) {
        // The row being deleted is the day's only copy. Handing it over first is what lets it be kept
        // somewhere that can still answer for that day; handing it over afterwards would lose it.
        applied(tempDir, List.of(trace(0, 900), trace(1, 20), trace(2, 25)));

        final Map<String, Integer> handedOver = new LinkedHashMap<>();
        narrow(tempDir, day(1), quiet(), (dateLabel, dayRecords, logRows) ->
                handedOver.merge(dateLabel, dayRecords.size(), Integer::sum));

        assertThat(handedOver.values().stream().mapToInt(Integer::intValue).sum())
                .as("day zero fell out of the window, so its record was handed over")
                .isEqualTo(1);
        assertThat(handedOver.keySet())
                .allSatisfy(label -> assertThat(label).startsWith("week-"));
    }

    @Test
    void aDayIsKeptWhereItCouldNotBeHandedOver(@TempDir final Path tempDir) {
        // Deleting it anyway would lose the only copy of a day nothing else can answer for, so a
        // failure has to leave the shard as it was and let the next pass try again.
        applied(tempDir, List.of(trace(0, 900), trace(1, 20), trace(2, 25)));

        assertThatThrownBy(() -> narrow(tempDir, day(1), quiet(), (dateLabel, dayRecords, logRows) -> {
            throw new IllegalStateException("the shared store is unreachable");
        })).isInstanceOf(IllegalStateException.class);

        final Map<String, Integer> handedOver = new LinkedHashMap<>();
        narrow(tempDir, day(1), quiet(), (dateLabel, dayRecords, logRows) ->
                handedOver.merge(dateLabel, dayRecords.size(), Integer::sum));
        assertThat(handedOver.values().stream().mapToInt(Integer::intValue).sum())
                .as("still there to be handed over on the next pass")
                .isEqualTo(1);
    }

    @Test
    void aTraceThatFallsOutOfTheWindowTakesItsChangesAndNodesWithIt(@TempDir final Path tempDir) {
        // A change row holds no time and no node name - both are resolved when it is read, from the
        // trace row it points at and the node row it names. Moved without them it cannot be read.
        applied(tempDir, List.of(trace(0, 900), trace(1, 20), trace(2, 25)));

        // Log rows leave on when the learner took them in, not on when their work ran, and this
        // harness applies everything at the real clock. So the boundary has to be after that for any
        // of them to have fallen out.
        final Map<String, Integer> logRowsByWeek = new LinkedHashMap<>();
        final Map<String, Integer> dayRecordsByWeek = new LinkedHashMap<>();
        narrow(tempDir, tomorrow(), quiet(), (dateLabel, dayRecords, logRows) -> {
            logRowsByWeek.merge(dateLabel, logRows.size(), Integer::sum);
            dayRecordsByWeek.merge(dateLabel, dayRecords.size(), Integer::sum);
        });

        assertThat(logRowsByWeek.values().stream().mapToInt(Integer::intValue).sum())
                .as("the traces that fell out, the changes they made, and the nodes those name")
                .isGreaterThan(3);

        // The two kinds are on two clocks, so they land in different weeks: these traces ran years
        // before the moment this test applied them.
        final String appliedWeek = weekWith(logRowsByWeek);
        final String ranWeek = weekWith(dayRecordsByWeek);
        assertThat(appliedWeek)
                .as("log rows go to the week they were applied in")
                .isNotEqualTo(ranWeek);
        assertThat(dayRecordsByWeek.getOrDefault(appliedWeek, 0))
                .as("and carry no day records with them")
                .isZero();
        assertThat(logRowsByWeek.getOrDefault(ranWeek, 0))
                .as("nor the other way about")
                .isZero();
    }

    @Test
    void howBusyEachNodeWasSurvivesTheChainBeingSplit(@TempDir final Path tempDir) {
        // A reading says only what moved since the one before it. Cut the chain and both halves are
        // wrong unless the first reading on each side says where everything stood.
        applied(tempDir, List.of(trace(0, 900), trace(1, 20), trace(2, 25)));
        final List<PathwayUsage> before = usage(tempDir);

        narrow(tempDir, tomorrow(), quiet(), (dateLabel, dayRecords, logRows) -> {
        });

        assertThat(before).as("there were readings to split").isNotEmpty();
        assertThat(usage(tempDir))
                .as("everything was handed over, so nothing is left to read")
                .isEmpty();
    }

    @Test
    void theFirstReadingHandedOverSaysWhereEverythingStood(@TempDir final Path tempDir) {
        // A reading says only what moved since the one before it. The first one in a bucket has
        // nothing before it there, so it has to say where everything stood or the totals read low.
        applied(tempDir, List.of(trace(0, 900), trace(1, 20), trace(2, 25)));
        final List<PathwayUsage> before = usage(tempDir);
        assertThat(before).hasSizeGreaterThan(1);
        final int everyNode = before.get(before.size() - 1).getNodes().size();

        final List<PathwaySerde.StoredUsage> handedOver = new ArrayList<>();
        narrow(tempDir, tomorrow(), quiet(), (dateLabel, dayRecords, logRows) ->
                logRows.forEach(row -> {
                    if (isUsage(row)) {
                        handedOver.add(readUsage(row));
                    }
                }));

        assertThat(handedOver).as("there were readings to hand over").isNotEmpty();
        assertThat(handedOver.get(0).moved())
                .as("the first names every node, not only the ones that moved in it")
                .hasSize(everyNode);
        assertThat(handedOver.get(0).moved())
                .as("and each carries its own last used time rather than the flag meaning this one")
                .allSatisfy(count -> assertThat(count.usedNow()).isFalse());
    }

    @Test
    void theNodeRowsAreCopiedRatherThanMoved(@TempDir final Path tempDir) {
        // A change row names its node by a number, and the node row is what turns that back into a
        // path. Move those out and every change left behind loses the name of what it changed.
        applied(tempDir, List.of(trace(0, 900), trace(1, 20), trace(2, 25)));

        final List<MutationLog.Row> handedOver = new ArrayList<>();
        narrow(tempDir, tomorrow(), quiet(), (dateLabel, dayRecords, logRows) ->
                handedOver.addAll(logRows));

        assertThat(handedOver).as("something was handed over").isNotEmpty();
        assertThat(handedOver.stream().filter(TestPathwayNarrowing::isNode).count())
                .as("the bucket is given the node rows, because it cannot read its changes without them")
                .isPositive();
        assertThat(nodeRowsLeft(tempDir))
                .as("and they are still here, because the changes left behind need them too")
                .isPositive();
    }

    private static long nodeRowsLeft(final Path dir) {
        try (final PathwaysDb db = PathwaysDb.create(dir, BYTE_BUFFERS, false)) {
            final long[] count = {0};
            db.getMutations().iterate((key, value) -> {
                if (key.get(key.position() + OPERATION.getBytes(StandardCharsets.UTF_8).length) == 2) {
                    count[0]++;
                }
            });
            return count[0];
        }
    }

    // A node row is marked 2.
    private static boolean isNode(final MutationLog.Row row) {
        return row.key()[OPERATION.getBytes(StandardCharsets.UTF_8).length] == 2;
    }

    // The marker sits straight after the pathway key, and a usage row is marked 1.
    private static boolean isUsage(final MutationLog.Row row) {
        return row.key()[OPERATION.getBytes(StandardCharsets.UTF_8).length] == 1;
    }

    private static PathwaySerde.StoredUsage readUsage(final MutationLog.Row row) {
        final ByteBuffer buffer = ByteBuffer.allocateDirect(row.value().length);
        buffer.put(row.value()).flip();
        return serde().readUsage(buffer);
    }

    private static List<PathwayUsage> usage(final Path dir) {
        try (final PathwaysDb db = PathwaysDb.create(dir, BYTE_BUFFERS, false)) {
            return mutationLog().readUsage(db.getMutations(), OPERATION.getBytes(StandardCharsets.UTF_8));
        }
    }


    private static String weekWith(final Map<String, Integer> byWeek) {
        return byWeek.entrySet().stream()
                .filter(entry -> entry.getValue() > 0)
                .map(Map.Entry::getKey)
                .findFirst()
                .orElseThrow();
    }

    // A boundary past everything this test applied, since the harness applies at the real clock.
    private static int tomorrow() {
        return (int) ((System.currentTimeMillis() + DAY_MS) / DAY_MS);
    }

    private static MessageReceiver quiet() {
        return (severity, message) -> {
        };
    }

    private static boolean narrow(final Path dir, final int oldestDay, final MessageReceiver messages) {
        // Dropped rather than archived, which is what these are about: where a day goes once the
        // window has passed it is a separate question from what the narrowing does to the model.
        return narrow(dir, oldestDay, messages, PathwayNarrower.ExpiredArchiver.DISCARD);
    }

    private static boolean narrow(final Path dir,
                                  final int oldestDay,
                                  final MessageReceiver messages,
                                  final PathwayNarrower.ExpiredArchiver archiver) {
        return new PathwayNarrower(serde(), mutationLog(), BYTE_BUFFERS)
                .narrow(dir, oldestDay, messages, archiver);
    }

    // Applies each trace as the processor would, so the model and the day by day account are built the
    // way they are in the running system rather than written by hand.
    private static void applied(final Path dir, final List<Trace> traces) {
        recordsWritten(dir, traces, doc());
    }

    private static void applied(final Path dir, final List<Trace> traces, final PathwaysDoc doc) {
        recordsWritten(dir, traces, doc);
    }

    private static int recordsWritten(final Path dir, final List<Trace> traces) {
        return recordsWritten(dir, traces, doc());
    }

    private static int recordsWritten(final Path dir, final List<Trace> traces, final PathwaysDoc doc) {
        try (final PathwaysDb db = PathwaysDb.create(dir, BYTE_BUFFERS, false)) {
            try (final LmdbWriter writer = db.createWriter()) {
                final TraceProcessor processor = new TraceProcessor(mutationLog(), BYTE_BUFFERS, serde(),
                        new IgnoredAttributes(doc.getIgnoredAttributes()),
                        new stroom.planb.impl.dao.trace.IgnoredSpans(List.of()));
                for (final Trace trace : traces) {
                    processor.processTrace(writer, db, idBytes(trace), id -> java.util.Optional.of(trace),
                            doc, (severity, message) -> {
                            }, null);
                }
                // What the hold was given is written once at the end of it, as the processor does.
                final int written = processor.writeObservedValues(writer, db);
                writer.commit();
                return written;
            }
        }
    }

    private static Object duration(final Path dir) {
        return onlyPathway(dir).getRoot().getConstraints().get("duration").getValue();
    }

    // Every change from the first of the given type onwards, newest first, which is what a replay
    // takes off the model to stand just before that change was made.
    private static List<PathwayMutation> from(final Path dir, final MutationType type) {
        final List<PathwayMutation> all = mutations(dir);
        final long sequence = all.stream()
                .filter(m -> type.equals(m.getType()))
                .mapToLong(PathwayMutation::getSequence)
                .min()
                .orElseThrow();
        return all.stream()
                .filter(m -> m.getSequence() >= sequence)
                .sorted(Comparator.comparingLong(PathwayMutation::getSequence).reversed())
                .toList();
    }

    private static PathNode child(final PathNode root, final String name) {
        return root.getChildren().stream()
                .filter(node -> name.equals(node.getName()))
                .findFirst()
                .orElse(null);
    }

    private static ConstraintValue occurrences(final Path dir, final String name) {
        return child(dir, name).getConstraints().get("occurrences").getValue();
    }

    private static PathNode child(final Path dir, final String name) {
        return onlyPathway(dir).getRoot().getChildren().stream()
                .filter(node -> name.equals(node.getName()))
                .findFirst()
                .orElse(null);
    }

    private static Pathway onlyPathway(final Path dir) {
        final List<Pathway> found = new ArrayList<>();
        try (final PathwaysDb db = PathwaysDb.create(dir, BYTE_BUFFERS, false)) {
            db.getPathways().iterate((key, value) -> found.add(serde().readPathway(value)));
        }
        return found.getFirst();
    }

    private static List<PathwayMutation> mutations(final Path dir) {
        // Read back through the log rather than straight off the table: a change names its node and
        // the trace that made it by number, and only the log knows what those numbers stand for.
        final List<PathwayMutation> found = new ArrayList<>();
        try (final PathwaysDb db = PathwaysDb.create(dir, BYTE_BUFFERS, false)) {
            final List<String> names = new ArrayList<>();
            db.getPathways().iterate((key, value) -> names.add(serde().readPathway(value).getName()));
            final MutationLog log = new MutationLog(BYTE_BUFFERS, serde());
            names.forEach(name ->
                    found.addAll(log.read(db.getMutations(), name.getBytes(StandardCharsets.UTF_8))));
        }
        return found;
    }

    @Test
    void aShardIsNarrowedOnceADayAtMidnight() {
        final Instant noon = Instant.parse("2026-10-13T12:00:00Z");

        assertThat(PathwayNarrower.narrowingDue(null, noon))
                .as("never narrowed, so due — which is what deleting the marker asks for")
                .isTrue();
        assertThat(PathwayNarrower.narrowingDue(Instant.parse("2026-10-13T00:00:30Z"), noon))
                .as("already narrowed today, so not again until tomorrow")
                .isFalse();
        assertThat(PathwayNarrower.narrowingDue(Instant.parse("2026-10-13T23:59:59Z"), noon))
                .as("the same day, however late in it")
                .isFalse();
        assertThat(PathwayNarrower.narrowingDue(Instant.parse("2026-10-12T23:59:59Z"),
                        Instant.parse("2026-10-13T00:00:01Z")))
                .as("a second past midnight is a new day, so the pass just after it narrows")
                .isTrue();
    }

    @Test
    void narrowingDoesNotCreepThroughTheDay() {
        // Due a day after the last run, a pass that ran at 00:00:30 would next be due at 00:00:30
        // tomorrow and run at the first pass after that, so each day it starts a little later than
        // the last. Held to the day boundary it does not.
        final Instant ranJustAfterMidnight = Instant.parse("2026-10-13T00:00:30Z");

        assertThat(PathwayNarrower.narrowingDue(ranJustAfterMidnight,
                        Instant.parse("2026-10-14T00:00:10Z")))
                .as("twenty seconds short of a day later, but a new day, so it runs")
                .isTrue();
    }

    @Test
    void aTraceAppliedTooLongAgoIsForgotten(@TempDir final Path dir) {
        applied(dir, List.of(trace(0, 20), trace(1, 25)));
        assertThat(appliedTraceRecords(dir))
                .as("one per trace applied, so the same trace handed over twice is applied once")
                .isEqualTo(2);

        // Measured against the clock the entries were written by rather than the days the traces ran
        // on: what is remembered is when this shard applied something, not when it happened.
        final int today = (int) (System.currentTimeMillis() / DAY_MS);

        narrow(dir, today);
        assertThat(appliedTraceRecords(dir))
                .as("applied today, so still worth remembering")
                .isEqualTo(2);

        narrow(dir, today + 1);
        assertThat(appliedTraceRecords(dir))
                .as("older than the window, so forgotten: a trace that old could teach the model "
                    + "nothing that survived the next pass anyway")
                .isZero();
    }

    @Test
    void anAppliedTraceWithNoTimeIsForgotten(@TempDir final Path dir) {
        applied(dir, List.of(trace(0, 20)));
        undatedRecord(dir, "no time against it");
        assertThat(appliedTraceRecords(dir)).isEqualTo(2);

        // Today's run, so nothing that carries a time is old enough to go. The one that carries none
        // goes anyway: no run will ever find it old, so no run would ever take it.
        narrow(dir, (int) (System.currentTimeMillis() / DAY_MS));
        assertThat(appliedTraceRecords(dir))
                .as("the dated one is still within the window and the undated one is gone")
                .isEqualTo(1);
    }

    // An entry against a trace saying only that it was applied, which is all an entry has to say for
    // the processor to leave the trace alone.
    private static void undatedRecord(final Path dir, final String traceId) {
        try (final PathwaysDb db = PathwaysDb.create(dir, BYTE_BUFFERS, false)) {
            try (final LmdbWriter writer = db.createWriter()) {
                BYTE_BUFFERS.useBytes(traceId.getBytes(StandardCharsets.UTF_8),
                        (Consumer<ByteBuffer>) key ->
                                db.getProcessingStatus().insert(writer, key, ByteBuffer.allocateDirect(0)));
                writer.commit();
            }
        }
    }

    private static int appliedTraceRecords(final Path dir) {
        final int[] count = {0};
        try (final PathwaysDb db = PathwaysDb.create(dir, BYTE_BUFFERS, false)) {
            db.getProcessingStatus().iterate((key, value) -> count[0]++);
        }
        return count[0];
    }

    @Test
    void aDayRecordSaysWhichWalksWereTakenThatDay(@TempDir final Path tempDir) {
        // One trace per day here: the fixture names a trace after the day it ran, so a second on the
        // same day is taken as one already applied.
        applied(tempDir, List.of(withChildren(0, "Ping", "Commit")));

        final DayRecord record = onlyDayRecord(tempDir);

        assertThat(record.paths().getPaths())
                .as("the walk that day, recorded against the day itself rather than only the model")
                .hasSize(1);
        assertThat(record.paths().getNodes())
                .as("named the way the model names them, which the nodes below resolve")
                .isNotEmpty();
        assertThat(record.nodes().keySet())
                .as("nodes named by the path that reaches them")
                .contains(List.of(OPERATION), List.of(OPERATION, "Ping"));
        assertThat(record.nodes().get(List.of(OPERATION)).nodeUuid())
                .as("and by what the model called them, which is how the walks resolve")
                .isNotBlank();
    }

    @Test
    void twoTracesGivingADayTheSameThingLeaveEverythingButTheCountsAlone(@TempDir final Path tempDir) {
        // Everything in a day record is a union except the counts, which is what lets old history be
        // loaded without a marker per trace saying it already has been. Two traces rather than one
        // applied twice, because the same trace twice never reaches the record - the marker stops it,
        // and a test that went that way would be testing the marker.
        applied(tempDir, List.of(withChildren(0, "Ping", "Commit")));
        final DayRecord first = onlyDayRecord(tempDir);

        applied(tempDir, List.of(withChildren(0, "Ping", "Commit")));
        final DayRecord second = onlyDayRecord(tempDir);

        assertThat(second.paths().getNodes()).isEqualTo(first.paths().getNodes());
        assertThat(second.paths().getSteps())
                .as("the same walk, numbered the same way, because it is numbered from what it holds")
                .isEqualTo(first.paths().getSteps());
        assertThat(second.nodes().keySet()).isEqualTo(first.nodes().keySet());
        assertThat(second.nodes().get(List.of(OPERATION)).constraints())
                .as("and the same values, from the same traces")
                .isEqualTo(first.nodes().get(List.of(OPERATION)).constraints());

        assertThat(second.paths().getPaths().getFirst().getTimesUsed())
                .as("the counts are the exemption: two traces took that walk, not one")
                .isEqualTo(2L);
        assertThat(second.nodes().get(List.of(OPERATION)).timesUsed())
                .as("and so is how busy the node was")
                .isEqualTo(2L);
    }

    @Test
    void onlyTheTracesStillBehindAValueAreStored(@TempDir final Path tempDir) {
        // A day record must not become a list of the day's traffic. Every trace that widened a bound
        // once is superseded by the next one to widen it further, and a trace nothing names any more
        // has no claim on anything - so it must not be in the record at all.
        final List<Trace> busyDay = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            busyDay.add(trace(0, 20 + i));
        }
        applied(tempDir, busyDay);

        final long traces = onlyDayRecord(tempDir).nodes().values().stream()
                .flatMap(node -> node.constraints().values().stream())
                .map(DayRecord.ObservedConstraint::traceId)
                .filter(Objects::nonNull)
                .distinct()
                .count();

        assertThat(traces)
                .as("twenty five traces, and only the few that still justify a bound are named")
                .isLessThan(10L);
    }

    @Test
    void aNodeThatRanButTaughtNothingIsStillInTheDay(@TempDir final Path tempDir) {
        // A day record is where the nodes of a derived model come from, so one that ran has to be in
        // it whether or not it gave the model anything. With constraint creation off nothing on a new
        // node is learnt from, which is the simplest way to reach that state; an attribute the model
        // does not learn from reaches it too.
        final PathwaysDoc noLearning = PathwaysDoc.builder()
                .uuid(UUID.randomUUID().toString())
                .name("Test Pathways")
                .allowPathwayCreation(true)
                .allowPathwayMutation(true)
                .allowConstraintCreation(false)
                .allowConstraintMutation(true)
                .build();
        applied(tempDir, List.of(withChildren(0, "Ping")), noLearning);

        final DayRecord record = onlyDayRecord(tempDir);

        assertThat(record.nodes().keySet())
                .as("both nodes ran, so both are in the day")
                .contains(List.of(OPERATION), List.of(OPERATION, "Ping"));
        final DayRecord.ObservedNode ping = record.nodes().get(List.of(OPERATION, "Ping"));
        assertThat(ping.constraints())
                .as("and it gave the model nothing")
                .isEmpty();
        assertThat(ping.timesUsed())
                .as("but it still ran once, which is what sizes its line")
                .isEqualTo(1L);
        assertThat(ping.nodeUuid())
                .as("and the walks taken that day name it by this")
                .isNotBlank();
    }

    private static DayRecord onlyDayRecord(final Path dir) {
        return readRecord(dayRecordBytes(dir));
    }

    private static DayRecord readRecord(final byte[] bytes) {
        final Function<ByteBuffer, DayRecord> read = buffer -> serde().readObservedValues(buffer);
        return BYTE_BUFFERS.useBytes(bytes, read);
    }

    private static byte[] dayRecordBytes(final Path dir) {
        final List<byte[]> found = new ArrayList<>();
        try (final PathwaysDb db = PathwaysDb.create(dir, BYTE_BUFFERS, true)) {
            db.getObservedValues().iterate((key, value) -> found.add(ByteBufferUtils.toBytes(value)));
        }
        assertThat(found).hasSize(1);
        return found.getFirst();
    }

    private static int observedValueRecords(final Path dir) {
        final int[] count = {0};
        try (final PathwaysDb db = PathwaysDb.create(dir, BYTE_BUFFERS, false)) {
            db.getObservedValues().iterate((key, value) -> count[0]++);
        }
        return count[0];
    }

    private static int day(final int dayIndex) {
        return (int) ((BASE + (dayIndex * DAY_MS)) / DAY_MS);
    }

    private static stroom.pathways.shared.otel.trace.NanoTime millis(final int millis) {
        return stroom.pathways.shared.otel.trace.NanoTime.ofMillis(millis);
    }

    private static PathwaySerde serde() {
        return new PathwaySerde(BYTE_BUFFER_FACTORY);
    }

    private static MutationLog mutationLog() {
        return new MutationLog(BYTE_BUFFERS, serde());
    }

    private static PathwaysDoc doc() {
        return PathwaysDoc.builder()
                .uuid(UUID.randomUUID().toString())
                .name("Test Pathways")
                .allowPathwayCreation(true)
                .allowPathwayMutation(true)
                .allowConstraintCreation(true)
                .allowConstraintMutation(true)
                .build();
    }

    // Every trace built here gets its own id. The key is the id's first sixteen bytes, so the day
    // leads it to keep ids readable, but a counter follows: two traces on one day sharing an id are
    // taken as one already applied, and a test that means to apply both silently applies the first.
    private static final AtomicInteger NEXT = new AtomicInteger();

    private static String nextId(final int dayIndex) {
        return String.format("%02d%014d", dayIndex, NEXT.incrementAndGet()) + "0".repeat(16);
    }

    private static byte[] idBytes(final Trace trace) {
        final byte[] bytes = new byte[16];
        final byte[] id = trace.getTraceId().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        System.arraycopy(id, 0, bytes, 0, Math.min(id.length, 16));
        return bytes;
    }

    // A root on the given day running the named children, one after another.
    private static Trace withChildren(final int dayIndex, final String... childNames) {
        final long start = (BASE + (dayIndex * DAY_MS)) * 1_000_000L;
        final Span root = span(OPERATION, "r" + dayIndex, "", start, 20);
        final List<Span> children = new ArrayList<>();
        for (int i = 0; i < childNames.length; i++) {
            children.add(span(childNames[i], "c" + dayIndex + i, "r" + dayIndex,
                    start + NanoDuration.ofMillis(i + 1L).getNanos(), 1));
        }
        final Map<String, List<Span>> byParent = new HashMap<>();
        byParent.put("", List.of(root));
        byParent.put("r" + dayIndex, children);
        return new Trace(nextId(dayIndex), byParent);
    }

    // A root on the given day running one child, which runs one of its own.
    private static Trace nested(final int dayIndex, final String childName, final String grandchildName) {
        final long start = (BASE + (dayIndex * DAY_MS)) * 1_000_000L;
        final Map<String, List<Span>> byParent = new HashMap<>();
        byParent.put("", List.of(span(OPERATION, "r" + dayIndex, "", start, 20)));
        byParent.put("r" + dayIndex, List.of(span(childName, "c" + dayIndex, "r" + dayIndex,
                start + NanoDuration.ofMillis(1L).getNanos(), 5)));
        byParent.put("c" + dayIndex, List.of(span(grandchildName, "g" + dayIndex, "c" + dayIndex,
                start + NanoDuration.ofMillis(2L).getNanos(), 1)));
        return new Trace(nextId(dayIndex), byParent);
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

    // One root span on the given day, lasting the given number of milliseconds.
    private static Trace trace(final int dayIndex, final int durationMillis) {
        final long start = (BASE + (dayIndex * DAY_MS)) * 1_000_000L;
        final Span root = Span.builder()
                .name(OPERATION)
                .spanId("r" + dayIndex)
                .parentSpanId("")
                .kind(SpanKind.SPAN_KIND_INTERNAL)
                .startTimeUnixNano(Long.toString(start))
                .endTimeUnixNano(Long.toString(start + NanoDuration.ofMillis(durationMillis).getNanos()))
                .attributes(List.of(KeyValue.builder()
                        .key("http.method")
                        .value(new AnyValue("GET", null, null, null, null, null, null))
                        .build()))
                .build();
        final Map<String, List<Span>> byParent = new HashMap<>();
        byParent.put("", List.of(root));
        return new Trace(nextId(dayIndex), byParent);
    }
}
