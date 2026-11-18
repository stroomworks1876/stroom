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
import stroom.pathways.shared.otel.trace.Span;
import stroom.pathways.shared.otel.trace.SpanKind;
import stroom.pathways.shared.otel.trace.Trace;
import stroom.pathways.shared.pathway.NamePathKey;
import stroom.pathways.shared.pathway.PathNode;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Whether a trace can be turned into a day record without a model to measure it against.
 *
 * <p>Step 4 needs that: a trace whose work ran before the window must change no model and still say
 * what its day contained. The cheapest way there is to read the trace the ordinary way against an
 * empty node and throw away the tree it builds — but only if doing so gives the same account of the
 * trace as reading it against the model that has been learning from traces like it all week. If it
 * does not, a day written from both kinds of trace disagrees with itself.
 *
 * <p>These compare the two, which is a question about reading a trace rather than about anything
 * step 4 builds, so they stand on their own.
 */
class TestRecordingWithoutAModel {

    private static final IgnoredAttributes NO_IGNORED = new IgnoredAttributes(List.of());
    private static final String OPERATION = "GET /orders";
    private static final long BASE = 1_700_000_000_000L;

    @Test
    void aTraceGivesTheSameAccountWithOrWithoutAModel() {
        // A model that has already seen this work several times over, so its nodes hold constraints
        // and its children are in an order it settled long before this trace arrived.
        PathNode model = null;
        for (int i = 0; i < 5; i++) {
            model = apply(trace(i, "Validate", "Commit"), model);
        }

        final NodeMutatorImpl against = mutator();
        against.process(trace(9, "Validate", "Commit"), key(), model, quiet(), doc());

        final NodeMutatorImpl alone = mutator();
        alone.process(trace(9, "Validate", "Commit"), key(), null, quiet(), doc());

        assertThat(valuesOf(against))
                .as("the fixture only says something if there is an account to compare")
                .hasSize(3)
                .allSatisfy((path, constraints) -> assertThat(constraints).hasSizeGreaterThan(3));
        assertThat(valuesOf(alone))
                .as("the same nodes, named the same way, given the same values")
                .isEqualTo(valuesOf(against));
    }

    @Test
    void theSpansFoldedIntoEachNodeAreCountedTheSame() {
        PathNode model = null;
        for (int i = 0; i < 3; i++) {
            model = apply(trace(i, "Validate", "Validate", "Commit"), model);
        }

        final NodeMutatorImpl against = mutator();
        against.process(trace(9, "Validate", "Validate", "Commit"), key(), model, quiet(), doc());
        final NodeMutatorImpl alone = mutator();
        alone.process(trace(9, "Validate", "Validate", "Commit"), key(), null, quiet(), doc());

        assertThat(spansOf(against))
                .as("the fixture only says something if the node really was reached twice")
                .containsEntry(List.of(OPERATION, "Validate"), 2L);
        assertThat(spansOf(alone)).isEqualTo(spansOf(against));
    }

    @Test
    void theSamePathIsFoundEitherWay() {
        PathNode model = null;
        for (int i = 0; i < 3; i++) {
            model = apply(trace(i, "Validate", "Commit"), model);
        }
        // A trace taking a different turning from the one the model knows best, so the shape it
        // reports is not simply whatever the model already held.
        final NodeMutatorImpl against = mutator();
        against.process(trace(9, "Commit"), key(), model, quiet(), doc());
        final NodeMutatorImpl alone = mutator();
        alone.process(trace(9, "Commit"), key(), null, quiet(), doc());

        assertThat(shapeOf(against))
                .as("a shape with something in it, not an empty one")
                .contains("NODE");
        assertThat(shapeOf(alone)).isEqualTo(shapeOf(against));
    }

