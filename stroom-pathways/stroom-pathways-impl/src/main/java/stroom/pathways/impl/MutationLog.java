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

import stroom.bytebuffer.impl6.ByteBuffers;
import stroom.pathways.impl.PathwayHistory.Count;
import stroom.pathways.impl.PathwaySerde.StoredChange;
import stroom.pathways.impl.PathwaySerde.StoredCount;
import stroom.pathways.impl.PathwaySerde.StoredNode;
import stroom.pathways.impl.PathwaySerde.StoredTrace;
import stroom.pathways.impl.PathwaySerde.StoredUsage;
import stroom.pathways.shared.otel.trace.NanoTime;
import stroom.pathways.shared.pathway.NodeUsage;
import stroom.pathways.shared.pathway.PathNode;
import stroom.pathways.shared.pathway.PathwayMutation;
import stroom.pathways.shared.pathway.PathwayUsage;
import stroom.planb.impl.dao.LmdbWriter;
import stroom.planb.impl.dao.trace.PathwaysDb;
import stroom.planb.impl.dao.trace.PathwaysDb.SimpleDb;
import stroom.util.shared.NullSafe;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.IntFunction;

/**
 * The record of every change made to a model, numbered in the order the changes were made.
 *
 * <p>A model has one history and everything that changes it writes here: a trace that widened a
 * constraint, and the narrowing that puts one back to what has been seen lately. Numbering them
 * in a single run is what lets a replay walk the model forwards or backwards through both.
 */
@Singleton
public class MutationLog {

    private static final byte CHANGE_MARKER = 0;
    private static final byte USAGE_MARKER = 1;
    private static final byte NODE_MARKER = 2;
    private static final byte TRACE_MARKER = 3;
    private static final byte COUNTS_MARKER = 4;
    private static final byte HIGHEST_MARKER = 5;

    private final ByteBuffers byteBuffers;
    private final PathwaySerde pathwaySerde;

    @Inject
    public MutationLog(final ByteBuffers byteBuffers, final PathwaySerde pathwaySerde) {
        this.byteBuffers = byteBuffers;
        this.pathwaySerde = pathwaySerde;
    }

    /**
     * Adds changes to a model's history, numbered on from whatever it already holds, and records how
     * busy every node was once they had been made.
     *
     * <p>Not private because the narrowing appends through it too: a model has one history,
     * and a change made by a trace and a change made by holding the model to its window have to be
     * numbered in the same run or a replay cannot walk them.
     */
    void append(final LmdbWriter writer,
                final PathwaysDb pathwaysDb,
                final byte[] pathwayKey,
                final List<PathwayMutation> mutations,
                final PathNode root,
                final int source,
                final PathwayHistory history) {
        if (mutations.isEmpty()) {
            return;
        }

        final SimpleDb db = pathwaysDb.getMutations();
        final long first = lastSequence(writer, db, pathwayKey) + 1;

        // What made this run of changes, written once however many it made. Every change here came
        // from one trace, or from one run of the narrowing, so the trace it came from and when it
        // arrived are the same for all of them. The span is not here: it moves from node to node
        // within a trace, so it stays with the change that names the node.
        final PathwayMutation firstMutation = mutations.getFirst();
        byteBuffers.useBytes(traceKey(pathwayKey, first), (Consumer<ByteBuffer>) keyByteBuffer ->
                pathwaySerde.writeTrace(new StoredTrace(firstMutation.getTraceId(),
                                firstMutation.getTime(),
                                firstMutation.getTraceTime(),
                                source),
                        valueByteBuffer -> db.insert(writer, keyByteBuffer, valueByteBuffer)));

        long sequence = first - 1;
        for (final PathwayMutation mutation : mutations) {
            sequence++;
            final long nodeId = idOf(writer, db, pathwayKey, history,
                    new StoredNode(mutation.getNodeUuid(), mutation.getNodePath()));
            final StoredChange change = new StoredChange(sequence,
                    first,
                    nodeId,
                    mutation.getSpanId(),
                    mutation.getConstraint(),
                    mutation.getType(),
                    mutation.isOptional(),
                    mutation.getOldValue(),
                    mutation.getNewValue());
            byteBuffers.useBytes(mutationKey(pathwayKey, sequence), (Consumer<ByteBuffer>) keyByteBuffer ->
                    pathwaySerde.writeMutation(change, valueByteBuffer ->
                            db.insert(writer, keyByteBuffer, valueByteBuffer)));
        }

        writeUsage(writer, db, pathwayKey, sequence, firstMutation.getTime(), root, history);
        recordHighest(writer, db, pathwayKey, sequence);
    }

