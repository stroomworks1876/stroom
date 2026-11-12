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

package stroom.pathways.client.presenter;

import stroom.pathways.shared.otel.trace.NanoTime;
import stroom.pathways.shared.pathway.HistoryRow;
import stroom.pathways.shared.pathway.PathwayDifference;
import stroom.pathways.shared.pathway.PathwayMutation;
import stroom.util.shared.Expander;
import stroom.util.shared.NullSafe;
import stroom.util.shared.TreeRow;

import java.util.Objects;

/**
 * One line of the mutation list: either a trace, or one change that trace made.
 *
 * <p>A single trace can teach a model fifty things at once, all stamped with the same time, which
 * buries everything around it. So the list holds a row per trace that opens to show what it changed.
 *
 * <p>This is a view of the history rather than part of it — {@link PathwayMutation} is shared with the
 * server and says nothing about being drawn.
 */
class MutationRow implements TreeRow {

    private final HistoryRow row;
    private final String traceId;
    private final String group;
    private final NanoTime time;
    private final NanoTime ranAt;
    private final long sequence;
    private final Expander expander;

    private MutationRow(final HistoryRow row,
                        final String traceId,
                        final String group,
                        final NanoTime time,
                        final NanoTime ranAt,
                        final long sequence,
                        final Expander expander) {
        this.row = row;
        this.traceId = traceId;
        this.group = group;
        this.time = time;
        this.ranAt = ranAt;
        this.sequence = sequence;
        this.expander = expander;
    }

    /**
     * @param sequence the last change the trace made, so selecting the trace shows the model as the
     *                 trace left it rather than as it stood partway through.
     */
    static MutationRow trace(final PathwayMutation last,
                             final NanoTime time,
                             final boolean expanded) {
        return new MutationRow(null, last.getTraceId(), groupOf(last), time, last.getTraceTime(),
                last.getSequence(), new Expander(0, expanded, false));
    }

    static MutationRow change(final PathwayMutation mutation) {
        return new MutationRow(mutation, mutation.getTraceId(), groupOf(mutation), mutation.getTime(),
                mutation.getTraceTime(), mutation.getSequence(), new Expander(1, false, true));
    }

    /**
     * The row a day's differences sit under.
     *
     * <p>A day rather than a trace, because no trace made these: they are two days compared, and the
     * day is the whole of where they sit in time. No sequence either - picking this shows the model
     * as that day ended, which is what picking the date does, rather than a place in a history that
     * has none for these days.
     */
    static MutationRow day(final int day, final boolean expanded) {
        return new MutationRow(null, null, groupOfDay(day), NanoTime.ofMillis(endOfDay(day)), null,
                NO_SEQUENCE, new Expander(0, expanded, false));
    }

    static MutationRow difference(final PathwayDifference difference) {
        return new MutationRow(difference, difference.getTraceId(), groupOfDay(difference.getDay()),
                NanoTime.ofMillis(endOfDay(difference.getDay())), difference.getRanAt(),
                NO_SEQUENCE, new Expander(1, false, true));
    }

    static String groupOfDay(final int day) {
        return "day:" + day;
    }

    // A date means the end of the day it names, which is the moment the model it stands for was
    // folded at.
    private static long endOfDay(final int day) {
        return ((day + 1L) * 86_400_000L) - 1L;
    }

    /**
     * What ties a change to the row it sits under.
     *
     * <p>A trace writes everything it taught the model in one go, so its id names the group. The
     * narrowing has no trace to name, and naming none would put every change it has ever made under
     * one row: opening one run would open them all, and picking one out would pick out the lot. Every
     * change a single run makes to a pathway is stamped with the same instant, so that names it
     * instead.
     */
    static String groupOf(final PathwayMutation mutation) {
        return mutation.getTraceId() != null
                ? mutation.getTraceId()
                : "narrowed:" + NullSafe.get(mutation.getTime(), NanoTime::toEpochNanos);
    }

    /** A row with no sequence, because nothing can be stepped to for the day it describes. */
    static final long NO_SEQUENCE = -1L;

    /**
     * What this row shows, or null where the row is the thing the rows beneath it sit under - the
     * trace that made them, or the day they were found on.
     */
    HistoryRow getRow() {
        return row;
    }

    /** The change this row shows, or null where it shows anything else. */
    PathwayMutation getMutation() {
        return row instanceof final PathwayMutation mutation
                ? mutation
                : null;
    }

    boolean isParent() {
        return row == null;
    }

    String getTraceId() {
        return traceId;
    }

    String getGroup() {
        return group;
    }

    /**
     * When Stroom learnt this change. What the row is placed by, because the plot above the list runs
     * on that clock and so does the order the list is in.
     */
    NanoTime getTime() {
        return time;
    }

    /**
     * When the work this change came from ran, which is what the list shows. A trace can be loaded
     * long after it ran, so the two differ, and it is the first a reader is asking about.
     */
    NanoTime getRanAt() {
        return ranAt;
    }

    /**
     * The point in the history this row winds the model back to.
     */
    long getSequence() {
        return sequence;
    }

    @Override
    public Expander getExpander() {
        return expander;
    }

    // Rows are rebuilt every time the list is drawn, so what marks two of them as the same row is what
    // they stand for, not the object. Without this a trace would close again as soon as it was opened.
    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final MutationRow other = (MutationRow) o;
        // The group and the row itself as well as the sequence: a difference has no sequence, so
        // every difference row would otherwise be the same row as every other.
        return sequence == other.sequence
               && Objects.equals(group, other.group)
               && Objects.equals(row, other.row);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sequence, group, row);
    }
}
