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

import stroom.pathways.shared.otel.trace.NanoTime;
import stroom.pathways.shared.pathway.AnyBoolean;
import stroom.pathways.shared.pathway.AnyTypeValue;
import stroom.pathways.shared.pathway.BooleanValue;
import stroom.pathways.shared.pathway.ConstraintValue;
import stroom.pathways.shared.pathway.DifferenceType;
import stroom.pathways.shared.pathway.IntegerRange;
import stroom.pathways.shared.pathway.IntegerValue;
import stroom.pathways.shared.pathway.Paths;
import stroom.pathways.shared.pathway.PathwayDifference;
import stroom.pathways.shared.pathway.StringSet;
import stroom.pathways.shared.pathway.StringValue;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What moved between one day's model and the day before's.
 *
 * <p>This is the Changes table for a day the change history does not reach. There is nothing the
 * learner did to list, so the two days are compared.
 */
class TestDayDifferences {

    private static final String ROOT = "GET /orders";
    private static final int DAY = 20_400;

    @Test
    void aNodeTheDayBeforeDidNotHaveArrives() {
        final List<PathwayDifference> moved = DayDifferences.between(
                day(node(ROOT)),
                day(node(ROOT), node(ROOT, "Commit")),
                DAY);

        assertThat(kinds(moved)).containsExactly(DifferenceType.NODE_ARRIVED);
        assertThat(moved.get(0).getNodePath()).isEqualTo(List.of(ROOT, "Commit"));
        assertThat(moved.get(0).getDay()).isEqualTo(DAY);
    }

    @Test
    void anArrivingNodeNamesTheTraceThatBroughtIt() {
        // The histogram counts the traces these rows name, and the Changes table shows them. A day
        // record holds no trace against a node, so the earliest trace behind one of its bounds is
        // the one that first reached it.
        final Node arriving = nodeSeenAt(List.of(ROOT, "Commit"), Map.of(
                "duration", seen(new IntegerValue(7), "c".repeat(32), "d".repeat(16), 1_760_000_000_500L),
                "rows", seen(new IntegerValue(3), "e".repeat(32), "f".repeat(16), 1_760_000_000_100L)));

        final List<PathwayDifference> moved = DayDifferences.between(
                day(node(ROOT)),
                day(node(ROOT), arriving),
                DAY);

        assertThat(kinds(moved)).containsExactly(DifferenceType.NODE_ARRIVED);
        assertThat(moved.get(0).getTraceId())
                .as("the earlier of the two bounds, not whichever is listed first")
                .isEqualTo("e".repeat(32));
        assertThat(moved.get(0).getSpanId()).isEqualTo("f".repeat(16));
        assertThat(moved.get(0).getRanAt()).isEqualTo(NanoTime.ofMillis(1_760_000_000_100L));
    }

    @Test
    void anArrivingNodeWithNothingLearntFromNamesNoTrace() {
        // Every bound of the node was configured to be ignored, so nothing was observed and there is
        // no trace to take. The row still stands, because the node did arrive.
        final List<PathwayDifference> moved = DayDifferences.between(
                day(node(ROOT)),
                day(node(ROOT), node(ROOT, "Commit")),
                DAY);

        assertThat(kinds(moved)).containsExactly(DifferenceType.NODE_ARRIVED);
        assertThat(moved.get(0).getTraceId()).isNull();
    }

    @Test
    void aNodeTheWindowHasPassedLeaves() {
        // Not a retirement. A day's model is the fold of a window, so a node leaves it when the last
        // day carrying it falls out behind the window's edge.
        final List<PathwayDifference> moved = DayDifferences.between(
                day(node(ROOT), node(ROOT, "Commit")),
                day(node(ROOT)),
                DAY);

        assertThat(kinds(moved)).containsExactly(DifferenceType.NODE_LEFT);
        assertThat(moved.get(0).getNodePath()).isEqualTo(List.of(ROOT, "Commit"));
    }

    @Test
    void aBoundThatAdmitsMoreWidened() {
        final List<PathwayDifference> moved = DayDifferences.between(
                day(node(ROOT, Map.of("size", new IntegerRange(5, 8)))),
                day(node(ROOT, Map.of("size", new IntegerRange(5, 12)))),
                DAY);

        assertThat(kinds(moved)).containsExactly(DifferenceType.WIDENED);
        assertThat(moved.get(0).getConstraint()).isEqualTo("size");
        assertThat(moved.get(0).getOldValue()).isEqualTo(new IntegerRange(5, 8));
        assertThat(moved.get(0).getNewValue()).isEqualTo(new IntegerRange(5, 12));
    }

