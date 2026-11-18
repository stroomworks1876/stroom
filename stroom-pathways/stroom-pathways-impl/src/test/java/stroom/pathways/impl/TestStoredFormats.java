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
import stroom.bytebuffer.impl6.ByteBufferPoolOutput;
import stroom.pathways.shared.PathwaySummary;
import stroom.pathways.shared.otel.trace.NanoTime;
import stroom.pathways.shared.pathway.Constraint;
import stroom.pathways.shared.pathway.NamePathKey;
import stroom.pathways.shared.pathway.NanoTimeRange;
import stroom.pathways.shared.pathway.PathNode;
import stroom.pathways.shared.pathway.PathStep;
import stroom.pathways.shared.pathway.PathUse;
import stroom.pathways.shared.pathway.Paths;
import stroom.pathways.shared.pathway.Pathway;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What a stored pathway does when it was laid out by a different build.
 *
 * <p>Neither the pathway nor the mutation layout carries field names — they are read back by position
 * — so a row from a build that ordered the fields differently cannot be told from a current one by its
 * contents. Each carries a byte saying which shape follows, and reading refuses anything else rather
 * than returning whatever the bytes happened to mean.
 */
class TestStoredFormats {

    private static final ByteBufferFactoryImpl BYTE_BUFFER_FACTORY = new ByteBufferFactoryImpl();

    @Test
    void aPathwayWrittenInAnotherFormatIsRefused() {
        final ByteBuffer stored = write();

        assertThat(new PathwaySerde(BYTE_BUFFER_FACTORY).readPathway(stored.duplicate()).getName())
                .as("the fixture only says something if it reads back as itself first")
                .isEqualTo("GET /orders");

        final ByteBuffer foreign = stored.duplicate();
        foreign.put(0, (byte) (foreign.get(0) + 1));

        assertThatThrownBy(() -> new PathwaySerde(BYTE_BUFFER_FACTORY).readPathway(foreign))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("pathways table has to be cleared");
    }

    @Test
    void aSummaryRefusesItToo() {
        // The list reads only the header of each pathway, so it has to check the same byte — otherwise
        // a page of rows comes back with times and sizes read out of the wrong bytes.
        final ByteBuffer foreign = write();
        foreign.put(0, (byte) (foreign.get(0) + 1));

        assertThatThrownBy(() -> new PathwaySerde(BYTE_BUFFER_FACTORY).readSummary(foreign))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("pathways table has to be cleared");
    }

    @Test
    void aStoredPathwayKeepsItsCounts() {
        final Pathway read = new PathwaySerde(BYTE_BUFFER_FACTORY).readPathway(write());

        assertThat(read.getTimesUsed()).isEqualTo(4207);
        assertThat(read.getTimesUpdated()).isEqualTo(31);
    }

    @Test
    void aSummaryReadsTheCountsToo() {
        // The list stops reading before the model, so the counts have to sit ahead of it. Were they
        // written after, a row would show whatever the first bytes of the path key came to.
        final PathwaySummary summary = new PathwaySerde(BYTE_BUFFER_FACTORY).readSummary(write());

        assertThat(summary.getTimesUsed()).isEqualTo(4207);
        assertThat(summary.getTimesUpdated()).isEqualTo(31);
        assertThat(summary.getLastUsedTime()).isEqualTo(NanoTime.ofMillis(3));
    }

    @Test
    void aStoredPathwayKeepsItsPaths() {
        final Pathway read = new PathwaySerde(BYTE_BUFFER_FACTORY).readPathway(write());
        final PathUse path = read.getPaths().getPaths().getFirst();

        assertThat(read.getPaths().getNodes()).containsExactly("node-a", "node-b");
        assertThat(path.getRoot()).isEqualTo(2);
        assertThat(path.getTimesUsed()).isEqualTo(19);
        assertThat(path.getFirstUsedTime()).isEqualTo(NanoTime.ofMillis(4));
        assertThat(path.getLastUsedTime()).isEqualTo(NanoTime.ofMillis(5));
        assertThat(path.getCreatedByTraceId()).isEqualTo("0a0b0c0d");
        assertThat(path.getLastTraceTime())
                .as("when the newest trace to take it ran, which is what decides whether it is still"
                    + " being taken")
                .isEqualTo(NanoTime.ofMillis(6));
    }

    @Test
    void aStoredPathwayKeepsTheShapesItsPathsAreBuiltFrom() {
        final Pathway read = new PathwaySerde(BYTE_BUFFER_FACTORY).readPathway(write());

        assertThat(read.getPaths().getSteps()).containsExactly(
                new PathStep(1, List.of()),
                new PathStep(PathStep.UNFINISHED, List.of(0)),
                new PathStep(0, List.of(0, 1)));
        assertThat(read.getPaths().getPaths().getFirst().getRoot()).isEqualTo(2);
        // The unfinished marker is negative and the length written is not, so it is the one value
        // that could be lost in the round trip.
        assertThat(read.getPaths().getSteps().get(1).isUnfinished()).isTrue();
    }

