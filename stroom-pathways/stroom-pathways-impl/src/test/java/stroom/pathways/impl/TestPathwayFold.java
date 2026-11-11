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

import stroom.pathways.shared.PathwaysDoc;
import stroom.pathways.shared.otel.trace.AnyValue;
import stroom.pathways.shared.otel.trace.KeyValue;
import stroom.pathways.shared.otel.trace.NanoDuration;
import stroom.pathways.shared.otel.trace.NanoTime;
import stroom.pathways.shared.otel.trace.Span;
import stroom.pathways.shared.otel.trace.SpanKind;
import stroom.pathways.shared.otel.trace.Trace;
import stroom.pathways.shared.pathway.NamePathKey;
import stroom.pathways.shared.pathway.PathNode;
import stroom.pathways.shared.pathway.Paths;
import stroom.pathways.shared.pathway.Pathway;
import stroom.planb.impl.dao.trace.CanonicalSpanOrder;
import stroom.planb.impl.dao.trace.IgnoredSpans;
import stroom.util.shared.NullSafe;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Building a model out of what a window of days was given.
 *
 * <p>The question that matters is whether it agrees with the learnt model. It cannot match it -
 * the learnt model holds everything never contradicted and a fold holds only what was seen - so the
 * rule is that a fold is never <b>wider</b>. Anything it claims, the model claims too.
 */
class TestPathwayFold {

    private static final IgnoredAttributes NO_IGNORED = new IgnoredAttributes(List.of());
    private static final String OPERATION = "GET /orders";
    private static final long BASE = 1_700_000_000_000L;

    @Test
    void theSameNodesAsTheModelTheSameTracesTaught() {
        final Seen seen = apply(trace(0, "Validate", "Commit"), trace(1, "Validate", "Commit"));

        final Pathway folded = PathwayFold.of(seen.record).model(key(), OPERATION);

        assertThat(pathsOf(folded.getRoot()))
                .as("the fixture only says something if there are nodes to compare")
                .hasSize(3)
                .as("every node the model has, and no others")
                .isEqualTo(pathsOf(seen.model));
    }

    @Test
    void aFoldNeverClaimsMoreThanTheModel() {
        // The model has seen this work five times over, so its bounds are wide. The fold has seen
        // one day of it.
        final Seen seen = apply(trace(0, "Validate"), trace(1, "Validate"), trace(2, "Validate"));

        final Pathway folded = PathwayFold.of(seen.record).model(key(), OPERATION);

        assertThat(constraintsOf(folded.getRoot()))
                .as("the fixture only says something if the fold claims anything at all")
                .hasSizeGreaterThan(3);
        constraintsOf(folded.getRoot()).forEach((name, value) ->
                assertThat(constraintsOf(seen.model))
                        .as("the model holds " + name + " and holds it at least as wide")
                        .containsKey(name));
    }

    @Test
    void eachNodeIsCountedTheWayTheModelCountsIt() {
        final Seen seen = apply(trace(0, "Validate", "Validate", "Commit"));

        final Pathway folded = PathwayFold.of(seen.record).model(key(), OPERATION);

        assertThat(usedOf(folded.getRoot()))
                .as("the fixture only says something if a node was reached twice")
                .containsEntry(List.of(OPERATION, "Validate"), 2L)
                .as("spans folded into each node, not traces")
                .isEqualTo(usedOf(seen.model));
    }

    @Test
    void anIdentityIsSafeToPutInMarkup() {
        // A node named by its path carries spaces, dots and slashes, and an identity ends up in an
        // SVG id where a space ends the reference and the line is drawn with no paint.
        final Seen seen = apply(trace(0, "UPDATE stroom.cluster_lock"));

        final Pathway folded = PathwayFold.of(seen.record).model(key(), OPERATION);

        assertThat(identitiesOf(folded.getRoot()))
                .allSatisfy(id -> assertThat(id).matches("[0-9a-f]{16}"));
        assertThat(identitiesOf(folded.getRoot()))
                .as("and one each, so two nodes are never confused")
                .doesNotHaveDuplicates();
    }

    @Test
    void aNodeKnowsWhenItWasLastUsedEvenThoughNoNodeRecordsIt() {
        // A day record counts spans and never times them. Only the paths carry a time, so this is
        // the one figure in a folded model that is worked out rather than read.
        final Seen seen = apply(trace(0, "Validate"), trace(5, "Validate"));

        final PathwayFold fold = PathwayFold.of(seen.record);
        final Pathway folded = fold.model(key(), OPERATION);

        assertThat(folded.getRoot().getLastUsedTime())
                .as("the latest path through it, which is the later of the two traces")
                .isEqualTo(ranAt(5));
        assertThat(fold.usage().get(folded.getRoot().getUuid()).getLastUsedTime())
                .as("and the usage says the same, because the drawing reads it from there")
                .isEqualTo(ranAt(5));
    }