    @Test
    void aBoundThatAdmitsLessNarrowed() {
        // A fold never narrows within itself, but a window that has moved on can hold less than it
        // did, so this really happens between two days.
        final List<PathwayDifference> moved = DayDifferences.between(
                day(node(ROOT, Map.of("size", new IntegerRange(5, 12)))),
                day(node(ROOT, Map.of("size", new IntegerRange(5, 8)))),
                DAY);

        assertThat(kinds(moved)).containsExactly(DifferenceType.NARROWED);
    }

    @Test
    void aBoundThatGainedOneEndAndLostTheOtherShifted() {
        final List<PathwayDifference> moved = DayDifferences.between(
                day(node(ROOT, Map.of("size", new IntegerRange(5, 10)))),
                day(node(ROOT, Map.of("size", new IntegerRange(8, 14)))),
                DAY);

        assertThat(kinds(moved))
                .as("neither a widening nor a narrowing, because it is both")
                .containsExactly(DifferenceType.SHIFTED);
    }

    @Test
    void aValueOfAnotherTypeIsNotReadAsAWidening() {
        final List<PathwayDifference> moved = DayDifferences.between(
                day(node(ROOT, Map.of("tenant", new StringValue("acme")))),
                day(node(ROOT, Map.of("tenant", new IntegerValue(7)))),
                DAY);

        assertThat(kinds(moved)).containsExactly(DifferenceType.TYPE_CHANGED);
    }

    @Test
    void aConstraintThatHadGivenUpStillNarrows() {
        // The one the naive test gets wrong. ObservedValues.add answers with what it was given where
        // that already says there was too much to list, so asking it one way round only would read
        // this as no change at all.
        final List<PathwayDifference> moved = DayDifferences.between(
                day(node(ROOT, Map.of("tenant", new AnyTypeValue()))),
                day(node(ROOT, Map.of("tenant", new StringValue("acme")))),
                DAY);

        assertThat(kinds(moved))
                .as("it admitted anything and now admits one thing")
                .containsExactly(DifferenceType.NARROWED);
    }

    @Test
    void aConstraintThatGivesUpWidens() {
        final List<PathwayDifference> moved = DayDifferences.between(
                day(node(ROOT, Map.of("tenant", new StringValue("acme")))),
                day(node(ROOT, Map.of("tenant", new AnyTypeValue()))),
                DAY);

        assertThat(kinds(moved)).containsExactly(DifferenceType.WIDENED);
    }

    @Test
    void aSetThatGrewWidened() {
        final List<PathwayDifference> moved = DayDifferences.between(
                day(node(ROOT, Map.of("tenant", new StringSet(java.util.Set.of("acme"))))),
                day(node(ROOT, Map.of("tenant", new StringSet(java.util.Set.of("acme", "globex"))))),
                DAY);

        assertThat(kinds(moved)).containsExactly(DifferenceType.WIDENED);
    }

    @Test
    void tooManyValuesToListIsNotReadAsAChangeOfType() {
        // Combining enough strings gives up and says it admits anything. Both days held strings, so
        // what happened is that the constraint stopped being able to list them - not that the work
        // started carrying a different kind of value. Shifted, because these two days really did
        // each hold something the other did not.
        final List<PathwayDifference> moved = DayDifferences.between(
                day(node(ROOT, Map.of("tenant", manyStrings("a", 15)))),
                day(node(ROOT, Map.of("tenant", manyStrings("b", 15)))),
                DAY);

        assertThat(kinds(moved)).containsExactly(DifferenceType.SHIFTED);
    }

    @Test
    void eitherBooleanFoldedWithOneBooleanIsStillABoolean() {
        // Asked the other way round this used to give up on the type, so the same two days read as
        // a change of type one way and a narrowing the other.
        assertThat(kinds(DayDifferences.between(
                day(node(ROOT, Map.of("ok", new AnyBoolean()))),
                day(node(ROOT, Map.of("ok", new BooleanValue(true)))),
                DAY)))
                .containsExactly(DifferenceType.NARROWED);
        assertThat(kinds(DayDifferences.between(
                day(node(ROOT, Map.of("ok", new BooleanValue(true)))),
                day(node(ROOT, Map.of("ok", new AnyBoolean()))),
                DAY)))
                .containsExactly(DifferenceType.WIDENED);
    }

    @Test
    void aConstraintTheDayBeforeDidNotHaveArrives() {
        final List<PathwayDifference> moved = DayDifferences.between(
                day(node(ROOT, Map.of())),
                day(node(ROOT, Map.of("tenant", new StringValue("acme")))),
                DAY);

        assertThat(kinds(moved)).containsExactly(DifferenceType.CONSTRAINT_ARRIVED);
        assertThat(moved.get(0).getOldValue()).isNull();
        assertThat(moved.get(0).getNewValue()).isEqualTo(new StringValue("acme"));
    }