    @Test
    void aConstraintTheModelHasLearntIsOptionalIsNotRecordedThatWayAlone() {
        // Optionality is learnt across traces: a constraint goes optional when a later trace's span
        // arrives without the attribute. The model carries that; one trace on its own cannot know it.
        PathNode model = null;
        model = apply(withTag(0, "tenant", "acme"), model);
        model = apply(withTag(1, null, null), model);

        final NodeMutatorImpl against = mutator();
        against.process(withTag(2, "tenant", "acme"), key(), model, quiet(), doc());
        final NodeMutatorImpl alone = mutator();
        alone.process(withTag(2, "tenant", "acme"), key(), null, quiet(), doc());

        assertThat(valuesOf(against).get(List.of(OPERATION)))
                .as("the fixture only says something if the model did learn it is optional")
                .containsEntry("attribute.tenant", "acme optional=true");
        assertThat(valuesOf(alone).get(List.of(OPERATION)))
                .as("what one trace alone can say: it saw the attribute, so it reads as required")
                .containsEntry("attribute.tenant", "acme optional=false");
    }

    // -----------------------------------------------------------------------
    // Fixture
    // -----------------------------------------------------------------------
    // One root span, with or without an extra attribute beyond the one every span here carries.
    private static Trace withTag(final int index, final String name, final String value) {
        final long start = (BASE + (index * 1000L)) * 1_000_000L;
        final List<KeyValue> attributes = new ArrayList<>();
        attributes.add(new KeyValue("http.route", AnyValue.stringValue("/orders")));
        if (name != null) {
            attributes.add(new KeyValue(name, AnyValue.stringValue(value)));
        }
        final Span root = Span.builder()
                .name(OPERATION)
                .spanId("r" + index)
                .parentSpanId("")
                .kind(SpanKind.SPAN_KIND_INTERNAL)
                .startTimeUnixNano(Long.toString(start))
                .endTimeUnixNano(Long.toString(start + NanoDuration.ofMillis(20L).getNanos()))
                .attributes(attributes)
                .build();
        return new Trace(String.format("%032d", index), Map.of("", List.of(root)));
    }


    // The values each node was given, by the path that reaches it. The stand-ins are left out: each
    // reading picks its own and the merge renames them, so two readings differing there says
    // nothing.
    private static Map<List<String>, Map<String, String>> valuesOf(final NodeMutatorImpl mutator) {
        final Map<List<String>, Map<String, String>> values = new LinkedHashMap<>();
        mutator.getObservations().nodes().forEach((path, node) -> {
            final Map<String, String> constraints = new LinkedHashMap<>();
            node.constraints().forEach((name, seen) ->
                    constraints.put(name, seen.value() + " optional=" + seen.optional()));
            values.put(path, constraints);
        });
        return values;
    }

    private static Map<List<String>, Long> spansOf(final NodeMutatorImpl mutator) {
        final Map<List<String>, Long> spans = new LinkedHashMap<>();
        mutator.getObservations().nodes().forEach((path, node) -> spans.put(path, node.timesUsed()));
        return spans;
    }

    // The shape as a readable tree, since a PathShape names its nodes by the stand-in the record
    // chose for each of them.
    private static String shapeOf(final NodeMutatorImpl mutator) {
        return render(mutator.getPathShape());
    }

    private static String render(final PathShape shape) {
        final StringBuilder sb = new StringBuilder(shape.kind().toString());
        shape.steps().forEach(child -> sb.append(" [").append(render(child)).append("]"));
        return sb.toString();
    }

    private static PathNode apply(final Trace trace, final PathNode current) {
        return mutator().process(trace, key(), current, quiet(), doc());
    }

    private static NodeMutatorImpl mutator() {
        return new NodeMutatorImpl(new CanonicalSpanOrder(doc()),
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

    // One root running the named children in order, each a little slower than the last trace's, so
    // the model really has been learning rather than seeing the same numbers over and over.
    private static Trace trace(final int index, final String... childNames) {
        final long start = (BASE + (index * 1000L)) * 1_000_000L;
        final Span root = span(OPERATION, "r" + index, "", start, 20 + index);
        final List<Span> children = new ArrayList<>();
        for (int i = 0; i < childNames.length; i++) {
            children.add(span(childNames[i], "c" + index + "_" + i, "r" + index,
                    start + NanoDuration.ofMillis(i + 1L).getNanos(), 1 + index));
        }
        final Map<String, List<Span>> byParent = new HashMap<>();
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
}