    /**
     * The numbering this pathway's changes refer to its nodes by, as it stands. Read once for each
     * model a hold touches and added to as that hold meets nodes it has not seen, so the numbering
     * carries on from where the model left it rather than starting again.
     */
    PathwayHistory history(final LmdbWriter writer,
                           final PathwaysDb pathwaysDb,
                           final byte[] pathwayKey) {
        final PathwayHistory history = new PathwayHistory();
        final SimpleDb db = pathwaysDb.getMutations();
        db.iteratePrefix(writer.getWriteTxn(), prefixOf(pathwayKey, NODE_MARKER), (key, value) ->
                history.put(idOfKey(key), pathwaySerde.readNode(value)));
        byteBuffers.useBytes(countsKey(pathwayKey), (Consumer<ByteBuffer>) keyByteBuffer ->
                db.get(writer.getWriteTxn(), keyByteBuffer, value -> {
                    if (value != null) {
                        pathwaySerde.readCounts(value).forEach(count ->
                                history.seed(count.nodeId(),
                                        new Count(count.timesUsed(), count.lastUsedTime())));
                    }
                    return null;
                }));
        return history;
    }

    /**
     * How busy the pathway's nodes are as the last reading left them, written once at the end of a
     * hold rather than with each reading. A reading says only what has moved, so the next one has to
     * know where things stood — and reading that back from the readings would mean replaying the whole
     * history to add one line to it. This is written over each time and is not part of the history.
     */
    void writeCounts(final LmdbWriter writer,
                     final PathwaysDb pathwaysDb,
                     final byte[] pathwayKey,
                     final PathwayHistory history) {
        if (!history.countsMoved()) {
            return;
        }
        final List<StoredCount> counts = new ArrayList<>();
        history.counts().forEach((id, count) ->
                counts.add(new StoredCount(id, count.timesUsed(), false, count.lastUsedTime())));
        byteBuffers.useBytes(countsKey(pathwayKey), (Consumer<ByteBuffer>) keyByteBuffer ->
                pathwaySerde.writeCounts(counts, valueByteBuffer ->
                        pathwaysDb.getMutations().insert(writer, keyByteBuffer, valueByteBuffer)));
        history.countsWritten();
    }

    private long idOf(final LmdbWriter writer,
                      final SimpleDb db,
                      final byte[] pathwayKey,
                      final PathwayHistory history,
                      final StoredNode node) {
        final Long known = history.id(node);
        if (known != null) {
            return known;
        }
        final long id = history.add(node);
        byteBuffers.useBytes(nodeKey(pathwayKey, id), (Consumer<ByteBuffer>) keyByteBuffer ->
                pathwaySerde.writeNode(node, valueByteBuffer ->
                        db.insert(writer, keyByteBuffer, valueByteBuffer)));
        return id;
    }