    @Test
    void somethingAbsentWhereItWasLookedForBecameOptional() {
        final List<PathwayDifference> moved = DayDifferences.between(
                day(node(List.of(ROOT), Map.of("tenant", new StringValue("acme")), false)),
                day(node(List.of(ROOT), Map.of("tenant", new StringValue("acme")), true)),
                DAY);

        assertThat(kinds(moved)).containsExactly(DifferenceType.BECAME_OPTIONAL);
    }

    @Test
    void aBoundCanMoveOnTheSameDayItBecomesOptional() {
        final List<PathwayDifference> moved = DayDifferences.between(
                day(node(List.of(ROOT), Map.of("size", new IntegerRange(5, 8)), false)),
                day(node(List.of(ROOT), Map.of("size", new IntegerRange(5, 12)), true)),
                DAY);

        assertThat(kinds(moved))
                .as("two things happened and the list says both")
                .containsExactly(DifferenceType.WIDENED, DifferenceType.BECAME_OPTIONAL);
    }

    @Test
    void aDayWhereNothingMovedHasNothingToList() {
        final DayRecord same = day(node(ROOT, Map.of("tenant", new StringValue("acme"))),
                node(ROOT, "Commit"));

        assertThat(DayDifferences.between(same, same, DAY)).isEmpty();
    }

    @Test
    void aRowNamesTheTraceBehindTheValueItEndedOnAndTheNodeTheDrawingCallsIt() {
        final List<PathwayDifference> moved = DayDifferences.between(
                day(node(ROOT, Map.of("size", new IntegerRange(5, 8)))),
                day(node(ROOT, Map.of("size", new IntegerRange(5, 12)))),
                DAY);

        assertThat(moved.get(0).getTraceId()).isEqualTo(TRACE);
        assertThat(moved.get(0).getSpanId()).isEqualTo(SPAN);
        assertThat(moved.get(0).getRanAt()).isNotNull();
        assertThat(moved.get(0).getNodeUuid())
                .as("so a row can pick its node out on the drawing beside it")
                .isNotNull();
    }

    // -----------------------------------------------------------------------
    // Fixture
    // -----------------------------------------------------------------------

    private static final String TRACE = "a".repeat(32);
    private static final String SPAN = "b".repeat(16);

    private static StringSet manyStrings(final String prefix, final int count) {
        final java.util.Set<String> values = new java.util.LinkedHashSet<>();
        for (int i = 0; i < count; i++) {
            values.add(prefix + i);
        }
        return new StringSet(values);
    }

    private static List<DifferenceType> kinds(final List<PathwayDifference> moved) {
        return moved.stream().map(PathwayDifference::getType).toList();
    }

    private static DayRecord day(final Node... nodes) {
        final Map<List<String>, DayRecord.ObservedNode> all = new LinkedHashMap<>();
        for (final Node node : nodes) {
            all.put(node.path, node.observed);
        }
        return new DayRecord(all, Paths.empty());
    }

    private record Node(List<String> path, DayRecord.ObservedNode observed) {

    }

    private static Node node(final String... path) {
        return node(List.of(path), Map.of(), false);
    }

    private static Node node(final String root, final Map<String, ConstraintValue> constraints) {
        return node(List.of(root), constraints, false);
    }

    private static Node nodeSeenAt(final List<String> path,
                                   final Map<String, DayRecord.ObservedConstraint> constraints) {
        return new Node(path, new DayRecord.ObservedNode(
                String.join("/", path), 1L, new LinkedHashMap<>(constraints)));
    }

    private static DayRecord.ObservedConstraint seen(final ConstraintValue value,
                                                     final String traceId,
                                                     final String spanId,
                                                     final long ranAtMs) {
        return new DayRecord.ObservedConstraint(value, false, traceId,
                NanoTime.ofMillis(ranAtMs), spanId);
    }

    private static Node node(final List<String> path,
                             final Map<String, ConstraintValue> constraints,
                             final boolean optional) {
        final Map<String, DayRecord.ObservedConstraint> observed = new LinkedHashMap<>();
        constraints.forEach((name, value) -> observed.put(name,
                new DayRecord.ObservedConstraint(value, optional, TRACE,
                        NanoTime.ofMillis(1_760_000_000_000L), SPAN)));
        return new Node(path, new DayRecord.ObservedNode(
                String.join("/", path), 1L, observed));
    }
}
