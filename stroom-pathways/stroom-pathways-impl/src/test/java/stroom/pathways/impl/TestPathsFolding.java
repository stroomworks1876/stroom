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
import stroom.pathways.shared.otel.trace.NanoTime;
import stroom.pathways.shared.otel.trace.Span;
import stroom.pathways.shared.otel.trace.SpanKind;
import stroom.pathways.shared.otel.trace.Trace;
import stroom.pathways.shared.pathway.NamePathKey;
import stroom.pathways.shared.pathway.PathNode;
import stroom.pathways.shared.pathway.PathUse;
import stroom.pathways.shared.pathway.Paths;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Folding two accounts of the paths taken, and numbering the result the same way every time.
 *
 * <p>A day record carries its own account of the paths taken that day. Two of them have to combine
 * when a day is written again, and the result has to come out byte for byte the same whichever
 * account was read first - otherwise loading the same traces twice changes what is stored, and
 * everything that rests on a day record being a union falls over.
 */
class TestPathsFolding {

    private static final IgnoredAttributes NO_IGNORED = new IgnoredAttributes(List.of());
    private static final String OPERATION = "ProcessorTaskCreatorJob.run";
    private static final String SOURCE = "a-traces-doc-uuid";

    @Test
    void foldingTwoAccountsOfOneWalkAddsTheCounts() {
        final Paths morning = account("trace-a", 0, "Ping", "Commit");
        final Paths afternoon = account("trace-b", 60, "Ping", "Commit");

        final Paths both = PathRecorder.merge(morning, afternoon);

        assertThat(both.getPaths()).hasSize(1);
        final PathUse row = both.getPaths().getFirst();
        assertThat(row.getTimesUsed()).isEqualTo(2L);
        assertThat(row.getCreatedByTraceId())
                .as("the trace that really took it first keeps the credit")
                .isEqualTo("trace-a");
        assertThat(row.getFirstUsedTime()).isEqualTo(time(0));
        assertThat(row.getLastUsedTime()).isEqualTo(time(60));
    }

    @Test
    void twoDifferentWalksStayTwoRows() {
        final Paths one = account("trace-a", 0, "Ping", "Commit");
        final Paths other = account("trace-b", 60, "Ping");

        assertThat(PathRecorder.merge(one, other).getPaths()).hasSize(2);
    }

    @Test
    void theOlderTraceKeepsTheCreditWhicheverWayRound() {
        final Paths early = account("trace-a", 0, "Ping");
        final Paths late = account("trace-b", 60, "Ping");

        assertThat(PathRecorder.merge(early, late).getPaths().getFirst().getCreatedByTraceId())
                .isEqualTo("trace-a");
        assertThat(PathRecorder.merge(late, early).getPaths().getFirst().getCreatedByTraceId())
                .as("reading the accounts the other way round cannot change who was first")
                .isEqualTo("trace-a");
    }

    @Test
    void twoTracesAtTheSameInstantAreSettledByTheirIds() {
        final Paths one = account("trace-b", 0, "Ping");
        final Paths other = account("trace-a", 0, "Ping");

        assertThat(PathRecorder.merge(one, other).getPaths().getFirst().getCreatedByTraceId())
                .isEqualTo("trace-a");
        assertThat(PathRecorder.merge(other, one).getPaths().getFirst().getCreatedByTraceId())
                .isEqualTo("trace-a");
    }

    @Test
    void foldingEitherWayRoundGivesTheSameAccount() {
        // The property the whole no-dedup-marker decision rests on. The two accounts number their
        // nodes in the order each met them, so without renumbering these come out different.
        final Paths one = PathRecorder.merge(
                account("trace-a", 0, "Ping", "Commit"),
                account("trace-b", 30, "Prepare", "Drain"));
        final Paths other = PathRecorder.merge(
                account("trace-b", 30, "Prepare", "Drain"),
                account("trace-a", 0, "Ping", "Commit"));

        assertThat(one.getNodes())
                .as("the fixture only says something if the two really do number them differently")
                .isNotEqualTo(other.getNodes());
        assertThat(PathRecorder.canonical(one)).isEqualTo(PathRecorder.canonical(other));
    }

    @Test
    void foldingADayIntoItselfMovesOnlyTheCounts() {
        final Paths day = PathRecorder.merge(
                account("trace-a", 0, "Ping", "Commit"),
                account("trace-b", 30, "Prepare"));

        final Paths once = PathRecorder.canonical(day);
        final Paths twice = PathRecorder.canonical(PathRecorder.merge(day, day));

        assertThat(twice.getNodes()).isEqualTo(once.getNodes());
        assertThat(twice.getSteps()).isEqualTo(once.getSteps());
        assertThat(twice.getPaths())
                .as("the counts are the one thing that does move, which is written down as a "
                    + "deliberate exemption")
                .isNotEqualTo(once.getPaths());
    }

    @Test
    void canonicalIsAlreadyCanonical() {
        final Paths day = PathRecorder.merge(
                account("trace-b", 30, "Prepare", "Drain"),
                account("trace-a", 0, "Ping", "Commit"));

        final Paths once = PathRecorder.canonical(day);
        assertThat(PathRecorder.canonical(once)).isEqualTo(once);
    }

    @Test
    void anAccountWithNothingInItFoldsAway() {
        final Paths day = account("trace-a", 0, "Ping");

        assertThat(PathRecorder.merge(Paths.empty(), day)).isEqualTo(day);
        assertThat(PathRecorder.merge(day, Paths.empty())).isEqualTo(day);
    }

    // -----------------------------------------------------------------------
    // Fixture
    // -----------------------------------------------------------------------

    // The model these walks are recorded against. One per test, because a path names its nodes by the
    // uuid the model minted, so two accounts only have anything to fold if they came from one model.
    private PathNode model;

    // One trace's walk, recorded the way the processor records it.
    private Paths account(final String traceId, final int seconds, final String... children) {
        final PathwaysDoc doc = doc();
        final NodeMutatorImpl mutator = new NodeMutatorImpl(
                new CanonicalSpanOrder(doc.getTemporalOrderingTolerance()), NO_IGNORED,
                new IgnoredSpans(List.of()));
        model = mutator.process(walk(children), new NamePathKey(OPERATION), model,
                (severity, message) -> {
                }, doc);
        return PathRecorder.add(Paths.empty(), mutator.getPathShape(), time(seconds), time(seconds),
                traceId, SOURCE);
    }

    private static NanoTime time(final int seconds) {
        return new NanoTime(1_700_000_000L + seconds, 0);
    }

    private static Trace walk(final String... childNames) {
        final Span root = span(OPERATION, "r0", "", 0);
        final List<Span> children = new ArrayList<>(childNames.length);
        for (int i = 0; i < childNames.length; i++) {
            children.add(span(childNames[i], "c" + i, "r0", (i * 3) + 1));
        }
        final Map<String, List<Span>> byParent = new HashMap<>();
        byParent.put("", List.of(root));
        if (!children.isEmpty()) {
            byParent.put("r0", children);
        }
        return new Trace("0".repeat(32), byParent);
    }

    private static Span span(final String name,
                             final String spanId,
                             final String parentSpanId,
                             final int startSeconds) {
        final long start = (1_700_000_000L + startSeconds) * 1_000_000_000L;
        return Span.builder()
                .name(name)
                .spanId(spanId)
                .parentSpanId(parentSpanId)
                .kind(SpanKind.SPAN_KIND_INTERNAL)
                .startTimeUnixNano(Long.toString(start))
                .endTimeUnixNano(Long.toString(start + 1_000_000L))
                .build();
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