    /**
     * One pathway's changes, in the order they were made, with the nodes and traces they refer to by
     * number put back. The numbering is read first so that a change can be handed back whole, which
     * is what everything above here expects to be given.
     */
    List<PathwayMutation> read(final SimpleDb db, final byte[] pathwayKey) {
        final Map<Long, StoredNode> nodes = new HashMap<>();
        db.iteratePrefix(prefixOf(pathwayKey, NODE_MARKER), (key, value) ->
                nodes.put(idOfKey(key), pathwaySerde.readNode(value)));

        final Map<Long, StoredTrace> traces = new HashMap<>();
        db.iteratePrefix(prefixOf(pathwayKey, TRACE_MARKER), (key, value) ->
                traces.put(idOfKey(key), pathwaySerde.readTrace(value)));

        final List<PathwayMutation> mutations = new ArrayList<>();
        db.iteratePrefix(prefixOf(pathwayKey, CHANGE_MARKER), (key, value) ->
                mutations.add(resolve(pathwaySerde.readMutation(value), nodes, traces)));
        return mutations;
    }

    private static PathwayMutation resolve(final StoredChange change,
                                           final Map<Long, StoredNode> nodes,
                                           final Map<Long, StoredTrace> traces) {
        final StoredNode node = nodes.get(change.nodeId());
        final StoredTrace trace = traces.get(change.traceSequence());
        return new PathwayMutation(change.sequence(),
                trace == null
                        ? null
                        : trace.time(),
                trace == null
                        ? null
                        : trace.traceTime(),
                trace == null
                        ? -1
                        : trace.source(),
                trace == null
                        ? null
                        : trace.traceId(),
                change.spanId(),
                node == null
                        ? List.of()
                        : node.path(),
                node == null
                        ? null
                        : node.uuid(),
                change.constraint(),
                change.type(),
                change.optional(),
                change.oldValue(),
                change.newValue());
    }

    // What this trace changed about how busy the model's nodes are: the ones it ran, and the ones it
    // no longer holds. The rest of the model stands exactly as the reading before this one left it, so
    // naming it again would be most of what a reading cost. Taken once for the trace, not once for
    // each change it made — a trace that moved nine constraints leaves nine changes and one reading.
    //
    // Taken at all because nothing else records it: how often a node has been used is what the model
    // holds now, and a trace that teaches the model nothing changes it without leaving any trace of
    // having done so. Without these a replay can say what the model allowed at a point but not how
    // busy it was.
    //
    // Numbered with the last change the trace made, which is how winding back to any one of that
    // trace's changes finds this reading: it is the first at or after the change picked. Not the last
    // one before it — that is the state before this trace ran, and a change of this trace being on
    // show means it has. A count moves once for the whole trace, so there is no state between one of
    // its changes and the next for a reading to describe.
    private void writeUsage(final LmdbWriter writer,
                            final SimpleDb db,
                            final byte[] pathwayKey,
                            final long sequence,
                            final NanoTime time,
                            final PathNode root,
                            final PathwayHistory history) {
        final List<StoredCount> moved = new ArrayList<>();
        final Set<Long> present = new HashSet<>();
        collectUsage(writer, db, pathwayKey, root, history, time, moved, present);

        // A node the model no longer holds, said so that a reading rebuilt by carrying the one before
        // it forward does not carry a node that has gone.
        final List<Long> gone = new ArrayList<>();
        history.counts().keySet().forEach(id -> {
            if (!present.contains(id)) {
                gone.add(id);
            }
        });
        gone.forEach(history::uncount);

        byteBuffers.useBytes(usageKey(pathwayKey, sequence), (Consumer<ByteBuffer>) keyByteBuffer ->
                pathwaySerde.writeUsage(new StoredUsage(sequence, time, moved, gone), valueByteBuffer ->
                        db.insert(writer, keyByteBuffer, valueByteBuffer)));
    }

