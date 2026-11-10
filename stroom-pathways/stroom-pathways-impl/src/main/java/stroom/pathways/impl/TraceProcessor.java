/*
 * Copyright 2025 Crown Copyright
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
import stroom.pathways.shared.PathwaysDoc;
import stroom.pathways.shared.otel.trace.NanoTime;
import stroom.pathways.shared.otel.trace.Span;
import stroom.pathways.shared.otel.trace.Trace;
import stroom.pathways.shared.pathway.PathKey;
import stroom.pathways.shared.pathway.PathNode;
import stroom.pathways.shared.pathway.Paths;
import stroom.pathways.shared.pathway.Pathway;
import stroom.planb.impl.dao.LmdbWriter;
import stroom.planb.impl.dao.trace.CanonicalSpanOrder;
import stroom.planb.impl.dao.trace.IgnoredSpans;
import stroom.planb.impl.dao.trace.NanoTimeUtil;
import stroom.planb.impl.dao.trace.PathwaysDb;
import stroom.planb.impl.dao.trace.PathwaysDb.SimpleDb;
import stroom.planb.impl.serde.trace.HexStringUtil;
import stroom.util.logging.LambdaLogger;
import stroom.util.logging.LambdaLoggerFactory;
import stroom.util.shared.NullSafe;
import stroom.util.shared.Severity;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.function.Function;

public class TraceProcessor {

    static final long MILLIS_PER_DAY = 86_400_000L;

    private static final LambdaLogger LOGGER = LambdaLoggerFactory.getLogger(TraceProcessor.class);

    private final MutationLog mutationLog;
    private final ByteBuffers byteBuffers;
    private final PathwaySerde pathwaySerde;
    private final IgnoredAttributes ignoredAttributes;
    private final IgnoredSpans ignoredSpans;

    public TraceProcessor(final MutationLog mutationLog,
                          final ByteBuffers byteBuffers,
                          final PathwaySerde pathwaySerde,
                          final IgnoredAttributes ignoredAttributes,
                          final IgnoredSpans ignoredSpans) {
        this.mutationLog = mutationLog;
        this.byteBuffers = byteBuffers;
        this.pathwaySerde = pathwaySerde;
        this.ignoredAttributes = ignoredAttributes;
        this.ignoredSpans = ignoredSpans;
    }

    /**
     * What became of one trace.
     *
     * <p>Three answers rather than two, because a caller that deletes the only copy of a trace once
     * this returns has to tell "there was nothing left to do" from "this could not be done".
     */
    public enum ApplyOutcome {
        /** Folded into the model, or marked so it is not offered again. The store was written to. */
        APPLIED,
        /** This store had already applied it. Nothing was written and there is nothing left to do. */
        ALREADY_APPLIED,
        /** Nothing could be done with it — it has no root span to key a pathway on. Not marked. */
        NOT_APPLICABLE
    }

    /**
     * Folds one trace into the model.
     *
     * @return what became of it. {@link ApplyOutcome#APPLIED} means the store was written to and needs
     * copying back.
     * @throws RuntimeException where the trace could not be applied. Deliberately not absorbed: the
     * caller holds the only copy and decides whether to delete it, so a failure it cannot see is a
     * trace lost.
     */
    public ApplyOutcome processTrace(final LmdbWriter writer,
                                     final PathwaysDb pathwaysDb,
                                     final byte[] traceId,
                                     final Function<byte[], Optional<Trace>> traceFunction,
                                     final PathwaysDoc doc,
                                     final MessageReceiver messageReceiver,
                                     final String sourceUuid) {
        return byteBuffers.useBytes(traceId, keyByteBuffer -> {
            final SimpleDb processingStatus = pathwaysDb.getProcessingStatus();
            final boolean processed = processingStatus
                    .get(writer.getWriteTxn(), keyByteBuffer.duplicate(), Objects::nonNull);
            if (!processed) {
                final Optional<Trace> optTrace = traceFunction.apply(traceId);
                if (optTrace.isEmpty()) {
                    // findTrace returns empty only when the bucket holds no span at all for this
                    // trace — a trace with spans but no root span still comes back. Mark as
                    // processed so it is skipped on future ticks.
                    LOGGER.warn("Skipping trace root {} as the bucket holds no spans for it; " +
                                    "marking as processed to suppress future re-scans",
                            HexStringUtil.encode(traceId));
                    markProcessed(writer, processingStatus, keyByteBuffer);
                    writer.tryCommit();
                    return ApplyOutcome.APPLIED;
                } else {
                    final Trace trace = optTrace.get();
                    LOGGER.debug(() -> "\n" + trace.toString());
                    if (trace.root() == null) {
                        // A pathway is keyed on the root span's name, and this trace has no root
                        // span. Left unmarked rather than marked processed, because the root may
                        // still arrive: nothing offers a trace for processing until it has one, so
                        // leaving the marker off costs nothing and keeps the trace eligible.
                        // No root span, so nothing to name beyond the trace itself.
                        messageReceiver.log(Severity.WARNING, () -> "Skipping trace "
                                + HexStringUtil.encode(traceId) + " as it has no root span");
                        return ApplyOutcome.NOT_APPLICABLE;
                    } else {
                        // No commit falls inside one trace. Its changes, the marker saying it was
                        // applied, and the day record that justifies what it taught all belong
                        // together, and a commit between any two of them leaves a model holding
                        // something nothing accounts for.
                        //
                        // This holds off a commit; it does not undo anything. A trace that throws
                        // part way leaves what it had already written in the transaction, and the
                        // hold goes on to commit it - so the trace is offered again and counted
                        // twice. Covering that means the caller aborting the transaction, which it
                        // does not do today.
                        writer.withoutCommitting(() -> {
                            buildPathways(writer, trace, doc, messageReceiver, pathwaysDb, sourceUuid);
                            markProcessed(writer, processingStatus, keyByteBuffer);
                        });
                        writer.tryCommit();
                        return ApplyOutcome.APPLIED;
                    }
                }
            }
            return ApplyOutcome.ALREADY_APPLIED;
        });
    }



    /**
     * What is written against a trace that has been applied: when it was applied, so the entry can be
     * forgotten once it is too old to be handed over again. The trace id is the key and is all that is
     * read back — nothing asks what the value says except the nightly pass that deletes the old ones.
     */
    private void markProcessed(final LmdbWriter writer,
                               final SimpleDb processingStatus,
                               final ByteBuffer keyByteBuffer) {
        byteBuffers.useLong(System.currentTimeMillis(), (Consumer<ByteBuffer>) valueByteBuffer ->
                processingStatus.insert(writer, keyByteBuffer, valueByteBuffer));
    }

    private void buildPathways(final LmdbWriter writer,
                               final Trace trace,
                               final PathwaysDoc doc,
                               final MessageReceiver messageReceiver,
                               final PathwaysDb pathwaysDb,
                               final String sourceUuid) {
        final CanonicalSpanOrder spanOrder = new CanonicalSpanOrder(doc.getTemporalOrderingTolerance());
        final PathKeyFactory pathKeyFactory = new PathKeyFactoryImpl();
        final NodeMutatorImpl nodeMutator = new NodeMutatorImpl(spanOrder, ignoredAttributes, ignoredSpans);

        final Span root = trace.root();
        final PathKey pathKey = pathKeyFactory.create(Collections.singletonList(root));
        final MessageReceiver messages =
                MessageReceiver.forSpan(messageReceiver, trace.getTraceId(), root.getSpanId());

        // Load current path.
        final SimpleDb pathways = pathwaysDb.getPathways();
        final byte[] keyBytes = pathKey.toString().getBytes(StandardCharsets.UTF_8);
        // What the pathway took last time, which is what it will take again give or take this trace.
        // Read before the value is decoded, because decoding consumes the buffer's position.
        final int[] storedSize = {0};
        // Whether this trace is the one bringing the pathway into being, which is not counted as an
        // update: it taught the model everything it knew, so counting it would leave every pathway
        // reading as having been updated at least once.
        final boolean[] created = {false};
        byteBuffers.useBytes(keyBytes, keyByteBuffer -> {
            Pathway pathway = pathways.get(writer.getWriteTxn(), keyByteBuffer, valueByteBuffer -> {
                if (valueByteBuffer == null) {
                    created[0] = true;
                    // No root yet. The mutator makes it, so that a pathway coming into being is
                    // recorded as a change like any other and a replay can rebuild it from nothing.
                    final Instant now = Instant.now();
                    final NanoTime nanoTime = NanoTimeUtil.fromInstant(now);
                    return Pathway.builder()
                            .name(pathKey.toString())
                            .createTime(nanoTime)
                            .lastUsedTime(nanoTime)
                            .pathKey(pathKey)
                            .build();
                }
                storedSize[0] = valueByteBuffer.remaining();
                return pathwaySerde.readPathway(valueByteBuffer);
            });

            final PathNode pathNode = nodeMutator.process(trace, pathKey, pathway.getRoot(),
                    messageReceiver, doc);
            if (pathNode == null) {
                // The document does not allow a pathway to be created and there was none, so there is
                // nothing to write. The mutator has already said so.
                return;
            }

            // Last used is the last time a trace took this path, and times used is how many have
            // taken it, so both move for every trace applied. Updated is the last time the model
            // itself moved and times updated is how many traces moved it, so those two only move when
            // the trace taught it something. Once a pathway has settled the two pairs come apart,
            // which is how a path that is still busy is told from one that has gone quiet.
            final Instant now = Instant.now();
            final NanoTime nanoTime = NanoTimeUtil.fromInstant(now);
            // Recorded whether or not the trace changed anything, because a path the model already
            // knew is still a path taken.
            final Paths before = pathway.getPaths();
            final Paths after = PathRecorder.add(before,
                    nodeMutator.getPathShape(),
                    nanoTime,
                    nodeMutator.getTraceTime(),
                    trace.getTraceId(),
                    sourceUuid);
            // Numbered against the list the recorder just interned into, so a change and the path the
            // same trace took point at the same store.
            final int source = after.getSources().indexOf(sourceUuid);
            // A path the model has never seen, said on the document's feed. Told apart by the list
            // having grown: a path already known is counted where it stands and adds nothing, so one
            // more entry is one more way through this pathway. Worth hearing about because a pathway
            // settles on the handful of shapes that carry its traffic within the hour and then turns
            // up a new one every few hours at most — and each of those is a single trace that did
            // something the job had never done before, which is easy to miss in a table ordered by how
            // much each path is used.
            if (!created[0] && after.getPaths().size() > before.getPaths().size()) {
                // Taken out before it is said, because what holds the pathway is written to again
                // below and a message is not made up until something asks it for its words.
                final String pathwayName = pathway.getName();
                messages.log(Severity.INFO, () -> "New path for " + pathwayName);
            }

            final Pathway.Builder builder = pathway
                    .copy()
                    .lastUsedTime(nanoTime)
                    .timesUsed(pathway.getTimesUsed() + 1)
                    .root(pathNode)
                    .paths(after);
            if (nodeMutator.isChanged()) {
                builder.updateTime(nanoTime);
                if (!created[0]) {
                    builder.timesUpdated(pathway.getTimesUpdated() + 1);
                }
            }
            pathway = builder.build();

            // Write pathway.
            pathwaySerde.writePathway(pathway, storedSize[0], byteBuffer ->
                    pathways.insert(writer, keyByteBuffer, byteBuffer));

            // In the same transaction as the model change they describe, so the two cannot disagree
            // and a batch that fails leaves neither.
            mutationLog.append(writer, pathwaysDb, keyBytes, nodeMutator.getMutations(), pathNode, source,
                    histories.computeIfAbsent(pathKey.toString(),
                            name -> mutationLog.history(writer, pathwaysDb, keyBytes)));

            // Held a day at a time, because that is the smallest step the narrowing moves the
            // window by. Gathered beside the model change it caused rather than in a pass of its own,
            // and written before any commit that follows the trace, so a widening cannot reach disk
            // without the value that taught it: the narrowing would otherwise take that widening
            // straight back out as something the model holds but has not been given.
            // Filed under the day the trace ran, not the day it was applied. A queue that has been
            // held up hands over days of traces at once, and filing those under today would put
            // behaviour in the window that happened outside it and leave the days it did happen on
            // empty.
            final NanoTime ranAt = NullSafe.getOrElse(nodeMutator.getTraceTime(), t -> t, nanoTime);
            given(writer, pathwaysDb, keyBytes, ranAt, nodeMutator.getObservations(),
                    nodeMutator.getPathShape(), trace.getTraceId(), sourceUuid);
        });
    }

    // What each model has been given, by the day the traces ran, built up across a hold and written
    // at the end of it. Held here rather than written per trace because writing one is serialising
    // the whole of a model's day — every node, every constraint and every walk — which is not
    // something to do once a trace on a path that already rewrites the model itself.
    private final Map<DayOfPathway, DayRecord> observedValues = new LinkedHashMap<>();

    // What each model a hold touches needs for its history to be carried on: how its nodes are
    // numbered, and how busy they were when the last reading was taken. Read once per model rather
    // than once per trace, because neither moves except by this hold's own hand.
    private final Map<String, PathwayHistory> histories = new LinkedHashMap<>();

    // What the day holds now, plus what this trace gave it. Read from disk the first time the hold
    // touches a day and kept in memory after that.
    //
    // The walk is interned straight into the day's own account rather than into an account of its
    // own that would then have to be folded in: folding re-numbers everything it is given, so doing
    // it that way interns the same walk twice and costs the size of the day rather than the size of
    // the trace.
    private void given(final LmdbWriter writer,
                       final PathwaysDb pathwaysDb,
                       final byte[] pathwayKey,
                       final NanoTime ranAt,
                       final DayRecord seen,
                       final PathShape shape,
                       final String traceId,
                       final String sourceUuid) {
        final DayOfPathway key = new DayOfPathway(pathwayKey, dayOf(ranAt));
        final DayRecord held = observedValues.computeIfAbsent(key, k -> read(writer, pathwaysDb, k));
        final Map<List<String>, DayRecord.ObservedNode> nodes = new LinkedHashMap<>(held.nodes());
        seen.nodes().forEach((path, arrived) -> nodes.merge(path, arrived,
                DayRecord.ObservedNode::and));
        // Both times are when the work ran, not when it was applied. A day record says what happened
        // that day, and an applied time is the wall clock of whenever the queue got to it - so giving
        // the same traces again would write a different record and the day would stop being a union.
        observedValues.put(key, new DayRecord(nodes,
                PathRecorder.add(held.paths(), shape, ranAt, ranAt, traceId, sourceUuid)));
    }

    private DayRecord read(final LmdbWriter writer,
                           final PathwaysDb pathwaysDb,
                           final DayOfPathway key) {
        // Typed up front because useBytes offers both a reading and a writing form, and a lambda alone
        // does not say which is wanted.
        final Function<ByteBuffer, DayRecord> read = keyByteBuffer ->
                pathwaysDb.getObservedValues().get(writer.getWriteTxn(),
                        keyByteBuffer.duplicate(),
                        value -> value == null
                                ? DayRecord.empty()
                                : pathwaySerde.readObservedValues(value));
        return byteBuffers.useBytes(key.bytes(), read);
    }

    // The day as it is written. Both halves are put in an order taken from what they hold rather than
    // from the order this hold happened to meet them: the nodes by their path, the walks by
    // renumbering. Otherwise two holds given the same traces in a different order write different
    // bytes, and old history could not be loaded without a marker per trace saying it already had
    // been. The node order is also what a drawing is laid out by walking, so it has to be settled
    // here rather than left to whichever trace arrived first.
    private static DayRecord written(final DayRecord day) {
        final Map<List<String>, DayRecord.ObservedNode> inOrder =
                new TreeMap<>(TraceProcessor::byPath);
        day.nodes().forEach((path, node) -> inOrder.put(path, ordered(node)));
        return new DayRecord(inOrder, PathRecorder.canonical(day.paths()));
    }

    // The constraints too, for the same reason: the trace table is numbered by walking them, so two
    // holds meeting one node's constraints in a different order would otherwise write different
    // bytes for the same day.
    private static DayRecord.ObservedNode ordered(final DayRecord.ObservedNode node) {
        return new DayRecord.ObservedNode(node.nodeUuid(), node.timesUsed(),
                new TreeMap<>(node.constraints()));
    }

    // A path at a time, so a node sits next to the ones it shares a parent with and after that
    // parent.
    private static int byPath(final List<String> first, final List<String> second) {
        for (int i = 0; i < Math.min(first.size(), second.size()); i++) {
            final int order = first.get(i).compareTo(second.get(i));
            if (order != 0) {
                return order;
            }
        }
        return Integer.compare(first.size(), second.size());
    }

    /**
     * How busy each model's nodes were when its last reading was taken. Written once here rather than
     * with every reading, because a reading says only what moved and the next one has to know where
     * things stood. Nothing in a hold reads it back, so it is left until the hold is done with.
     */
    void writeHistories(final LmdbWriter writer, final PathwaysDb pathwaysDb) {
        histories.forEach((name, history) -> mutationLog.writeCounts(writer, pathwaysDb,
                name.getBytes(StandardCharsets.UTF_8), history));
        histories.clear();
    }

    /**
     * Writes what the models have been given during this hold, for the days the traces ran on.
     *
     * <p>Registered with the writer as the work to do inside every commit, so that what a model was
     * given and what it became are written together whichever commit lands - the one at the end of
     * the hold, one a long hold takes when it crosses the threshold, or the one inside the writer's
     * own close.
     *
     * @return how many were written, which is one per model per day the hold touched. A day record
     * counts how many traces took each walk and how busy each node was, so every applied trace moves
     * the day it falls on and there is no longer a case where a hold writes none.
     */
    int writeObservedValues(final LmdbWriter writer, final PathwaysDb pathwaysDb) {
        int written = 0;
        for (final Entry<DayOfPathway, DayRecord> entry : observedValues.entrySet()) {
            final DayRecord day = entry.getValue();
            byteBuffers.useBytes(entry.getKey().bytes(), (Consumer<ByteBuffer>) keyByteBuffer ->
                    pathwaySerde.writeObservedValues(written(day), valueByteBuffer ->
                            pathwaysDb.getObservedValues().insert(writer, keyByteBuffer, valueByteBuffer)));
            written++;
        }
        observedValues.clear();
        return written;
    }

    // One model's values for one day. Holds the key bytes it is written under so the end of the hold
    // does not have to work them out again.
    private record DayOfPathway(byte[] bytes) {

        private DayOfPathway(final byte[] pathwayKey, final int day) {
            this(observedValuesKey(pathwayKey, day));
        }

        @Override
        public boolean equals(final Object o) {
            return o instanceof final DayOfPathway other && Arrays.equals(bytes, other.bytes);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(bytes);
        }
    }

    // The day a time falls in, counted from the epoch. Days rather than anything finer because the
    // window moves a day at a time, and anything finer multiplies what is stored for no more answer.
    static int dayOf(final NanoTime time) {
        return (int) (time.toEpochMillis() / MILLIS_PER_DAY);
    }

    private static byte[] observedValuesKey(final byte[] pathwayKey, final int day) {
        final ByteBuffer buffer = ByteBuffer.allocate(pathwayKey.length + 1 + Integer.BYTES);
        buffer.put(pathwayKey);
        buffer.put((byte) 0);
        // Big endian, so the days of one pathway read back in order and a prefix covers all of them.
        buffer.putInt(day);
        return buffer.array();
    }

}
