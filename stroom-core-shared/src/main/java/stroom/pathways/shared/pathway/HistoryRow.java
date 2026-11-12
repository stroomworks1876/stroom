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

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.List;

/**
 * One row of a pathway's history, of either kind.
 *
 * <p>Two things can be listed against a model and they are not the same thing. A
 * {@link PathwayMutation} is something the learner did: it has a place in a numbered history and the
 * model can be wound back to it. A {@link PathwayDifference} is how one day's model differed from
 * the day before's, worked out by comparing them, for days the change history does not reach - it
 * has a place in a day and nowhere else.
 *
 * <p>They travel in one list because the table sorts on its columns, and sorting by node or
 * constraint puts the two kinds among each other. Only sorting by time keeps them apart, and then
 * only because every difference is older than the first change.
 *
 * <p>What is here is what the table draws and sorts on. What is deliberately not here is the
 * sequence: that is how a change is stepped to, a difference has none, and anything that winds the
 * model back takes a {@link PathwayMutation} so it cannot be handed one of the others by mistake.
 */
@JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        property = "kind"
)
@JsonSubTypes({
        @JsonSubTypes.Type(value = PathwayMutation.class, name = "change"),
        @JsonSubTypes.Type(value = PathwayDifference.class, name = "difference")
})
public interface HistoryRow {

    /** The span names that reach the node this is about. */
    List<String> getNodePath();

    /** What the model this came from calls that node, for picking it out on the drawing. */
    String getNodeUuid();

    /** The constraint this is about, or null where the row is about the node itself. */
    String getConstraint();

    /** What happened, in the words of whichever kind of row this is. */
    String getTypeDisplayValue();

    ConstraintValue getOldValue();

    ConstraintValue getNewValue();

    /** The trace behind this row, which the two kinds mean slightly differently. */
    String getTraceId();

    String getSpanId();
}