    private void collectUsage(final LmdbWriter writer,
                              final SimpleDb db,
                              final byte[] pathwayKey,
                              final PathNode node,
                              final PathwayHistory history,
                              final NanoTime time,
                              final List<StoredCount> moved,
                              final Set<Long> present) {
        if (node == null) {
            return;
        }
        final long id = idOf(writer, db, pathwayKey, history,
                new StoredNode(node.getUuid(), node.getNodePath()));
        present.add(id);

        final Count was = history.counts().get(id);
        final long timesUsed = node.getTimesUsed();
        final NanoTime lastUsedTime = node.getLastUsedTime();
        if (was == null
            || was.timesUsed() != timesUsed
            || !Objects.equals(was.lastUsedTime(), lastUsedTime)) {
            moved.add(new StoredCount(id, timesUsed, Objects.equals(lastUsedTime, time), lastUsedTime));
            history.count(id, new Count(timesUsed, lastUsedTime));
        }

        NullSafe.list(node.getChildren()).forEach(child ->
                collectUsage(writer, db, pathwayKey, child, history, time, moved, present));
    }

    /**
     * One pathway's readings, in the order they were taken, each put back together whole. A reading
     * says only what moved since the one before it, so they are rebuilt by carrying each forward into
     * the next — which is what reading them in key order amounts to.
     */
    List<PathwayUsage> readUsage(final SimpleDb db, final byte[] pathwayKey) {
        final Map<Long, StoredNode> nodes = new HashMap<>();
        db.iteratePrefix(prefixOf(pathwayKey, NODE_MARKER), (key, value) ->
                nodes.put(idOfKey(key), pathwaySerde.readNode(value)));

        final List<PathwayUsage> readings = new ArrayList<>();
        // Insertion ordered, so a reading names its nodes in the order the model first met them
        // rather than in whatever order a hash gives back.
        final Map<Long, NodeUsage> running = new LinkedHashMap<>();
        db.iteratePrefix(prefixOf(pathwayKey, USAGE_MARKER), (key, value) -> {
            final StoredUsage stored = pathwaySerde.readUsage(value);
            stored.gone().forEach(running::remove);
            stored.moved().forEach(count -> {
                final StoredNode node = nodes.get(count.nodeId());
                running.put(count.nodeId(), new NodeUsage(node == null
                        ? null
                        : node.uuid(),
                        count.timesUsed(),
                        count.usedNow()
                                ? stored.time()
                                : count.lastUsedTime()));
            });
            readings.add(new PathwayUsage(stored.sequence(), new ArrayList<>(running.values())));
        });
        return readings;
    }

    // Where this pathway's history got to. The last change row still here answers it until the
    // narrowing moves rows out, and after that it says only where the part still here got to - so the
    // highest number ever used is kept as well and whichever is greater wins. Numbering from the
    // lower of the two would hand numbers out twice, and a change number is how a bucket's rows and
    // the shard's are told apart when they are read together.
    //
    // Read on the batch's own transaction, so it counts on from what earlier traces in the same
    // batch wrote rather than starting them all again from what was last committed.
    private long lastSequence(final LmdbWriter writer,
                              final SimpleDb db,
                              final byte[] pathwayKey) {
        final long lastHere = db.lastPrefixed(writer.getWriteTxn(), prefixOf(pathwayKey, CHANGE_MARKER),
                key -> key == null
                        ? 0L
                        : key.getLong(key.limit() - Long.BYTES));
        return Math.max(lastHere, highest(writer, db, pathwayKey));
    }

    // Zero where there is none, which is both a pathway nothing has changed yet and a shard written
    // before this row existed. Either way the last change row still here is the better answer.
    private long highest(final LmdbWriter writer, final SimpleDb db, final byte[] pathwayKey) {
        final long[] highest = {0L};
        byteBuffers.useBytes(markedKey(pathwayKey, HIGHEST_MARKER, 0L),
                (Consumer<ByteBuffer>) keyByteBuffer -> db.get(writer.getWriteTxn(), keyByteBuffer,
                        value -> {
                            if (value != null) {
                                highest[0] = value.getLong(value.position());
                            }
                            return null;
                        }));
        return highest[0];
    }

