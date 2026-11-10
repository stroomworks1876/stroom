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
import stroom.pathways.impl.DayRecord.ObservedConstraint;
import stroom.pathways.impl.DayRecord.ObservedNode;
import stroom.pathways.shared.otel.trace.NanoTime;
import stroom.pathways.shared.pathway.ConstraintValue;
import stroom.pathways.shared.pathway.IntegerRange;
import stroom.pathways.shared.pathway.IntegerValue;
import stroom.pathways.shared.pathway.Paths;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a day record holds, and the rule that every part of it but one is a union.
 *
 * <p>A day record is what a model is rebuilt from for a date the change history does not reach, and
 * old history can be loaded without a marker per trace only because giving a day the same traces
 * twice leaves it as it was. Every merge rule here has to keep that true, which means none of them
 * may depend on which of two accounts was read first.
 */
class TestDayRecords {

    private static final ByteBufferFactoryImpl FACTORY = new ByteBufferFactoryImpl();
    private static final List<String> ROOT = List.of("GET /orders");
    private static final List<String> CHILD = List.of("GET /orders", "Commit");

    @Test
    void aRecordComesBackAsItWentIn() {
        final Map<List<String>, ObservedNode> nodes = new LinkedHashMap<>();
        nodes.put(ROOT, new ObservedNode("uuid-root", 7L,
                constraints("size", new IntegerValue(3), false, "trace-a", time(10), "span-a")));
        nodes.put(CHILD, new ObservedNode("uuid-child", 2L,
                constraints("size", new IntegerValue(9), true, "trace-b", time(20), "span-b")));
        final DayRecord written = new DayRecord(nodes, Paths.empty());

        final DayRecord read = roundTrip(written);

        assertThat(read.nodes().keySet())
                .as("nodes are named by the path that reaches them, so a record means something "
                    + "without the model that minted the uuids")
                .containsExactly(ROOT, CHILD);
        final ObservedNode child = read.nodes().get(CHILD);
        assertThat(child.timesUsed()).isEqualTo(2L);
        assertThat(child.nodeUuid())
                .as("what the model called it, so the paths taken resolve against this record alone")
                .isEqualTo("uuid-child");
        final ObservedConstraint seen = child.constraints().get("size");
        assertThat(seen.value()).isEqualTo(new IntegerValue(9));
        assertThat(seen.optional()).isTrue();
        assertThat(seen.traceId()).isEqualTo("trace-b");
        assertThat(seen.ranAt())
                .as("when it ran, which is how two accounts tell whose bound came first")
                .isEqualTo(time(20));
        assertThat(seen.spanId()).isEqualTo("span-b");
    }

    @Test
    void aWiderValueTakesTheCreditWithIt() {
        final DayRecord narrow = day("trace-a", 10, new IntegerValue(3));
        final DayRecord wide = day("trace-b", 20, new IntegerRange(1, 9));

        assertThat(creditedIn(DayRecord.merge(narrow, wide)))
                .as("the trace that justifies the value as it now stands")
                .isEqualTo("trace-b");
        assertThat(creditedIn(DayRecord.merge(wide, narrow)))
                .as("and reading the two the other way round cannot change that")
                .isEqualTo("trace-b");
    }

    @Test
    void whereNeitherAccountJustifiesTheValueAloneTheEarlierTraceTakesIt() {
        // Two bounds that together cover more than either did, so neither trace justifies the value
        // on its own. The one that ran first is named, which is why the record lists when each ran.
        final DayRecord earlier = day("trace-z", 10, new IntegerValue(1));
        final DayRecord later = day("trace-a", 20, new IntegerValue(9));

        assertThat(creditedIn(DayRecord.merge(earlier, later)))
                .as("by the clock, not by the id - an id is a random string and decides nothing")
                .isEqualTo("trace-z");
        assertThat(creditedIn(DayRecord.merge(later, earlier))).isEqualTo("trace-z");
    }

    @Test
    void anythingEverSeenMissingIsOptionalFromThenOn() {
        final DayRecord present = day("trace-a", 10, new IntegerValue(3));
        final DayRecord absent = new DayRecord(Map.of(ROOT, new ObservedNode("uuid-root", 1L,
                constraints("size", new IntegerValue(3), true, "trace-b", time(20), "span-b"))),
                Paths.empty());

        assertThat(DayRecord.merge(present, absent).nodes().get(ROOT).constraints().get("size")
                .optional()).isTrue();
    }

    @Test
    void givingADayTheSameValuesTwiceLeavesThemAsTheyWere() {
        final DayRecord once = day("trace-a", 10, new IntegerValue(3));

        final DayRecord twice = DayRecord.merge(once, once);

        assertThat(twice.nodes().get(ROOT).constraints())
                .isEqualTo(once.nodes().get(ROOT).constraints());
        assertThat(twice.nodes().get(ROOT).timesUsed())
                .as("the one figure that does move, which is written down as a deliberate exemption")
                .isEqualTo(2L);
    }

    // One trace's account of one node's one constraint.
    private static DayRecord day(final String traceId, final int seconds, final ConstraintValue value) {
        return new DayRecord(Map.of(ROOT, new ObservedNode("uuid-root", 1L,
                constraints("size", value, false, traceId, time(seconds), "span-" + traceId))),
                Paths.empty());
    }

    private static String creditedIn(final DayRecord record) {
        return record.nodes().get(ROOT).constraints().get("size").traceId();
    }

    private static NanoTime time(final int seconds) {
        return new NanoTime(1_700_000_000L + seconds, 0);
    }

    private static Map<String, ObservedConstraint> constraints(final String name,
                                                               final ConstraintValue value,
                                                               final boolean optional,
                                                               final String traceId,
                                                               final NanoTime ranAt,
                                                               final String spanId) {
        return new LinkedHashMap<>(Map.of(name,
                new ObservedConstraint(value, optional, traceId, ranAt, spanId)));
    }

    private static DayRecord roundTrip(final DayRecord record) {
        final PathwaySerde serde = new PathwaySerde(FACTORY);
        final ByteBuffer[] held = new ByteBuffer[1];
        serde.writeObservedValues(record, value -> {
            final ByteBuffer copy = ByteBuffer.allocateDirect(value.remaining());
            copy.put(value.duplicate()).flip();
            held[0] = copy;
        });
        return serde.readObservedValues(held[0]);
    }
}
