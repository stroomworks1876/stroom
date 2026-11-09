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

import stroom.pathways.shared.PathwayAsAt;
import stroom.pathways.shared.otel.trace.NanoTime;
import stroom.pathways.shared.pathway.Constraint;
import stroom.pathways.shared.pathway.ConstraintValue;
import stroom.pathways.shared.pathway.IntegerValue;
import stroom.pathways.shared.pathway.MutationType;
import stroom.pathways.shared.pathway.NamePathKey;
import stroom.pathways.shared.pathway.PathNode;
import stroom.pathways.shared.pathway.Pathway;
import stroom.pathways.shared.pathway.PathwayMutation;
import stroom.query.api.TimeRange;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Turning a moment into a model.
 *
 * <p>A date is read against when the learner took each change in, because taking changes off a model
 * only works on an unbroken run from the newest. These say which changes come off for a given moment,
 * and what is answered for a moment the change history does not reach.
 */
class TestPathwayAsAt {

    private static final String NAME = "GET /orders";
    private static final long NOON = 1_700_000_000_000L;

    @Test
    void noMomentAsksForTheModelAsItStands() {
        final Pathway pathway = pathway(5);

        final PathwayAsAt asAt = ShardedPathwayReader.asAt(pathway, changes(), List.of(), null, null);

        assertThat(asAt.getAsAt()).isNull();
        assertThat(asAt.getPathway().getRoot().getConstraints().get("size").getValue())
                .as("nothing comes off a model nobody asked to wind back")
                .isEqualTo(new IntegerValue(5));
    }

    @Test
    void aMomentTakesOffEveryChangeLearntAfterIt() {
        final Pathway pathway = pathway(5);

        final PathwayAsAt asAt = ShardedPathwayReader.asAt(pathway, changes(), List.of(), NOON + 1_500, null);

        assertThat(asAt.getPathway().getRoot().getConstraints().get("size").getValue())
                .as("the change taken in at two seconds past is undone, the one at one second stands")
                .isEqualTo(new IntegerValue(3));
    }

    @Test
    void aMomentTheHistoryDoesNotReachHasNoModelToShow() {
        // Picked before the learner had taken anything in. Showing the model as it stands would hand
        // a reader today's model under an old date's heading.
        final PathwayAsAt asAt = ShardedPathwayReader.asAt(pathway(5), changes(), List.of(), NOON - 1, null);

        assertThat(asAt.getPathway().getRoot())
                .as("there is nothing to wind back through, so there is nothing to draw")
                .isNull();
        assertThat(asAt.getAsAt()).isEqualTo(NanoTime.ofMillis(NOON - 1));
    }

    @Test
    void theScaleIsWhatThePathwayHasEverDoneNotWhatItHadDoneByThen() {
        // Measured against the part on show, whatever was largest at that moment would always be
        // drawn at full size, so winding back would rescale the picture rather than shrink it.
        final Pathway pathway = pathway(5);

        final PathwayAsAt wound = ShardedPathwayReader.asAt(pathway, changes(), List.of(), NOON + 1_500, null);
        final PathwayAsAt now = ShardedPathwayReader.asAt(pathway, changes(), List.of(), null, null);

        assertThat(wound.getChangeCeiling()).isEqualTo(now.getChangeCeiling());
        assertThat(wound.getChangeCeiling())
                .as("taken off the node's own tally, which is kept for exactly this")
                .isEqualTo(7L);
    }

    @Test
    void everyNodeOnShowHasItsFiguresWithIt() {
        // A node the drawing has no figures for is drawn at its smallest in the colour of never
        // having changed, so a map that misses nodes shows as a picture that has mostly gone grey.
        final PathwayAsAt asAt = ShardedPathwayReader.asAt(pathway(5), changes(), List.of(),
                NOON + 1_500, null);

        assertThat(asAt.getChanges())
                .containsKey(asAt.getPathway().getRoot().getUuid());
        assertThat(asAt.getChanges().get(asAt.getPathway().getRoot().getUuid()).getLastUpdated())
                .as("the newest change at or before the moment, not the newest there has ever been")
                .isEqualTo(NanoTime.ofMillis(NOON + 1_000));
    }

    @Test
    void aMomentAfterEveryChangeIsTheModelAsItStands() {
        // "All time" and "Today" both end now, so they must answer identically. A moment that takes
        // nothing off is now, whatever the range said - and in particular it must not be given usage
        // readings, which lag a node's own tally and would draw the lines differently.
        final PathwayAsAt allTime = ShardedPathwayReader.asAt(pathway(5), changes(), List.of(),
                null, null);
        final PathwayAsAt today = ShardedPathwayReader.asAt(pathway(5), changes(), List.of(),
                NOON + 60_000, null);

        assertThat(today.getUsage())
                .as("nothing was wound back, so each node's own tally is what the drawing reads")
                .isEmpty();
        assertThat(today.getPathway().getRoot().getConstraints().get("size").getValue())
                .isEqualTo(allTime.getPathway().getRoot().getConstraints().get("size").getValue());
        assertThat(today.getChanges().keySet()).isEqualTo(allTime.getChanges().keySet());
        assertThat(today.getChangeCeiling()).isEqualTo(allTime.getChangeCeiling());
    }

