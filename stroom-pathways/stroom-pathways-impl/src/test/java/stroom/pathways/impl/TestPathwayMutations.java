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
import stroom.pathways.impl.PathwaySerde.StoredChange;
import stroom.pathways.shared.PathwaysDoc;
import stroom.pathways.shared.otel.trace.AnyValue;
import stroom.pathways.shared.otel.trace.KeyValue;
import stroom.pathways.shared.otel.trace.NanoDuration;
import stroom.pathways.shared.otel.trace.NanoTime;
import stroom.pathways.shared.otel.trace.Span;
import stroom.pathways.shared.otel.trace.SpanKind;
import stroom.pathways.shared.otel.trace.Trace;
import stroom.pathways.shared.pathway.AnyTypeValue;
import stroom.pathways.shared.pathway.Constraint;
import stroom.pathways.shared.pathway.ConstraintValue;
import stroom.pathways.shared.pathway.MutationType;
import stroom.pathways.shared.pathway.NamePathKey;
import stroom.pathways.shared.pathway.NanoTimeRange;
import stroom.pathways.shared.pathway.NanoTimeValue;
import stroom.pathways.shared.pathway.PathNode;
import stroom.pathways.shared.pathway.PathwayMutation;
import stroom.pathways.shared.pathway.Regex;
import stroom.pathways.shared.pathway.StringSet;
import stroom.pathways.shared.pathway.StringValue;
import stroom.util.shared.Severity;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The record of what each trace did to the model.
 *
 * <p>Enough is kept to replay the change rather than only to report it: where in the model it landed,
 * what the constraint held before and what it holds now. The trace and span are on every record so a
 * replay can say what caused each step.
 */
class TestPathwayMutations {

    private static final ByteBufferFactoryImpl BYTE_BUFFER_FACTORY = new ByteBufferFactoryImpl();
    private static final String OPERATION = "ProcessorTaskCreatorJob.run";
    private static final String PING = "Ping";
    private static final String TRACE_ID = "0a0b0c0d0e0f00010203040506070809";
    private static final long BASE = 1_700_000_000_000_000_000L;
    private static final String METHOD = "attribute.http.method";

    // Just the values a node was given, which is what these are about. A day record also carries
    // what is optional, how busy the node was and the trace behind each bound.
    private static Map<String, ConstraintValue> valuesOf(final DayRecord.ObservedNode node) {
        final Map<String, ConstraintValue> values = new LinkedHashMap<>();
        node.constraints().forEach((name, seen) -> values.put(name, seen.value()));
        return values;
    }

    @Test
    void whatEachConstraintWasGivenIsKeptBesideTheChangeItCaused() {
        final NodeMutatorImpl mutator = mutator();
        mutator.process(childAt(0, 3), key(), null, quiet(), doc());

        assertThat(valuesOf(mutator.getObservations().nodes().values().iterator().next()))
                .as("the values this trace gave, not the envelope the model ended up with")
                .containsEntry("duration", new NanoTimeValue(millis(20)))
                .containsEntry("kind", new StringValue("SPAN_KIND_INTERNAL"));
    }

    @Test
    void aNodeReachedTwiceInOneTraceFoldsItsValuesTogether() {
        // The same child twice under one root, three milliseconds apart, so the node is folded in twice
        // and what it was given spans both.
        final Span root = span(OPERATION, "r0", "", 0, 20, "GET");
        final Map<String, List<Span>> byParent = new HashMap<>();
        byParent.put("", List.of(root));
        byParent.put("r0", List.of(span(PING, "c0", "r0", 1, 1, null),
                span(PING, "c1", "r0", 4, 1, null)));

        final NodeMutatorImpl mutator = mutator();
        final PathNode node = mutator.process(new Trace(TRACE_ID, byParent), key(), null, quiet(), doc());

        final List<String> child = node.getChildren().getFirst().getNodePath();
        assertThat(valuesOf(mutator.getObservations().nodes().get(child)))
                .containsEntry("sinceParentStart", new NanoTimeRange(millis(1), millis(4)));
    }

    @Test
    void aChildSaysHowLongAfterItsParentItStarted() {
        final PathNode root = mutator().process(childAt(0, 3), key(), null, quiet(), doc());

        assertThat(sinceParentStart(root)).isEqualTo(new NanoTimeValue(millis(3)));
        assertThat(root.getConstraints())
                .as("the root is enclosed by nothing, so there is nothing to measure it against")
                .doesNotContainKey("sinceParentStart");
    }

