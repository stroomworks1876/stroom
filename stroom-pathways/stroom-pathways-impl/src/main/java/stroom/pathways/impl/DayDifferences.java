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
import stroom.pathways.shared.pathway.AnyTypeValue;
import stroom.pathways.shared.pathway.ConstraintValue;
import stroom.pathways.shared.pathway.DifferenceType;
import stroom.pathways.shared.pathway.PathwayDifference;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * What moved between one day's model and the day before's.
 *
 * <p>This is what the Changes table lists for a day the change history does not reach. The learner
 * made no changes on such a day, so there is nothing it did to show, and the two days are compared
 * instead.
 *
 * <p>Both days arrive as the folded records {@link PathwayFold} is built from, so this needs no
 * store and nothing is written: the answer is worked out when it is asked for. That was measured
 * before it was built - ninety days of folds on the largest model on the dev instance is about
 * seventy milliseconds - which is why there is no stored difference list anywhere.
 *
 * <p>Nodes and their constraints only. Paths are not compared, and nothing here has a notion of a
 * node being retired: a folded node is in the window or it is not, and the narrowing's retirement is
 * something it does to a learnt model.
 */
final class DayDifferences {

    private DayDifferences() {
    }

    /**
     * Everything that moved, the day's own nodes first and then any the day before had and it does
     * not.
     *
     * @param before the day before's model, as the days in the window ending on it folded together
     * @param after  the day's own, folded the same way
     * @param day    the day being described, counted from the epoch
     */
    static List<PathwayDifference> between(final DayRecord before,
                                           final DayRecord after,
                                           final int day) {
        final List<PathwayDifference> differences = new ArrayList<>();
        after.nodes().forEach((path, now) -> {
            final DayRecord.ObservedNode was = before.nodes().get(path);
            if (was == null) {
                differences.add(nodeArrived(day, path, now));
            } else {
                constraints(day, path, was, now, differences);
            }
        });
        before.nodes().forEach((path, was) -> {
            // Not a retirement. A day's model is the fold of a window, so a node leaves it as soon as
            // the last day carrying it falls out behind the window's edge.
            if (!after.nodes().containsKey(path)) {
                differences.add(new PathwayDifference(day, path, PathwayFold.identity(path), null,
                        DifferenceType.NODE_LEFT, null, null, null, null, null));
            }
        });
        return differences;
    }

    private static void constraints(final int day,
                                    final List<String> path,
                                    final DayRecord.ObservedNode was,
                                    final DayRecord.ObservedNode now,
                                    final List<PathwayDifference> differences) {
        now.constraints().forEach((name, after) -> {
            final DayRecord.ObservedConstraint before = was.constraints().get(name);
            if (before == null) {
                differences.add(constraint(day, path, name, after,
                        DifferenceType.CONSTRAINT_ARRIVED, null, after.value()));
                return;
            }
            // Asked separately, because a bound can move on the same day the thing it describes
            // starts being absent, and both are worth saying.
            final DifferenceType moved = valueMoved(before.value(), after.value());
            if (moved != null) {
                differences.add(constraint(day, path, name, after, moved,
                        before.value(), after.value()));
            }
            if (before.optional() != after.optional()) {
                // No values on this row: for a pure change of optionality they would be the same
                // value twice, which reads as a bound having moved when none did.
                differences.add(constraint(day, path, name, after,
                        after.optional()
                                ? DifferenceType.BECAME_OPTIONAL
                                : DifferenceType.BECAME_REQUIRED,
                        null, null));
            }
        });
        was.constraints().forEach((name, before) -> {
            if (!now.constraints().containsKey(name)) {
                differences.add(constraint(day, path, name, before,
                        DifferenceType.CONSTRAINT_LEFT, before.value(), null));
            }
        });
    }