    @Test
    void thePathsPointAtTheNodesTheModelNames() {
        // The record names its nodes by a stand-in that means nothing outside it. If the paths are
        // not told the new names, half of them point at nodes the tree no longer describes.
        final Seen seen = apply(trace(0, "Validate", "Commit"));

        final Pathway folded = PathwayFold.of(seen.record).model(key(), OPERATION);

        final Paths paths = folded.getPaths();
        assertThat(paths.getPaths()).as("the fixture only says something if a path was taken")
                .hasSize(1);
        assertThat(paths.getNodes())
                .as("the fixture only says something if the path names any nodes")
                .hasSize(3)
                .as("named the way the tree names them")
                .isSubsetOf(identitiesOf(folded.getRoot()));
    }

    @Test
    void aDayWithNothingInItFoldsToNothingRatherThanFailing() {
        final Pathway folded = PathwayFold.of(DayRecord.empty()).model(key(), OPERATION);

        assertThat(folded.getRoot()).isNull();
        assertThat(folded.getTimesUsed()).isZero();
    }

    // -----------------------------------------------------------------------
    // Fixture
    // -----------------------------------------------------------------------

    // What a set of traces taught a model, and what the same traces said about their day - the two
    // things this compares, built the way TraceProcessor builds them.
    private record Seen(PathNode model, DayRecord record) {

    }

    private static Seen apply(final Trace... traces) {
        PathNode model = null;
        DayRecord record = DayRecord.empty();
        for (final Trace trace : traces) {
            final NodeMutatorImpl mutator = mutator();
            model = mutator.process(trace, key(), model, quiet(), doc());
            final NanoTime ranAt = NanoTime.fromString(trace.root().getStartTimeUnixNano());
            record = DayRecord.merge(record, new DayRecord(mutator.getObservations().nodes(),
                    PathRecorder.add(Paths.empty(), mutator.getPathShape(), ranAt, ranAt,
                            trace.getTraceId(), null)));
        }
        return new Seen(model, record);
    }

    private static List<List<String>> pathsOf(final PathNode node) {
        final List<List<String>> paths = new ArrayList<>();
        collect(node, n -> paths.add(n.getNodePath()));
        return paths;
    }

    private static List<String> identitiesOf(final PathNode node) {
        final List<String> ids = new ArrayList<>();
        collect(node, n -> ids.add(n.getUuid()));
        return ids;
    }

    private static Map<List<String>, Long> usedOf(final PathNode node) {
        final Map<List<String>, Long> used = new LinkedHashMap<>();
        collect(node, n -> used.put(n.getNodePath(), n.getTimesUsed()));
        return used;
    }

    private static Map<String, String> constraintsOf(final PathNode node) {
        final Map<String, String> values = new LinkedHashMap<>();
        collect(node, n -> NullSafe.map(n.getConstraints())
                .forEach((name, constraint) -> values.put(n.getNodePath() + " " + name,
                        String.valueOf(constraint.getValue()))));
        return values;
    }

    private static void collect(final PathNode node, final Consumer<PathNode> to) {
        if (node != null) {
            to.accept(node);
            node.getChildren().forEach(child -> collect(child, to));
        }
    }

    private static NanoTime ranAt(final int index) {
        return NanoTime.fromString(Long.toString((BASE + (index * 1000L)) * 1_000_000L));
    }

    private static Trace trace(final int index, final String... childNames) {
        final long start = (BASE + (index * 1000L)) * 1_000_000L;
        final Span root = span(OPERATION, "r" + index, "", start, 20 + index);
        final List<Span> children = new ArrayList<>();
        for (int i = 0; i < childNames.length; i++) {
            children.add(span(childNames[i], "c" + index + "_" + i, "r" + index,
                    start + NanoDuration.ofMillis(i + 1L).getNanos(), 1 + index));
        }
        final Map<String, List<Span>> byParent = new LinkedHashMap<>();
        byParent.put("", List.of(root));
        byParent.put("r" + index, children);
        return new Trace(String.format("%032d", index), byParent);
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
                .attributes(List.of(new KeyValue("http.route", AnyValue.stringValue("/orders"))))
                .build();
    }

    private static NodeMutatorImpl mutator() {
        return new NodeMutatorImpl(new CanonicalSpanOrder(doc().getTemporalOrderingTolerance()),
                NO_IGNORED, new IgnoredSpans(List.of()));
    }

    private static NamePathKey key() {
        return new NamePathKey(OPERATION);
    }

    private static MessageReceiver quiet() {
        return (severity, message) -> {
        };
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
}