    @Test
    void aChildThatStartedBeforeItsParentIsClampedToNothing() {
        // Two machines whose clocks disagree: the child reports a start three milliseconds before the
        // parent that contains it.
        final PathNode root = mutator().process(childAt(5, 2), key(), null, quiet(), doc());

        assertThat(sinceParentStart(root))
                .as("a gap cannot be negative, so it is recorded as none")
                .isEqualTo(new NanoTimeValue(NanoTime.ZERO));
    }

    @Test
    void theGapWidensLikeAnyOtherRange() {
        PathNode root = mutator().process(childAt(0, 3), key(), null, quiet(), doc());
        root = mutator().process(childAt(0, 40), key(), root, quiet(), doc());

        assertThat(sinceParentStart(root)).isEqualTo(new NanoTimeRange(millis(3), millis(40)));
    }

    @Test
    void oneEndOfTheGapCanBeLeftOpen() {
        final List<String> ignored = List.of("sinceParentStart.max");
        PathNode root = mutator(ignored).process(childAt(0, 3), key(), null, quiet(), doc());
        root = mutator(ignored).process(childAt(0, 40), key(), root, quiet(), doc());

        assertThat(sinceParentStart(root))
                .as("the same naming that works for duration works here, with no code of its own")
                .isEqualTo(new NanoTimeRange(millis(3), null));
    }

    @Test
    void theFirstTraceRecordsThePathwayAndItsSteps() {
        final NodeMutatorImpl mutator = mutator();
        mutator.process(trace("GET", PING), key(), null, quiet(), doc());

        assertThat(mutator.getMutations()).extracting(PathwayMutation::getType)
                .as("the pathway itself, then everything learnt under it")
                .startsWith(MutationType.PATHWAY_ADDED)
                .contains(MutationType.NODE_ADDED, MutationType.CONSTRAINT_ADDED);

        assertThat(mutator.getMutations().getFirst().getNodePath())
                .as("named like any node, so a replay can put the root back")
                .containsExactly(OPERATION);

        assertThat(mutator.getMutations()).allSatisfy(mutation -> {
            assertThat(mutation.getTraceId()).isEqualTo(TRACE_ID);
            assertThat(mutation.getSpanId()).isNotBlank();
            assertThat(mutation.getTime()).isNotNull();
        });
    }

    @Test
    void aWideningCarriesWhatItWasAndWhatItBecame() {
        final PathNode root = mutator().process(trace("GET", PING), key(), null, quiet(), doc());

        final NodeMutatorImpl mutator = mutator();
        mutator.process(trace("POST", PING), key(), root, quiet(), doc());

        final PathwayMutation widened = mutator.getMutations().stream()
                .filter(m -> "attribute.http.method".equals(m.getConstraint()))
                .findFirst()
                .orElseThrow();

        assertThat(widened.getType()).isEqualTo(MutationType.CONSTRAINT_SET_EXPANDED);
        assertThat(widened.getOldValue()).isEqualTo(new StringValue("GET"));
        assertThat(widened.getNewValue()).isEqualTo(new StringSet(Set.of("GET", "POST")));
        assertThat(widened.getNodePath())
                .as("the root node is where this attribute lives")
                .containsExactly(OPERATION);
    }

    @Test
    void aNewStepNamesWhereItWasAdded() {
        final PathNode root = mutator().process(trace("GET", PING), key(), null, quiet(), doc());

        final NodeMutatorImpl mutator = mutator();
        mutator.process(trace("GET", PING, "Commit"), key(), root, quiet(), doc());

        final PathwayMutation added = mutator.getMutations().stream()
                .filter(m -> MutationType.NODE_ADDED.equals(m.getType()))
                .findFirst()
                .orElseThrow();

        assertThat(added.getNodePath())
                .as("named from the root down, so a replay knows what to draw")
                .containsExactly(OPERATION, "Commit");
    }