    @Test
    void aRangeIsReadAsItsLatestTime() {
        // A range is picked, a drawing is one instant. The expressions are resolved here because the
        // browser has no parser for them.
        assertThat(ShardedPathwayReader.latestIn(new TimeRange("Test", null, null)))
                .as("an open end is the model as it stands")
                .isNull();
        assertThat(ShardedPathwayReader.latestIn(null))
                .as("no range at all is the same thing")
                .isNull();
        assertThat(ShardedPathwayReader.latestIn(
                new TimeRange("Test", null, ClientDateUtilStub.iso(NOON + 1_500))))
                .isEqualTo(NOON + 1_500);
    }

    @Test
    void aChangeCanBePickedByNumberRatherThanByTime() {
        // Stepping asks for them one at a time. Every change one trace made carries the same
        // timestamp, so a time could not tell two of them apart.
        final PathwayAsAt asAt = ShardedPathwayReader.asAt(pathway(5), changes(), List.of(), null, 1L);

        assertThat(asAt.getPathway().getRoot().getConstraints().get("size").getValue())
                .isEqualTo(new IntegerValue(3));
        assertThat(asAt.getAsAt())
                .as("the moment shown is when that change was taken in")
                .isEqualTo(NanoTime.ofMillis(NOON + 1_000));
    }

    @Test
    void theCutIsTheNewestChangeTakenInByThatMoment() {
        assertThat(ShardedPathwayReader.cutAt(changes(), NOON + 1_500)).isEqualTo(1L);
        assertThat(ShardedPathwayReader.cutAt(changes(), NOON + 2_500)).isEqualTo(2L);
        assertThat(ShardedPathwayReader.cutAt(changes(), NOON - 1))
                .as("nothing had been taken in by then")
                .isNull();
    }

    @Test
    void aChangeLandingLateIsStillFoundByWhenItWasLearnt() {
        // Its work ran long before the others; the learner took it in last, so it has the highest
        // number and comes off first. Ordering on when the work ran would scatter the run.
        final List<PathwayMutation> changes = changes();
        changes.add(change(3L, NOON + 3_000, NOON - 500_000, 5, 7));

        assertThat(ShardedPathwayReader.cutAt(changes, NOON + 3_500)).isEqualTo(3L);
        assertThat(ShardedPathwayReader.cutAt(changes, NOON + 2_500))
                .as("read against when it was learnt, not when its work ran")
                .isEqualTo(2L);
    }

    // A root whose "size" constraint has been widened twice: to 3 one second past noon, and to the
    // value given here two seconds past.
    private static Pathway pathway(final int size) {
        final PathNode root = PathNode.builder()
                .uuid("root-uuid")
                .name(NAME)
                .nodePath(List.of(NAME))
                .children(List.of())
                .constraints(Map.of("size", Constraint.builder()
                        .name("size")
                        .value(new IntegerValue(size))
                        .build()))
                .timesChanged(7L)
                .build();
        return Pathway.builder()
                .name(NAME)
                .createTime(NanoTime.ofMillis(NOON))
                .updateTime(NanoTime.ofMillis(NOON + 2_000))
                .lastUsedTime(NanoTime.ofMillis(NOON + 2_000))
                .pathKey(new NamePathKey(NAME))
                .root(root)
                .build();
    }

    // An absolute time in the form a range carries one, which is what the expression parser reads
    // when the range was not written as an expression.
    private static final class ClientDateUtilStub {

        private static String iso(final long ms) {
            return java.time.Instant.ofEpochMilli(ms).toString();
        }
    }

    private static List<PathwayMutation> changes() {
        final List<PathwayMutation> changes = new ArrayList<>();
        // One chain: nothing, then 3, then 5. Each change says what it moved the value from, so
        // taking it off puts back what the one before it left.
        changes.add(change(1L, NOON + 1_000, NOON + 1_000, null, 3));
        changes.add(change(2L, NOON + 2_000, NOON + 2_000, 3, 5));
        return changes;
    }

    private static PathwayMutation change(final long sequence,
                                          final long learntMs,
                                          final long ranMs,
                                          final Integer from,
                                          final int to) {
        final ConstraintValue was = from == null
                ? null
                : new IntegerValue(from);
        return new PathwayMutation(sequence,
                NanoTime.ofMillis(learntMs),
                NanoTime.ofMillis(ranMs),
                0,
                "trace-" + sequence,
                "span-" + sequence,
                List.of(NAME),
                "root-uuid",
                "size",
                MutationType.CONSTRAINT_MAX_EXPANDED,
                false,
                was,
                new IntegerValue(to));
    }
}