    // One row per pathway, written over each time. Kept beside the changes rather than in the counts
    // row because it has to be there whether or not the counts moved, and it must outlive every
    // change row the narrowing takes away.
    private void recordHighest(final LmdbWriter writer,
                               final SimpleDb db,
                               final byte[] pathwayKey,
                               final long sequence) {
        byteBuffers.useBytes(markedKey(pathwayKey, HIGHEST_MARKER, 0L),
                (Consumer<ByteBuffer>) keyByteBuffer ->
                        byteBuffers.useLong(sequence, (Consumer<ByteBuffer>) valueByteBuffer ->
                                db.insert(writer, keyByteBuffer, valueByteBuffer)));
    }

    // Pathway first, so one model's changes are a single run of keys, then where each sits in that
    // history. Big endian because LMDB orders keys by their bytes, and that is the order a replay
    // walks them in.
    // Alongside the changes but under a marker of their own, so that reading a pathway's changes does
    // not walk over these as well.
    private static byte[] usageKey(final byte[] pathwayKey, final long sequence) {
        return markedKey(pathwayKey, USAGE_MARKER, sequence);
    }

    private static byte[] mutationKey(final byte[] pathwayKey, final long sequence) {
        return markedKey(pathwayKey, CHANGE_MARKER, sequence);
    }

    // What a run of changes came from, numbered with the first change it made, which is the number
    // those changes point back at.
    private static byte[] traceKey(final byte[] pathwayKey, final long sequence) {
        return markedKey(pathwayKey, TRACE_MARKER, sequence);
    }

    private static byte[] nodeKey(final byte[] pathwayKey, final long id) {
        return markedKey(pathwayKey, NODE_MARKER, id);
    }

    // One row per pathway rather than one per reading, so it is numbered zero and written over.
    private static byte[] countsKey(final byte[] pathwayKey) {
        return markedKey(pathwayKey, COUNTS_MARKER, 0L);
    }

    private static byte[] markedKey(final byte[] pathwayKey, final byte marker, final long number) {
        final ByteBuffer buffer = ByteBuffer.allocate(pathwayKey.length + 1 + Long.BYTES);
        buffer.put(pathwayKey);
        buffer.put(marker);
        buffer.putLong(number);
        return buffer.array();
    }

    /**
     * Everything this table holds about one pathway: its changes, its usage readings, and the nodes
     * and traces those refer to. Done here because the markers that tell them apart are this class's
     * own, and a caller that knew only some of them would leave the rest behind as rows nothing can
     * reach.
     *
     * @return how many rows went.
     */
    int removeAll(final LmdbWriter writer, final SimpleDb db, final byte[] pathwayKey) {
        int removed = 0;
        for (final byte marker :
                new byte[]{CHANGE_MARKER, USAGE_MARKER, NODE_MARKER, TRACE_MARKER, COUNTS_MARKER,
                        HIGHEST_MARKER}) {
            removed += db.deletePrefixed(writer, prefixOf(pathwayKey, marker));
        }
        return removed;
    }

    /**
     * One row as it is stored, both halves, so a bucket is written from it rather than from a re-read
     * of a row that has already gone.
     */
    record Row(byte[] key, byte[] value) {

    }