    @Test
    void aRangeRecordsWhichEndOfItMoved() {
        // One duration seen, so the constraint holds a single time. A shorter one pushes the bottom of
        // a range down; a longer one then pushes the top up.
        PathNode root = mutator().process(trace("GET", 20, PING), key(), null, quiet(), doc());

        final NodeMutatorImpl shorter = mutator();
        root = shorter.process(trace("GET", 5, PING), key(), root, quiet(), doc());
        assertThat(durationChange(shorter)).isEqualTo(MutationType.CONSTRAINT_MIN_EXPANDED);

        final NodeMutatorImpl longer = mutator();
        longer.process(trace("GET", 90, PING), key(), root, quiet(), doc());
        assertThat(durationChange(longer)).isEqualTo(MutationType.CONSTRAINT_MAX_EXPANDED);
    }

    @Test
    void anEndGivenUpOnIsNotAnEndThatMoved() {
        // Learnt as an ordinary range first, then told to stop learning the bottom of it.
        PathNode root = mutator().process(trace("GET", 20, PING), key(), null, quiet(), doc());
        root = mutator().process(trace("GET", 5, PING), key(), root, quiet(), doc());

        final NodeMutatorImpl opened = mutator(List.of("duration.min"));
        root = opened.process(trace("GET", 30, PING), key(), root, quiet(), doc());
        assertThat(durationChange(opened))
                .as("the bottom was let go of, not pushed down")
                .isEqualTo(MutationType.CONSTRAINT_BOUND_OPENED);

        final NodeMutatorImpl slower = mutator(List.of("duration.min"));
        slower.process(trace("GET", 90, PING), key(), root, quiet(), doc());
        assertThat(durationChange(slower))
                .as("the end still being learnt moves as it always did")
                .isEqualTo(MutationType.CONSTRAINT_MAX_EXPANDED);
    }

    @Test
    void anEndNeverLearntIsNotAnEndThatMoved() {
        // Told not to learn the bottom before there is a range at all, so the single value seen so far
        // is the top rather than an end that moved.
        final PathNode root = mutator().process(trace("GET", 20, PING), key(), null, quiet(), doc());

        final NodeMutatorImpl opened = mutator(List.of("duration.min"));
        opened.process(trace("GET", 30, PING), key(), root, quiet(), doc());
        assertThat(durationChange(opened)).isEqualTo(MutationType.CONSTRAINT_BOUND_OPENED);
    }

    @Test
    void aValueJoiningASetSaysSo() {
        final PathNode root = mutator().process(trace("GET", 20, PING), key(), null, quiet(), doc());

        final NodeMutatorImpl mutator = mutator();
        mutator.process(trace("POST", 20, PING), key(), root, quiet(), doc());

        assertThat(methodChange(mutator))
                .as("one value became a set of two")
                .isEqualTo(MutationType.CONSTRAINT_SET_EXPANDED);
    }

    @Test
    void tooManyValuesToListSaysTheConstraintGaveUp() {
        // The first value is held on its own and the next nine fill the set to MAX_SET_SIZE, so the
        // eleventh value seen is the one that takes it past listing them.
        PathNode root = null;
        for (int i = 0; i < 10; i++) {
            root = mutator().process(trace("method-" + i, 20, PING), key(), root, quiet(), doc());
        }

        final NodeMutatorImpl mutator = mutator();
        mutator.process(trace("method-10", 20, PING), key(), root, quiet(), doc());

        assertThat(methodChange(mutator))
                .as("past the point of listing them, so it stopped saying anything")
                .isEqualTo(MutationType.CONSTRAINT_GENERALISED);
    }

    @Test
    void anAttributeNamedInTheConfigurationSaysItWasIgnored() {
        final IgnoredAttributes ignored = new IgnoredAttributes(List.of("http.method"));
        final NodeMutatorImpl mutator = new NodeMutatorImpl(
                new CanonicalSpanOrder(doc()), ignored,
                new IgnoredSpans(List.of()));
        mutator.process(trace("GET", 20, PING), key(), null, quiet(), doc());

        assertThat(methodChange(mutator))
                .as("configured away, which is not the same as a type it could not handle")
                .isEqualTo(MutationType.CONSTRAINT_IGNORED);
    }

    @Test
    void aTraceThatTeachesNothingRecordsNothing() {
        final PathNode root = mutator().process(trace("GET", PING), key(), null, quiet(), doc());

        final NodeMutatorImpl mutator = mutator();
        mutator.process(trace("GET", PING), key(), root, quiet(), doc());

        assertThat(mutator.getMutations()).isEmpty();
        assertThat(mutator.isChanged()).isFalse();
    }

