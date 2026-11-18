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
import stroom.pathways.shared.otel.trace.NanoDuration;
import stroom.pathways.shared.otel.trace.Span;
import stroom.pathways.shared.otel.trace.SpanKind;
import stroom.pathways.shared.otel.trace.Trace;
import stroom.pathways.shared.pathway.NamePathKey;
import stroom.pathways.shared.pathway.PathNode;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a node's own tally of changes counts.
 *
 * <p>It is there so the scale the drawing measures node sizes against is a walk of the model rather
 * than a read of every change ever made. That only holds while it counts what the drawing counts, so
 * these say what it leaves out.
 */
class TestNodeChangeCounts {

    private static final String OPERATION = "ProcessorTaskCreatorJob.run";
    private static final String PING = "Ping";
    private static final long BASE = 1_700_000_000_000_000_000L;

    @Test
    void theTraceThatBringsAPathwayIntoBeingCountsForNothing() {
        // It taught the model everything it knows. Counting it would leave every node reading as
        // having been changed at least once, which says nothing about where the churn is.
        final PathNode root = mutator().process(trace("t0", 10), key(), null, quiet(), doc());

        assertThat(root.getTimesChanged()).isZero();
        assertThat(root.getChildren().getFirst().getTimesChanged()).isZero();
    }

    @Test
    void aTraceThatWidensSomethingCountsOnce() {
        final PathNode first = mutator().process(trace("t0", 10), key(), null, quiet(), doc());
        final PathNode second = mutator().process(trace("t1", 40), key(), first, quiet(), doc());

        assertThat(second.getChildren().getFirst().getTimesChanged())
                .as("the second trace ran longer, so the child's duration had to widen")
                .isEqualTo(1);
    }

    @Test
    void aTraceThatChangesOneNodeSeveralTimesStillCountsOnce() {
        // This trace moves both the child's duration and how long after its parent it started, so two
        // changes are recorded against one node.
        final PathNode first = mutator().process(trace("t0", 10), key(), null, quiet(), doc());
        final NodeMutatorImpl mutator = mutator();
        final PathNode second = mutator.process(twoChanges("t1"), key(), first, quiet(), doc());

        assertThat(mutator.getMutations().stream()
                .filter(mutation -> PING.equals(mutation.getNodePath().getLast()))
                .count())
                .as("the test is worth nothing unless this trace really did change the node twice")
                .isEqualTo(2);
        assertThat(countsOf(second))
                .as("what is counted is traces, not changes")
                .containsEntry(PING, 1L);
    }

    @Test
    void eachFurtherTraceAddsOne() {
        PathNode node = mutator().process(trace("t0", 10), key(), null, quiet(), doc());
        node = mutator().process(trace("t1", 40), key(), node, quiet(), doc());
        node = mutator().process(trace("t2", 90), key(), node, quiet(), doc());

        assertThat(node.getChildren().getFirst().getTimesChanged()).isEqualTo(2);
    }

    @Test
    void aTraceThatTellsTheModelNothingNewCountsForNothing() {
        final PathNode first = mutator().process(trace("t0", 10), key(), null, quiet(), doc());
        final PathNode second = mutator().process(trace("t1", 10), key(), first, quiet(), doc());

        assertThat(second.getChildren().getFirst().getTimesChanged())
                .as("the same run again moved no bound, so nothing changed")
                .isZero();
    }

    private static Map<String, Long> countsOf(final PathNode root) {
        final Map<String, Long> counts = new HashMap<>();
        counts.put(root.getName(), root.getTimesChanged());
        root.getChildren().forEach(child -> counts.put(child.getName(), child.getTimesChanged()));
        return counts;
    }

    private static NodeMutatorImpl mutator() {
        return new NodeMutatorImpl(
                new CanonicalSpanOrder(doc().getTemporalOrderingTolerance()),
                new IgnoredAttributes(List.of()),
                new IgnoredSpans(List.of()));
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

    // One child under one root, the child running for as long as the test says.
    private static Trace trace(final String traceId, final int childDurationMillis) {
        return traceOf(traceId, 1, childDurationMillis);
    }

    // The child starts later and runs longer, so two of its constraints move at once.
    private static Trace twoChanges(final String traceId) {
        return traceOf(traceId, 9, 40);
    }

    private static Trace traceOf(final String traceId,
                                 final int childStartMillis,
                                 final int childDurationMillis) {
        final Span root = span(OPERATION, "r0", "", 0, 100);
        final Span child = span(PING, "c0", "r0", childStartMillis, childDurationMillis);
        final Map<String, List<Span>> byParent = new HashMap<>();
        byParent.put("", List.of(root));
        byParent.put("r0", List.of(child));
        return new Trace(traceId, byParent);
    }

    private static Span span(final String name,
                             final String spanId,
                             final String parentSpanId,
                             final int startMillis,
                             final int durationMillis) {
        final long start = BASE + NanoDuration.ofMillis(startMillis).getNanos();
        return Span.builder()
                .name(name)
                .spanId(spanId)
                .parentSpanId(parentSpanId)
                .kind(SpanKind.SPAN_KIND_INTERNAL)
                .startTimeUnixNano(Long.toString(start))
                .endTimeUnixNano(Long.toString(start + NanoDuration.ofMillis(durationMillis).getNanos()))
                .build();
    }
}
