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

package stroom.pathways.shared.pathway;

import stroom.pathways.shared.otel.trace.NanoTime;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Objects;

/**
 * One thing that moved between a day's model and the day before's.
 *
 * <p>What the Changes table lists for a day the change history does not reach. The learner made no
 * changes those days, so there is nothing it did to show; this is worked out afterwards by folding
 * both days and comparing them.
 *
 * <p><b>It is not a {@link PathwayMutation} and cannot stand in for one.</b> A change has a place in
 * a numbered history and the model can be wound back to it. A difference has a place in a day and
 * nowhere else: two values a day apart, with nothing in between to step through. Hence no sequence
 * here, and hence a type of its own rather than a flag on the other - so that nothing which winds
 * the model back, steps through it or counts its changes can be handed one of these by mistake.
 *
 * <p>Several of these can share a day, and a day where nothing moved has none.
 */
@JsonInclude(Include.NON_NULL)
public class PathwayDifference implements HistoryRow {

    @JsonProperty
    private final int day;
    @JsonProperty
    private final List<String> nodePath;
    @JsonProperty
    private final String nodeUuid;
    @JsonProperty
    private final String constraint;
    @JsonProperty
    private final DifferenceType type;
    @JsonProperty
    private final ConstraintValue oldValue;
    @JsonProperty
    private final ConstraintValue newValue;
    @JsonProperty
    private final String traceId;
    @JsonProperty
    private final String spanId;
    @JsonProperty
    private final NanoTime ranAt;

    /**
     * @param day       the day this was found on, counted from the epoch. The whole of its place in
     *                  time: a difference is two days compared, so there is no finer moment to give
     * @param nodeUuid  what the folded model this came from calls the node, so a row can pick the
     *                  node out on the drawing beside it. Worked out from the node's path rather than
     *                  minted by the learner, so it means nothing outside that one answer
     * @param traceId   the trace behind the value as the day ended holding it. Not the trace that
     *                  caused the move: a day record says what justified a value, not what changed it,
     *                  and over a day those are usually but not always the same trace
     */
    @JsonCreator
    public PathwayDifference(@JsonProperty("day") final int day,
                             @JsonProperty("nodePath") final List<String> nodePath,
                             @JsonProperty("nodeUuid") final String nodeUuid,
                             @JsonProperty("constraint") final String constraint,
                             @JsonProperty("type") final DifferenceType type,
                             @JsonProperty("oldValue") final ConstraintValue oldValue,
                             @JsonProperty("newValue") final ConstraintValue newValue,
                             @JsonProperty("traceId") final String traceId,
                             @JsonProperty("spanId") final String spanId,
                             @JsonProperty("ranAt") final NanoTime ranAt) {
        this.day = day;
        this.nodePath = nodePath;
        this.nodeUuid = nodeUuid;
        this.constraint = constraint;
        this.type = type;
        this.oldValue = oldValue;
        this.newValue = newValue;
        this.traceId = traceId;
        this.spanId = spanId;
        this.ranAt = ranAt;
    }

    public int getDay() {
        return day;
    }

    @Override
    public List<String> getNodePath() {
        return nodePath;
    }

    @Override
    public String getNodeUuid() {
        return nodeUuid;
    }

    @Override
    public String getConstraint() {
        return constraint;
    }

    @Override
    public String getTypeDisplayValue() {
        return type == null
                ? null
                : type.getDisplayValue();
    }

    public DifferenceType getType() {
        return type;
    }

    @Override
    public ConstraintValue getOldValue() {
        return oldValue;
    }

    @Override
    public ConstraintValue getNewValue() {
        return newValue;
    }

    @Override
    public String getTraceId() {
        return traceId;
    }

    @Override
    public String getSpanId() {
        return spanId;
    }

    public NanoTime getRanAt() {
        return ranAt;
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final PathwayDifference that = (PathwayDifference) o;
        return day == that.day
               && Objects.equals(nodePath, that.nodePath)
               && Objects.equals(constraint, that.constraint)
               && type == that.type
               && Objects.equals(oldValue, that.oldValue)
               && Objects.equals(newValue, that.newValue);
    }

    @Override
    public int hashCode() {
        return Objects.hash(day, nodePath, constraint, type, oldValue, newValue);
    }

    @Override
    public String toString() {
        return "day " + day + " " + String.join("/", nodePath)
               + (constraint == null
                ? ""
                : " " + constraint)
               + " " + type;
    }
}