    @Test
    void aMutationSurvivesTheRoundTrip() {
        final NodeMutatorImpl mutator = mutator();
        mutator.process(trace("GET", PING), key(), null, quiet(), doc());
        final PathwaySerde serde = new PathwaySerde(BYTE_BUFFER_FACTORY);

        for (final PathwayMutation mutation : mutator.getMutations()) {
            final StoredChange written = stored(mutation);
            final StoredChange[] read = new StoredChange[1];
            serde.writeMutation(written, buffer -> {
                final ByteBuffer copy = ByteBuffer.allocateDirect(buffer.remaining());
                copy.put(buffer).flip();
                read[0] = serde.readMutation(copy);
            });
            assertThat(read[0]).isEqualTo(written);
        }
    }

    // A change as it is stored: the node it was made against and the trace that made it stand as
    // numbers here, and what those numbers mean is the log's business rather than the serde's.
    private static StoredChange stored(final PathwayMutation mutation) {
        return new StoredChange(7L,
                5L,
                3L,
                mutation.getSpanId(),
                mutation.getConstraint(),
                mutation.getType(),
                mutation.isOptional(),
                mutation.getOldValue(),
                mutation.getNewValue());
    }

    private static MutationType methodChange(final NodeMutatorImpl mutator) {
        return mutator.getMutations().stream()
                .filter(m -> "attribute.http.method".equals(m.getConstraint()))
                .findFirst()
                .orElseThrow()
                .getType();
    }

    private static MutationType durationChange(final NodeMutatorImpl mutator) {
        return mutator.getMutations().stream()
                .filter(m -> "duration".equals(m.getConstraint()))
                .findFirst()
                .orElseThrow()
                .getType();
    }

    @Test
    void aRowFromAnEarlierFormatIsRefusedRatherThanMisread() {
        final NodeMutatorImpl mutator = mutator();
        mutator.process(trace("GET", PING), key(), null, quiet(), doc());
        final PathwaySerde serde = new PathwaySerde(BYTE_BUFFER_FACTORY);

        final ByteBuffer[] written = new ByteBuffer[1];
        serde.writeMutation(stored(mutator.getMutations().getFirst()), buffer -> {
            final ByteBuffer copy = ByteBuffer.allocateDirect(buffer.remaining());
            copy.put(buffer).flip();
            written[0] = copy;
        });

        // The layout carries no field names, so a row laid out differently would otherwise be read as
        // whatever its bytes happened to mean.
        written[0].put(0, (byte) (written[0].get(0) + 1));

        assertThatThrownBy(() -> serde.readMutation(written[0]))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("mutations table has to be cleared");
    }

    @Test
    void aValueMatchingTheRegexLeavesItAlone() {
        final List<String> messages = new ArrayList<>();
        final NodeMutatorImpl mutator = mutator();
        final PathNode root = mutator.process(trace("GET", PING), key(), withMethodPattern("G.T"),
                recording(messages), doc());

        assertThat(root.getConstraints().get(METHOD).getValue()).isEqualTo(new Regex("G.T"));
        assertThat(mutator.getMutations()).isEmpty();
        assertThat(messages).isEmpty();
    }

    @Test
    void aValueMissingTheRegexChangesItToAnyTypeWhereMutationIsAllowed() {
        final List<String> messages = new ArrayList<>();
        final PathNode root = mutator().process(trace("POST", PING), key(), withMethodPattern("G.T"),
                recording(messages), doc());

        assertThat(root.getConstraints().get(METHOD).getValue()).isEqualTo(new AnyTypeValue());
        assertThat(messages).singleElement().asString()
                .startsWith("WARNING ")
                .contains("Changing to any type: ")
                .endsWith(" POST");
    }

    @Test
    void aValueMissingTheRegexKeepsItWhereMutationIsNotAllowed() {
        final List<String> messages = new ArrayList<>();
        final PathwaysDoc fixed = doc().copy().allowPathwayMutation(false).build();
        final PathNode root = mutator().process(trace("POST", PING), key(), withMethodPattern("G.T"),
                recording(messages), fixed);

        assertThat(root.getConstraints().get(METHOD).getValue()).isEqualTo(new Regex("G.T"));
        assertThat(messages).singleElement().asString()
                .startsWith("ERROR ")
                .contains("Unexpected string: ")
                .endsWith(" POST");
    }

