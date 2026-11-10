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

import stroom.pathways.shared.otel.trace.NanoTime;
import stroom.pathways.shared.pathway.ConstraintValue;
import stroom.pathways.shared.pathway.Paths;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What one model was given on one day.
 *
 * <p>Nodes are named by the path of span names that reaches them, not by the uuid the model minted,
 * so a day can be rebuilt into a model from the records alone. Each one says what the model called
 * it that day as well, because the walks taken name their nodes the way the model does - so the
 * record resolves its own walks without reaching for the model.
 *
 * <p>A bound names its trace outright. A model has hundreds of constraints and a handful of traces
 * set most of them, so the same id would be written out hundreds of times - which is why the serde
 * writes the traces it finds once each and has the bounds point at them. That is a detail of how the
 * record is stored: nothing here knows about it, and a trace no bound names is never written.
 *
 * <p><b>Every field is a union except the two counts.</b> They are the spans folded into each node,
 * and the number of traces that took each walk in {@link #paths()}. Give the same day the same traces
 * twice and nothing else moves. That is what lets old history be loaded without a marker per trace saying
 * it already has been, and every merge rule here has to keep it true - which means not depending on
 * which of two accounts was read first.
 */
record DayRecord(Map<List<String>, ObservedNode> nodes, Paths paths) {

    static DayRecord empty() {
        return new DayRecord(Map.of(), Paths.empty());
    }

    /** Two accounts of one day, folded into one. */
    static DayRecord merge(final DayRecord first, final DayRecord second) {
        final Map<List<String>, ObservedNode> nodes = new LinkedHashMap<>(first.nodes());
        second.nodes().forEach((path, arrived) -> nodes.merge(path, arrived, ObservedNode::and));
        return new DayRecord(nodes, PathRecorder.merge(first.paths(), second.paths()));
    }

    /**
     * One node's account of a day.
     *
     * @param nodeUuid  what the model called this node that day. Held so that {@link #paths()}, which
     *                  names its nodes the way the learnt model does, resolves against this record's
     *                  own nodes and needs nothing outside it
     * @param timesUsed spans folded into this node that day, counted the way the learnt model counts
     *                  them. The one figure here that is a sum rather than a union, so it drifts if
     *                  a day is loaded twice - a deliberate exemption, taken because the alternatives
     *                  were a marker per trace or not showing the figure
     */
    record ObservedNode(String nodeUuid, long timesUsed, Map<String, ObservedConstraint> constraints) {

        ObservedNode and(final ObservedNode arrived) {
            final Map<String, ObservedConstraint> all = new LinkedHashMap<>(constraints);
            arrived.constraints().forEach((name, seen) ->
                    all.merge(name, seen, ObservedConstraint::and));
            // The uuid is whichever account has one. Both come from the same model, so they agree; a
            // node retired and made again under the same path is the only way they differ, and then
            // either names a node this record itself describes.
            return new ObservedNode(nodeUuid == null
                    ? arrived.nodeUuid()
                    : nodeUuid,
                    timesUsed + arrived.timesUsed(),
                    all);
        }
    }

    /**
     * What one constraint was given that day, and what justifies it.
     *
     * @param optional whether the thing this constrains was ever absent where it was looked for
     * @param traceId  the trace behind the value as it now stands, which is what the Changes table
     *                 shows for a day the change history does not reach
     * @param ranAt    when that trace ran, so that two accounts being folded can tell which of two
     *                 traces really saw a bound first. Deciding that by trace id instead would be
     *                 picking at random, because an id is a random string
     * @param spanId   the span within that trace
     */
    record ObservedConstraint(ConstraintValue value,
                              boolean optional,
                              String traceId,
                              NanoTime ranAt,
                              String spanId) {

        /**
         * Two accounts of one constraint, folded.
         *
         * <p>The value widens to cover both. The credit goes to whichever account already held the
         * widened value; where that does not settle it - both held it, or the union is wider than
         * either on its own - it goes to whichever trace ran first.
         */
        ObservedConstraint and(final ObservedConstraint arrived) {
            final ConstraintValue widened = ObservedValues.add(value, arrived.value());
            final boolean heldIt = widened.equals(value);
            final boolean arrivedHeldIt = widened.equals(arrived.value());
            final ObservedConstraint credited;
            if (heldIt && !arrivedHeldIt) {
                credited = this;
            } else if (arrivedHeldIt && !heldIt) {
                credited = arrived;
            } else {
                credited = earlier(this, arrived);
            }
            return new ObservedConstraint(widened,
                    optional || arrived.optional(),
                    credited.traceId(),
                    credited.ranAt(),
                    credited.spanId());
        }

        // Which of two accounts names the trace that ran first. An account naming no trace loses,
        // because naming one is more use than naming nothing; otherwise the same rule everything
        // else folding this record uses.
        private static ObservedConstraint earlier(final ObservedConstraint first,
                                                  final ObservedConstraint second) {
            if (first.traceId() == null || second.traceId() == null) {
                return first.traceId() == null
                        ? second
                        : first;
            }
            final int byTime = Times.compare(first.ranAt(), second.ranAt());
            if (byTime != 0) {
                return byTime < 0
                        ? first
                        : second;
            }
            return Times.compare(first.traceId(), second.traceId()) <= 0
                    ? first
                    : second;
        }
    }
}