    @Test
    void aPathwayWithNoPathsYetReadsBackWithNone() {
        final Pathway pathway = Pathway.builder()
                .name("GET /orders")
                .createTime(NanoTime.ofMillis(1))
                .updateTime(NanoTime.ofMillis(2))
                .lastUsedTime(NanoTime.ofMillis(3))
                .pathKey(new NamePathKey("GET /orders"))
                .root(new PathNode("GET /orders"))
                .build();

        final ByteBuffer[] written = new ByteBuffer[1];
        new PathwaySerde(BYTE_BUFFER_FACTORY).writePathway(pathway, 0, buffer -> {
            final ByteBuffer copy = ByteBuffer.allocateDirect(buffer.remaining());
            copy.put(buffer).flip();
            written[0] = copy;
        });

        final Pathway read = new PathwaySerde(BYTE_BUFFER_FACTORY).readPathway(written[0]);
        assertThat(read.getPaths().getPaths()).isEmpty();
        assertThat(read.getPaths().getNodes()).isEmpty();
        assertThat(read.getPaths().getSteps()).isEmpty();
    }

    @Test
    void aSummaryCountsTheNodesAndPathsWithoutReadingTheModel() {
        final PathwaySummary summary = new PathwaySerde(BYTE_BUFFER_FACTORY).readSummary(write());

        // Three nodes: the root and the two children built below. One path.
        assertThat(summary.getNodes()).isEqualTo(3);
        assertThat(summary.getPaths()).isEqualTo(1);
    }

    @Test
    void theCountsTheListReadsDoNotShiftTheModelBehindThem() {
        // The counts sit ahead of the model in an unversioned positional layout, so a full read that
        // did not take them off the front would find the path key where a node count is.
        final Pathway read = new PathwaySerde(BYTE_BUFFER_FACTORY).readPathway(write());

        assertThat(read.getName()).isEqualTo("GET /orders");
        assertThat(read.getRoot().getName()).isEqualTo("GET /orders");
        assertThat(read.getRoot().getChildren()).hasSize(2);
        assertThat(read.getPaths().getPaths()).hasSize(1);
    }

    @Test
    void aDurationWithOneEndLeftOpenIsStoredWithThatEndAbsent() {
        final Pathway read = new PathwaySerde(BYTE_BUFFER_FACTORY).readPathway(write());
        final Map<String, Constraint> constraints = read.getRoot().getConstraints();

        assertThat(constraints.get("duration").getValue())
                .as("both ends learnt, as before")
                .isEqualTo(new NanoTimeRange(NanoTime.ofMillis(1), NanoTime.ofMillis(9)));
        assertThat(constraints.get("bottom.only").getValue())
                .as("the top was left open, so only the bottom was written")
                .isEqualTo(new NanoTimeRange(NanoTime.ofMillis(1), null));
        assertThat(constraints.get("top.only").getValue())
                .isEqualTo(new NanoTimeRange(null, NanoTime.ofMillis(9)));
    }

    @Test
    void aNodeKeepsWhenItLastChanged() {
        final PathNode changed = new PathNode("Commit").copy()
                .timesChanged(3)
                .lastChangedTime(NanoTime.ofMillis(5))
                .build();
        final Pathway pathway = Pathway.builder()
                .name("GET /orders")
                .createTime(NanoTime.ofMillis(1))
                .updateTime(NanoTime.ofMillis(2))
                .lastUsedTime(NanoTime.ofMillis(3))
                .pathKey(new NamePathKey("GET /orders"))
                .root(new PathNode("GET /orders").copy()
                        .children(List.of(changed))
                        .retiredTime(NanoTime.ofMillis(7))
                        .build())
                .build();

        final Pathway read = new PathwaySerde(BYTE_BUFFER_FACTORY).readPathway(copied(pathway));

        assertThat(read.getRoot().getLastChangedTime())
                .as("a node nothing has changed has no time")
                .isNull();
        assertThat(read.getRoot().getRetiredTime())
                .as("the field after it is still where it should be")
                .isEqualTo(NanoTime.ofMillis(7));
        assertThat(read.getRoot().getChildren().getFirst().getLastChangedTime())
                .isEqualTo(NanoTime.ofMillis(5));
    }