    @Test
    void aRegexHasToMatchTheWholeValue() {
        final PathNode partial = mutator().process(trace("forget", PING), key(), withMethodPattern("get"),
                quiet(), doc());
        assertThat(partial.getConstraints().get(METHOD).getValue())
                .as("found inside the value is not a match")
                .isEqualTo(new AnyTypeValue());

        final PathNode whole = mutator().process(trace("forget", PING), key(), withMethodPattern(".*get.*"),
                quiet(), doc());
        assertThat(whole.getConstraints().get(METHOD).getValue()).isEqualTo(new Regex(".*get.*"));
    }

    @Test
    void anyValueMatchesAcrossLineBreaks() {
        final List<String> messages = new ArrayList<>();
        final PathNode root = mutator().process(trace("GET\nPOST", PING), key(), withMethodPattern(".*"),
                recording(messages), doc());

        assertThat(root.getConstraints().get(METHOD).getValue()).isEqualTo(new Regex(".*"));
        assertThat(messages).isEmpty();
    }

    @Test
    void aNewNodeTakesItsFirstValuesWhereConstraintCreationIsOff() {
        final PathNode root = mutator().process(trace("GET", PING), key(), null, quiet(), doc());
        final PathwaysDoc noCreation = doc().copy().allowConstraintCreation(false).build();

        final PathNode grown = mutator().process(trace("GET", PING, "Commit"), key(), root, quiet(),
                noCreation);

        final Map<String, Constraint> commit = child(grown, "Commit").getConstraints();
        assertThat(commit).containsKeys("duration", "sinceParentStart", "kind", "occurrences");
        assertThat(commit.values())
                .as("all of them required, as on any new node")
                .noneMatch(Constraint::isOptional);
    }

    @Test
    void aKnownNodeGetsNoNewConstraintWhereConstraintCreationIsOff() {
        final PathNode root = mutator().process(trace(null, PING), key(), null, quiet(), doc());
        final PathwaysDoc noCreation = doc().copy().allowConstraintCreation(false).build();
        final List<String> messages = new ArrayList<>();

        final PathNode after = mutator().process(trace("GET", PING), key(), root, recording(messages),
                noCreation);

        assertThat(after.getConstraints()).doesNotContainKey(METHOD);
        assertThat(messages).singleElement().asString()
                .startsWith("ERROR ")
                .contains("Constraint not found: ");
    }

    @Test
    void aNodeBroughtBackRelearnsWhereConstraintCreationIsOff() {
        final PathwaysDoc noCreation = doc().copy().allowConstraintCreation(false).build();

        final NodeMutatorImpl mutator = mutator();
        final PathNode root = mutator.process(childAt(0, 3), key(), withPingRetired(), quiet(), noCreation);

        assertThat(sinceParentStart(root))
                .as("learnt afresh from this trace rather than widened from what it held")
                .isEqualTo(new NanoTimeValue(millis(3)));
        assertThat(mutator.getMutations())
                .filteredOn(m -> List.of(OPERATION, PING).equals(m.getNodePath()))
                .extracting(PathwayMutation::getType)
                .contains(MutationType.NODE_RESTORED, MutationType.CONSTRAINT_ADDED);
    }

    @Test
    void aNewPathwayTakesItsFirstValuesWhereMutationIsOff() {
        final PathwaysDoc fixed = doc().copy().allowPathwayMutation(false).build();

        final NodeMutatorImpl mutator = mutator();
        final PathNode root = mutator.process(trace("GET"), key(), null, quiet(), fixed);

        assertThat(root.getConstraints()).containsKeys("duration", "kind", METHOD);
        assertThat(valuesOf(mutator.getObservations().nodes().get(List.of(OPERATION))))
                .as("and the day notes what it learnt")
                .containsEntry(METHOD, new StringValue("GET"));
    }

    @Test
    void aNodeBroughtBackRelearnsWhereMutationIsOff() {
        final PathwaysDoc fixed = doc().copy().allowPathwayMutation(false).build();

        final PathNode root = mutator().process(childAt(0, 3), key(), withPingRetired(), quiet(), fixed);

        assertThat(sinceParentStart(root)).isEqualTo(new NanoTimeValue(millis(3)));
    }