    /**
     * One pathway's rows that the window has passed, grouped by the week they belong to.
     *
     * <p>Nothing is deleted here. What comes back is handed to whoever stores it, and only once that
     * has worked are the same rows dropped.
     *
     * <p>A trace is the unit. Every change a trace made carries that trace's applied time, so a trace
     * and its changes always fall in the same week and move together - which is what stops a
     * wind-back finding half of one. The node rows a change names are copied into every week that
     * takes any of this pathway's changes, because a change row cannot be read without them.
     */
    Expired expired(final LmdbWriter writer,
                    final SimpleDb db,
                    final byte[] pathwayKey,
                    final int oldestDay,
                    final IntFunction<String> weekOfDay) {
        final List<Row> nodeRows = new ArrayList<>();
        db.iteratePrefix(writer.getWriteTxn(), prefixOf(pathwayKey, NODE_MARKER), (key, value) ->
                nodeRows.add(new Row(copyOf(key), copyOf(value))));

        // Which week each trace belongs to, for the ones the window has passed. Held by the trace's
        // own number because that is what a change row names it by.
        final Map<Long, String> weekByTrace = new HashMap<>();
        final Map<String, List<Row>> byWeek = new LinkedHashMap<>();
        db.iteratePrefix(writer.getWriteTxn(), prefixOf(pathwayKey, TRACE_MARKER), (key, value) -> {
            // Taken before the row is read. Reading it walks the buffer to its end, and a row copied
            // after that is a row of nothing.
            final Row row = new Row(copyOf(key), copyOf(value));
            final StoredTrace trace = pathwaySerde.readTrace(value);
            final NanoTime applied = trace.time();
            if (applied == null
                || applied.toEpochMillis() / TraceProcessor.MILLIS_PER_DAY >= oldestDay) {
                return;
            }
            final String week = weekOfDay.apply(
                    (int) (applied.toEpochMillis() / TraceProcessor.MILLIS_PER_DAY));
            weekByTrace.put(idOfKey(key), week);
            byWeek.computeIfAbsent(week, w -> new ArrayList<>()).add(row);
        });

        if (byWeek.isEmpty()) {
            return Expired.NOTHING;
        }

        db.iteratePrefix(writer.getWriteTxn(), prefixOf(pathwayKey, CHANGE_MARKER), (key, value) -> {
            final Row row = new Row(copyOf(key), copyOf(value));
            final String week = weekByTrace.get(pathwaySerde.readMutation(value).traceSequence());
            if (week != null) {
                byWeek.get(week).add(row);
            }
        });

        expireUsage(writer, db, pathwayKey, oldestDay, weekOfDay, byWeek);

        // The rows that move, and the rows that are copied. Node rows are copied: a bucket cannot
        // read its own change rows without them, and neither can the live shard read the ones it
        // keeps. Moving them would take the node names off every change still here.
        final Map<String, List<Row>> toStore = new LinkedHashMap<>();
        byWeek.forEach((week, rows) -> {
            final List<Row> all = new ArrayList<>(rows);
            all.addAll(nodeRows);
            toStore.put(week, all);
        });
        return new Expired(toStore, byWeek);
    }

    /**
     * What a week is given, and what may then be dropped from the live shard.
     *
     * @param toStore what goes in each bucket, including the node rows a change cannot be read
     *                without
     * @param toDrop  the subset that has actually left - never the node rows, which are copied
     */
    record Expired(Map<String, List<Row>> toStore, Map<String, List<Row>> toDrop) {

        static final Expired NOTHING = new Expired(Map.of(), Map.of());
    }