    /**
     * Which way a value moved, or null where it did not move at all.
     *
     * <p>Asked by combining the two and seeing which of them the combination already was.
     * {@link ObservedValues#add} covers both of what it is given, so a combination equal to one side
     * means that side already covered the other. Both at once is a value that gained something and
     * lost something, which is neither a widening nor a narrowing. The same shape
     * {@link DayRecord.ObservedConstraint#and} uses to decide which of two accounts already held a
     * value.
     *
     * <p>One limit worth knowing: where combining the two has too many values to list it answers
     * that it admits anything, which matches neither side, so the answer is that the value shifted
     * even where one day's values contained the other's. Telling those apart means asking whether
     * one value covers another, which nothing here can do.
     */
    private static DifferenceType valueMoved(final ConstraintValue before,
                                             final ConstraintValue after) {
        if (Objects.equals(before, after)) {
            return null;
        }
        final ConstraintValue union = ObservedValues.add(before, after);
        // Combining them gave up on the type. That happens for two reasons and only one of them is
        // a type changing: the two really were different kinds of thing, or there were simply too
        // many values to list. Calling the second a type change would tell a reader the work had
        // started carrying something new when all it did was carry more of the same.
        //
        // A side that had already given up is neither. It is a bound that admitted anything and now
        // admits less, or the other way about, which is an ordinary narrowing or widening.
        final boolean hadGivenUp = before instanceof AnyTypeValue || after instanceof AnyTypeValue;
        if (union instanceof AnyTypeValue && !hadGivenUp && !ObservedValues.sameKind(before, after)) {
            return DifferenceType.TYPE_CHANGED;
        }
        final boolean grew = !union.equals(before);
        final boolean shrank = !union.equals(after);
        if (grew && shrank) {
            return DifferenceType.SHIFTED;
        }
        return grew
                ? DifferenceType.WIDENED
                : DifferenceType.NARROWED;
    }

    /**
     * A node the day before did not have, named with the trace that brought it.
     *
     * <p>A day record holds no trace against a node, and it does not need to. A node and its bounds
     * are given by the same traces, so the earliest trace behind any of its bounds is the one that
     * first reached it that day. The histogram's day-wide bars are counted from the traces these
     * rows name, so a row without one is a day that reads as empty.
     *
     * <p>Unnamed only where the node has no bound that was learnt from at all, which today means
     * every one of them was configured to be ignored.
     */
    private static PathwayDifference nodeArrived(final int day,
                                                 final List<String> path,
                                                 final DayRecord.ObservedNode node) {
        final DayRecord.ObservedConstraint first = firstSeen(node);
        return new PathwayDifference(day, path, PathwayFold.identity(path), null,
                DifferenceType.NODE_ARRIVED, null, null,
                first == null
                        ? null
                        : first.traceId(),
                first == null
                        ? null
                        : first.spanId(),
                first == null
                        ? null
                        : first.ranAt());
    }

    private static DayRecord.ObservedConstraint firstSeen(final DayRecord.ObservedNode node) {
        DayRecord.ObservedConstraint first = null;
        for (final DayRecord.ObservedConstraint seen : node.constraints().values()) {
            if (first == null || earlier(seen.ranAt(), first.ranAt())) {
                first = seen;
            }
        }
        return first;
    }

    // A bound with no time on it never wins, so a node whose times are all missing keeps the first
    // bound it holds rather than the last.
    private static boolean earlier(final NanoTime candidate, final NanoTime held) {
        return candidate != null && (held == null || candidate.compareTo(held) < 0);
    }

    private static PathwayDifference constraint(final int day,
                                                final List<String> path,
                                                final String name,
                                                final DayRecord.ObservedConstraint seen,
                                                final DifferenceType type,
                                                final ConstraintValue oldValue,
                                                final ConstraintValue newValue) {
        return new PathwayDifference(day, path, PathwayFold.identity(path), name, type,
                oldValue, newValue, seen.traceId(), seen.spanId(), seen.ranAt());
    }
}