    @Test
    void aKnownConstraintIsNotWidenedWhereMutationIsOff() {
        final PathNode root = mutator().process(trace("GET", PING), key(), null, quiet(), doc());
        final PathwaysDoc fixed = doc().copy().allowPathwayMutation(false).build();
        final List<String> messages = new ArrayList<>();

        final PathNode after = mutator().process(trace("POST", PING), key(), root, recording(messages),
                fixed);

        assertThat(after.getConstraints().get(METHOD).getValue()).isEqualTo(new StringValue("GET"));
        assertThat(messages).singleElement().asString()
                .startsWith("ERROR ")
                .contains("Unexpected string ");
    }

    private static PathNode withPingRetired() {
        final PathNode root = mutator().process(trace("GET", PING), key(), null, quiet(), doc());
        final List<PathNode> children = root.getChildren().stream()
                .map(child -> child.copy().retiredTime(millis(1)).build())
                .toList();
        return root.copy().children(children).build();
    }

    private static PathNode child(final PathNode root, final String name) {
        return root.getChildren().stream()
                .filter(node -> name.equals(node.getName()))
                .findFirst()
                .orElseThrow();
    }

    // A pathway learnt from one trace, with its method constraint swapped for the pattern given.
    private static PathNode withMethodPattern(final String regex) {
        final PathNode root = mutator().process(trace("GET", PING), key(), null, quiet(), doc());
        final Map<String, Constraint> constraints = new HashMap<>(root.getConstraints());
        constraints.put(METHOD, new Constraint(METHOD, new Regex(regex), false));
        return root.copy().constraints(constraints).build();
    }

    // Keeps the warnings and errors logged, each as its severity followed by the message.
    private static MessageReceiver recording(final List<String> messages) {
        return (severity, message) -> {
            if (Severity.WARNING.equals(severity) || Severity.ERROR.equals(severity)) {
                messages.add(severity.name() + " " + message.get());
            }
        };
    }

    private static NodeMutatorImpl mutator() {
        return mutator(List.of());
    }

    private static NodeMutatorImpl mutator(final List<String> ignoredAttributes) {
        return new NodeMutatorImpl(
                new CanonicalSpanOrder(doc()),
                new IgnoredAttributes(ignoredAttributes),
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

    // One child under one root, each placed where the test wants it, so a test can say what the gap
    // between the two starts is.
    private static Trace childAt(final int rootMillis, final int childMillis) {
        final Span root = span(OPERATION, "r0", "", rootMillis, 20, "GET");
        final Span child = span(PING, "c0", "r0", childMillis, 1, null);
        final Map<String, List<Span>> byParent = new HashMap<>();
        byParent.put("", List.of(root));
        byParent.put("r0", List.of(child));
        return new Trace(TRACE_ID, byParent);
    }

    private static NanoTime millis(final int millis) {
        return NanoTime.ofNanos(NanoDuration.ofMillis(millis).getNanos());
    }

    private static ConstraintValue sinceParentStart(final PathNode root) {
        return root.getChildren().getFirst().getConstraints().get("sinceParentStart").getValue();
    }

    private static Trace trace(final String method, final String... childNames) {
        return trace(method, 1, childNames);
    }

    private static Trace trace(final String method, final int rootMillis, final String... childNames) {
        final Span root = span(OPERATION, "r0", "", 0, rootMillis, method);
        final List<Span> children = new ArrayList<>(childNames.length);
        for (int i = 0; i < childNames.length; i++) {
            children.add(span(childNames[i], "c" + i, "r0", i + 1, 1, null));
        }

        final Map<String, List<Span>> byParent = new HashMap<>();
        byParent.put("", List.of(root));
        if (!children.isEmpty()) {
            byParent.put("r0", children);
        }
        return new Trace(TRACE_ID, byParent);
    }

    private static Span span(final String name,
                             final String spanId,
                             final String parentSpanId,
                             final int startMillis,
                             final int durationMillis,
                             final String method) {
        final long start = BASE + NanoDuration.ofMillis(startMillis).getNanos();
        final Span.Builder builder = Span.builder()
                .name(name)
                .spanId(spanId)
                .parentSpanId(parentSpanId)
                .kind(SpanKind.SPAN_KIND_INTERNAL)
                .startTimeUnixNano(Long.toString(start))
                .endTimeUnixNano(Long.toString(start + NanoDuration.ofMillis(durationMillis).getNanos()));
        if (method != null) {
            builder.attributes(List.of(KeyValue.builder()
                    .key("http.method")
                    .value(new AnyValue(method, null, null, null, null, null, null))
                    .build()));
        }
        return builder.build();
    }
}