    /**
     * Moves the readings the window has passed into their weeks, and makes both sides of the split
     * readable.
     *
     * <p>A reading says only what moved since the one before it, so a chain cut in half leaves both
     * halves wrong: the part left behind starts in the middle with nothing carried in, and the part
     * taken away starts at its own first reading with nothing before it. So the first reading on each
     * side of a cut is rewritten as a full account of every node, which is what the first reading ever
     * written looks like anyway.
     *
     * <p>The totals come from replaying the chain, which can be done here because nothing has been
     * dropped yet. On a later pass the part left behind already begins with a full account, so the
     * replay begins there.
     */
    private void expireUsage(final LmdbWriter writer,
                             final SimpleDb db,
                             final byte[] pathwayKey,
                             final int oldestDay,
                             final IntFunction<String> weekOfDay,
                             final Map<String, List<Row>> byWeek) {
        final List<Row> rows = new ArrayList<>();
        final List<StoredUsage> readings = new ArrayList<>();
        db.iteratePrefix(writer.getWriteTxn(), prefixOf(pathwayKey, USAGE_MARKER), (key, value) -> {
            rows.add(new Row(copyOf(key), copyOf(value)));
            readings.add(pathwaySerde.readUsage(value));
        });
        if (rows.isEmpty()) {
            return;
        }

        final Map<Long, StoredCount> running = new LinkedHashMap<>();
        String lastWeek = null;
        Row liveBoundary = null;
        StoredUsage liveBoundaryReading = null;

        for (int i = 0; i < rows.size(); i++) {
            final StoredUsage reading = readings.get(i);
            carryForward(running, reading);

            final NanoTime time = reading.time();
            if (time == null || time.toEpochMillis() / TraceProcessor.MILLIS_PER_DAY >= oldestDay) {
                // The first one staying behind. Whatever came before it has gone, so it has to say
                // where everything stood rather than only what it moved.
                liveBoundary = rows.get(i);
                liveBoundaryReading = reading;
                break;
            }

            final String week = weekOfDay.apply(
                    (int) (time.toEpochMillis() / TraceProcessor.MILLIS_PER_DAY));
            final boolean firstInWeek = !week.equals(lastWeek);
            lastWeek = week;
            byWeek.computeIfAbsent(week, w -> new ArrayList<>())
                    .add(firstInWeek
                            ? whole(rows.get(i).key(), reading, running)
                            : rows.get(i));
        }

        if (liveBoundary != null && lastWeek != null) {
            final Row rewritten = whole(liveBoundary.key(), liveBoundaryReading, running);
            byteBuffers.useBytes(rewritten.key(), (Consumer<ByteBuffer>) keyByteBuffer ->
                    byteBuffers.useBytes(rewritten.value(), (Consumer<ByteBuffer>) valueByteBuffer ->
                            db.insert(writer, keyByteBuffer, valueByteBuffer)));
        }
    }

    // Applies one reading to the totals, the way reading them back does.
    private static void carryForward(final Map<Long, StoredCount> running, final StoredUsage reading) {
        NullSafe.list(reading.gone()).forEach(running::remove);
        NullSafe.list(reading.moved()).forEach(count -> running.put(count.nodeId(),
                // The flag says "as at this reading's own time", which only holds where the reading
                // it came from is still the one being read. Spelled out instead, so it survives being
                // carried into a reading written later.
                new StoredCount(count.nodeId(), count.timesUsed(), false,
                        count.usedNow()
                                ? reading.time()
                                : count.lastUsedTime())));
    }

    private Row whole(final byte[] key, final StoredUsage reading, final Map<Long, StoredCount> running) {
        final StoredUsage full = new StoredUsage(reading.sequence(), reading.time(),
                new ArrayList<>(running.values()), List.of());
        final byte[][] written = new byte[1][];
        pathwaySerde.writeUsage(full, value -> written[0] = copyOf(value));
        return new Row(key, written[0]);
    }

    /** Drops rows handed over to a bucket, once they are safely there. */
    void drop(final LmdbWriter writer, final SimpleDb db, final List<Row> rows) {
        rows.forEach(row -> byteBuffers.useBytes(row.key(),
                (Consumer<ByteBuffer>) keyByteBuffer -> db.delete(writer, keyByteBuffer)));
    }

    private static byte[] copyOf(final ByteBuffer buffer) {
        final byte[] bytes = new byte[buffer.remaining()];
        buffer.duplicate().get(bytes);
        return bytes;
    }

    private static ByteBuffer prefixOf(final byte[] pathwayKey, final byte marker) {
        final ByteBuffer prefix = ByteBuffer.allocateDirect(pathwayKey.length + 1);
        prefix.put(pathwayKey).put(marker).flip();
        return prefix;
    }

    private static long idOfKey(final ByteBuffer key) {
        return key.getLong(key.limit() - Long.BYTES);
    }
}
