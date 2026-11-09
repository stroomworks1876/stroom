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
import stroom.pathways.shared.pathway.Pathway;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Collections;
import java.util.Map;

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

    @JsonCreator
    public PathwayAsAt(@JsonProperty("pathway") final Pathway pathway,
                       @JsonProperty("changes") final Map<String, NodeChange> changes,
                       @JsonProperty("usage") final Map<String, NodeUsage> usage,
                       @JsonProperty("asAt") final NanoTime asAt,
                       @JsonProperty("changeCeiling") final long changeCeiling,
                       @JsonProperty("usageCeiling") final long usageCeiling) {
        this.pathway = pathway;
        this.changes = changes == null
                ? Collections.emptyMap()
                : changes;
        this.usage = usage == null
                ? Collections.emptyMap()
                : usage;
        this.asAt = asAt;
        this.changeCeiling = changeCeiling;
        this.usageCeiling = usageCeiling;
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

    public long getUsageCeiling() {
        return usageCeiling;
    }
}