    @Test
    void aPathwayStoredWithoutLastChangedTimesIsStillRead() {
        // Format 1 is format 2 less each node's last-changed time, written here by hand the way that
        // format laid a pathway out. Refusing it would leave every pathway already learnt unreadable
        // until the table was cleared.
        final ByteBuffer stored;
        try (final ByteBufferPoolOutput output = new ByteBufferPoolOutput(BYTE_BUFFER_FACTORY, 128, -1)) {
            output.writeByte(1);
            output.writeString("GET /orders");
            writeTime(output, NanoTime.ofMillis(1));
            writeTime(output, NanoTime.ofMillis(2));
            writeTime(output, NanoTime.ofMillis(3));
            output.writeLong(4207);
            output.writeLong(31);
            output.writeVarInt(2, true);
            output.writeVarInt(0, true);
            output.writeByte(0);
            output.writeString("GET /orders");
            // A node's children are written inside it, ahead of its own figures: the root's name and
            // path, then the whole child, then the root's figures.
            writeNodeHead(output, "root", "GET /orders", List.of("GET /orders"), 1);
            writeNodeHead(output, "child", "Commit", List.of("GET /orders", "Commit"), 0);
            writeNodeFiguresWithoutLastChanged(output);
            writeNodeFiguresWithoutLastChanged(output);
            // No paths: nodes, steps, uses and sources all empty.
            output.writeInt(0);
            output.writeInt(0);
            output.writeInt(0);
            output.writeInt(0);
            final ByteBuffer written = output.getByteBuffer().flip();
            stored = ByteBuffer.allocateDirect(written.remaining());
            stored.put(written).flip();
        }

        final Pathway read = new PathwaySerde(BYTE_BUFFER_FACTORY).readPathway(stored);

        assertThat(read.getName()).isEqualTo("GET /orders");
        assertThat(read.getTimesUsed()).isEqualTo(4207);
        assertThat(read.getRoot().getUuid()).isEqualTo("root");
        assertThat(read.getRoot().getLastChangedTime()).isNull();
        assertThat(read.getRoot().getRetiredTime())
                .as("read from where format 1 put it rather than shifted by a field it does not have")
                .isEqualTo(NanoTime.ofMillis(8));
        assertThat(read.getRoot().getChildren()).hasSize(1);
        assertThat(read.getRoot().getChildren().getFirst().getTimesChanged()).isEqualTo(6);
        assertThat(read.getRoot().getChildren().getFirst().getLastChangedTime()).isNull();
        assertThat(new PathwaySerde(BYTE_BUFFER_FACTORY).readSummary(stored.rewind()).getTimesUsed())
                .as("the list reads it as well")
                .isEqualTo(4207);
    }

    private static void writeNodeHead(final ByteBufferPoolOutput output,
                                      final String uuid,
                                      final String name,
                                      final List<String> path,
                                      final int children) {
        output.writeString(uuid);
        output.writeString(name);
        output.writeInt(path.size());
        path.forEach(output::writeString);
        output.writeInt(children);
    }

    // No constraints, used five times, changed six, last used at 4ms and retired at 8ms.
    private static void writeNodeFiguresWithoutLastChanged(final ByteBufferPoolOutput output) {
        output.writeInt(0);
        output.writeLong(5);
        output.writeLong(6);
        output.writeBoolean(true);
        writeTime(output, NanoTime.ofMillis(4));
        output.writeBoolean(true);
        writeTime(output, NanoTime.ofMillis(8));
    }

    private static void writeTime(final ByteBufferPoolOutput output, final NanoTime time) {
        output.writeLong(time.getSeconds());
        output.writeInt(time.getNanos());
    }

    private static ByteBuffer copied(final Pathway pathway) {
        final ByteBuffer[] written = new ByteBuffer[1];
        new PathwaySerde(BYTE_BUFFER_FACTORY).writePathway(pathway, 0, buffer -> {
            final ByteBuffer copy = ByteBuffer.allocateDirect(buffer.remaining());
            copy.put(buffer).flip();
            written[0] = copy;
        });
        return written[0];
    }

    private static ByteBuffer write() {
        final Pathway pathway = Pathway.builder()
                .name("GET /orders")
                .createTime(NanoTime.ofMillis(1))
                .updateTime(NanoTime.ofMillis(2))
                .lastUsedTime(NanoTime.ofMillis(3))
                .timesUsed(4207)
                .timesUpdated(31)
                .pathKey(new NamePathKey("GET /orders"))
                .root(new PathNode(
                        UUID.randomUUID().toString(),
                        "GET /orders",
                        List.of("GET /orders"),
                        List.of(new PathNode("Prepare statement"), new PathNode("Commit")),
                        Map.of(
                                "duration", new Constraint("duration",
                                        new NanoTimeRange(NanoTime.ofMillis(1), NanoTime.ofMillis(9)), false),
                                "bottom.only", new Constraint("bottom.only",
                                        new NanoTimeRange(NanoTime.ofMillis(1), null), false),
                                "top.only", new Constraint("top.only",
                                        new NanoTimeRange(null, NanoTime.ofMillis(9)), false))))
                .paths(new Paths(
                        List.of("node-a", "node-b"),
                        List.of(new PathStep(1, List.of()),
                                new PathStep(PathStep.UNFINISHED, List.of(0)),
                                new PathStep(0, List.of(0, 1))),
                        List.of(new PathUse(
                                2,
                                19,
                                NanoTime.ofMillis(4),
                                NanoTime.ofMillis(5),
                                "0a0b0c0d",
                                NanoTime.ofMillis(3),
                                NanoTime.ofMillis(6),
                                0)),
                        List.of("a-traces-doc-uuid")))
                .build();

        final ByteBuffer[] written = new ByteBuffer[1];
        new PathwaySerde(BYTE_BUFFER_FACTORY).writePathway(pathway, 0, buffer -> {
            final ByteBuffer copy = ByteBuffer.allocateDirect(buffer.remaining());
            copy.put(buffer).flip();
            written[0] = copy;
        });
        return written[0];
    }
}
