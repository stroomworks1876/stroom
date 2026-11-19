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

package stroom.pathways.shared;

import stroom.pathways.shared.otel.trace.NanoTime;
import stroom.pathways.shared.pathway.NodeChange;
import stroom.pathways.shared.pathway.NodeUsage;
import stroom.pathways.shared.pathway.PathNode;
import stroom.pathways.shared.pathway.Pathway;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * One pathway as it stood at one moment, with everything a drawing of it needs.
 *
 * <p>Everything, so that drawing it is one call. How large each node is drawn and what colour it is
 * are worked out here rather than by whoever draws it: the changes they are counted from are filed by
 * the day their work ran, so a date never promises an unbroken run of them, and counting down to a
 * moment would mean reading every change at or below it — which for a history that has been archived
 * means opening every week of it.
 */
@JsonInclude(Include.NON_NULL)
public class PathwayAsAt {

    @JsonProperty
    private final Pathway pathway;
    /**
     * How much each node had changed by this moment and when it last changed, by node uuid. Keyed the
     * way the drawing asks for them, so nothing has to be matched up after it arrives.
     */
    @JsonProperty
    private final Map<String, NodeChange> changes;
    @JsonProperty
    private final Map<String, NodeUsage> usage;
    /**
     * The moment being shown, or null where that is now.
     */
    @JsonProperty
    private final NanoTime asAt;
    /**
     * What node sizes are drawn in proportion to: the most any node has ever been changed, taken over
     * the whole of the pathway's life rather than over the part being shown.
     *
     * <p>Over the part being shown, whatever was largest at that moment would always be drawn at full
     * size, so winding back would rescale the picture rather than shrink it.
     */
    @JsonProperty
    private final long changeCeiling;
    @JsonProperty
    private final long usageCeiling;
    @JsonProperty
    private final boolean folded;

    /**
     * The tree the drawing lays its nodes out from, or null where the model on show is its own.
     *
     * <p>A folded model holds only the nodes that day was given, so every day would be laid out from
     * a different tree and the drawing would be built afresh at every step rather than moved. This
     * covers every node any described day has, so each of them draws the same set and a step is a
     * change of appearance rather than a new picture.
     */
    @JsonProperty
    private final PathNode layout;

    @JsonCreator
    public PathwayAsAt(@JsonProperty("pathway") final Pathway pathway,
                       @JsonProperty("changes") final Map<String, NodeChange> changes,
                       @JsonProperty("usage") final Map<String, NodeUsage> usage,
                       @JsonProperty("asAt") final NanoTime asAt,
                       @JsonProperty("changeCeiling") final Long changeCeiling,
                       @JsonProperty("usageCeiling") final Long usageCeiling,
                       @JsonProperty("folded") final Boolean folded,
                       @JsonProperty("layout") final PathNode layout) {
        this.folded = Objects.requireNonNullElse(folded, false);
        this.layout = layout;
        this.pathway = pathway;
        this.changes = changes == null
                ? Collections.emptyMap()
                : changes;
        this.usage = usage == null
                ? Collections.emptyMap()
                : usage;
        this.asAt = asAt;
        this.changeCeiling = Objects.requireNonNullElse(changeCeiling, 0L);
        this.usageCeiling = Objects.requireNonNullElse(usageCeiling, 0L);
    }

    /**
     * Whether this model was folded out of day records rather than wound back from the change
     * history.
     *
     * <p>The two are different answers to the same question and only one is available for a given
     * moment. A wound-back model is exact and carries everything the learner ever took in. A folded
     * one holds only what was seen over the window it covers, so it has no count of changes, and
     * its nodes are named by a stand-in worked out from the path of span names that reaches each of
     * them rather than by the uuid the learner minted.
     *
     * <p>That last part is why a drawing has to ask: it is laid out from the model as it stands and
     * looks each node up by uuid, which finds nothing in a folded model.
     */
    public boolean isFolded() {
        return folded;
    }

    public Pathway getPathway() {
        return pathway;
    }

    public Map<String, NodeChange> getChanges() {
        return changes;
    }

    public Map<String, NodeUsage> getUsage() {
        return usage;
    }

    public NanoTime getAsAt() {
        return asAt;
    }

    public long getChangeCeiling() {
        return changeCeiling;
    }

    public PathNode getLayout() {
        return layout;
    }

    public long getUsageCeiling() {
        return usageCeiling;
    }
}
