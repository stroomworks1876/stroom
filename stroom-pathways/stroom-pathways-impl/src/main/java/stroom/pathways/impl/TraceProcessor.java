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
import stroom.pathways.shared.PathwaySummary;
import stroom.pathways.shared.PathwaysDoc;
import stroom.pathways.shared.otel.trace.NanoTime;
import stroom.pathways.shared.otel.trace.Span;
import stroom.pathways.shared.otel.trace.Trace;
import stroom.pathways.shared.pathway.NamePathKey;
import stroom.pathways.shared.pathway.PathKey;
import stroom.pathways.shared.pathway.PathNode;
import stroom.pathways.shared.pathway.Paths;
import stroom.pathways.shared.pathway.Pathway;
import stroom.planb.impl.dao.LmdbWriter;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
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
     *
     * @param oldestDayInWindow the oldest day the model is held to. A trace whose work ran before it
     *                          would be narrowed straight back out, so it changes no model and
     *                          writes no change row - all it leaves is an account of its day, kept
     *                          until the end of the hold and then put in that week's bucket.
     */
    public ApplyOutcome processTrace(final LmdbWriter writer,
                                     final PathwaysDb pathwaysDb,
                                     final byte[] traceId,
                                     final Function<byte[], Optional<Trace>> traceFunction,
                                     final PathwaysDoc doc,
                                     final MessageReceiver messageReceiver,
                                     final String sourceUuid,
                                     final int oldestDayInWindow) {
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
                    } else if (ranOn(trace) < oldestDayInWindow) {
                        // It would be narrowed straight back out, so the model is left alone, no
                        // change row is written and nothing is marked. What the day was given still
                        // counts, and so does the fact that this pathway was given anything at all;
                        // both are kept until the end of the hold.
                        final NanoTime ranAt = ranAt(trace);
                        keepForABucket(trace, dayOf(ranAt), doc, messageReceiver, sourceUuid);
                        noteOldTrace(trace, ranAt);
                        return ApplyOutcome.APPLIED;
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
                        wroteToTheShard = true;
                        writer.tryCommit();
                        return ApplyOutcome.APPLIED;
                    }
                }
            }
            return ApplyOutcome.ALREADY_APPLIED;
        });
    }



    /**
     * What one model was given by a trace whose work ran before the window, without applying it to
     * anything.
     *
     * <p>The ordinary reading of a trace, against no model at all. What it builds is thrown away;
     * what it observed is the answer. That it gives the same account either way is what
     * {@code TestRecordingWithoutAModel} is about - so an old trace and a live one cannot disagree
     * about what a day contained.
     *
     * <p>Two things here do read the model, so the account is not quite the same either way.
     *
     * <p>A value is learnt from only where the constraint is already held or the document allows new
     * ones. Every document allows new ones today, so that one does not bite. Turn it off and a trace
     * taking this route would record nothing at all, because against no model every constraint is a
     * new one.
     *
     * <p>Whether a constraint is optional is learnt across traces - it goes optional when a later
     * trace arrives without the attribute - and that history lives in the model. A trace recorded on
     * its own cannot know it, so it says required where the live route would say optional, and
     * {@link DayRecord.ObservedConstraint#and} only ever ors optional upward.
     * {@code TestRecordingWithoutAModel} holds that difference so it is not found by surprise.
     */
    private DayRecord observed(final Trace trace,
                               final PathwaysDoc doc,
                               final MessageReceiver messageReceiver,
                               final String sourceUuid) {
        final NodeMutatorImpl nodeMutator = mutatorFor(doc);
        nodeMutator.process(trace, pathKeyOf(trace), null, messageReceiver, doc);
        // The trace's own time where it has one, and the moment it was taken in where it does not -
        // the same fallback the route into the live shard uses, so the two agree on which day a
        // trace belongs to.
        final NanoTime ranAt = NullSafe.getOrElse(nodeMutator.getTraceTime(), t -> t,
                NanoTimeUtil.fromInstant(Instant.now()));
        return new DayRecord(nodeMutator.getObservations().nodes(),
                PathRecorder.add(Paths.empty(), nodeMutator.getPathShape(), ranAt, ranAt,
                        trace.getTraceId(), sourceUuid));
    }


    // Gathered rather than written where they are found, so a week is opened once for the whole hold
    // rather than once per trace. A bulk load is thousands of traces across a handful of weeks.
    private final Map<String, Map<DayOfPathway, DayRecord>> beforeTheWindow = new LinkedHashMap<>();
    private boolean wroteToTheShard;
    private final Map<String, OldTraces> names = new LinkedHashMap<>();

    // The day a trace's work ran, which is the day its evidence is filed under.
    private static int ranOn(final Trace trace) {
        return dayOf(ranAt(trace));
    }

    // When a trace's work ran, read off the root span, which is where the mutator reads it too. A
    // root span carrying no start time counts as having run now, so that deciding which route a
    // trace takes is never what breaks on one. Reading its spans still refuses such a trace, as it
    // always has, and that is where it is reported from.
    private static NanoTime ranAt(final Trace trace) {
        final NanoTime ranAt = NanoTime.fromString(trace.root().getStartTimeUnixNano());
        return ranAt == null
                ? NanoTimeUtil.now()
                : ranAt;
    }

    /**
     * Notes that a pathway was given a trace whose work ran before the window.
     *
     * <p>Gathered rather than written where it is found, which is what every other thing this class
     * writes per pathway does. Writing one means reading a row, decoding whatever model stands
     * behind it and writing the whole of it back, and a bulk load of a year offers hundreds of
     * thousands of these - so a hold gathers them and writes one row per pathway at the end.
     */
    private void noteOldTrace(final Trace trace, final NanoTime ranAt) {
        names.merge(pathKeyOf(trace).toString(), new OldTraces(1L, ranAt), OldTraces::and);
    }

    /**
     * Puts the names of pathways given only old traces into the live shard, with what those traces
     * say about them.
     *
     * <p>Such a pathway teaches no model, so without a row of its own nothing lists it, nothing
     * opens it, and none of what its days hold can be reached. The row carries its name and the key
     * that finds its shard, and no create time, which is what says there is no model behind it.
     *
     * <p>Last used and times used do stand on it. They are about traces taking the path, which these
     * did, whatever they taught.
     *
     * <p>A pathway that already has a model is left alone. Its counts are of traces that reached
     * that model and these did not. Decided from the row's header, which stops before the model, so
     * a back-filled trace for a busy pathway does not decode a tree of a thousand constraints to
     * answer whether it has one.
     */
    void writeNames(final LmdbWriter writer, final PathwaysDb pathwaysDb) {
        final SimpleDb pathways = pathwaysDb.getPathways();
        names.forEach((name, seen) -> byteBuffers.useBytes(name.getBytes(StandardCharsets.UTF_8),
                keyByteBuffer -> {
                    final PathwaySummary header = pathways.get(writer.getWriteTxn(),
                            keyByteBuffer.duplicate(), valueByteBuffer -> valueByteBuffer == null
                                    ? null
                                    : pathwaySerde.readSummary(valueByteBuffer));
                    if (header != null && header.getCreateTime() != null) {
                        return;
                    }
                    final Pathway named = header == null
                            ? new Pathway.Builder()
                                    .name(name)
                                    .pathKey(new NamePathKey(name))
                                    .root(new PathNode(name))
                                    // The epoch on both, which is what says no model came into
                                    // being. See Pathway.hasModel.
                                    .createTime(NanoTime.ZERO)
                                    .updateTime(NanoTime.ZERO)
                                    .lastUsedTime(seen.ranAt())
                                    .timesUsed(seen.count())
                                    .build()
                            : pathways.get(writer.getWriteTxn(), keyByteBuffer.duplicate(),
                                    pathwaySerde::readPathway)
                                    .copy()
                                    .lastUsedTime(Times.compare(seen.ranAt(),
                                            header.getLastUsedTime()) > 0
                                            ? seen.ranAt()
                                            : header.getLastUsedTime())
                                    .timesUsed(header.getTimesUsed() + seen.count())
                                    .build();
                    pathwaySerde.writePathway(named, 0, valueByteBuffer ->
                            pathways.insert(writer, keyByteBuffer.duplicate(), valueByteBuffer));
                    wroteToTheShard = true;
                }));
        names.clear();
    }

    /** What a hold's old traces said about one pathway, before any of it is written. */
    private record OldTraces(long count, NanoTime ranAt) {

        private OldTraces and(final OldTraces arrived) {
            return new OldTraces(count + arrived.count(),
                    Times.compare(arrived.ranAt(), ranAt) > 0
                            ? arrived.ranAt()
                            : ranAt);
        }
    }

    private void keepForABucket(final Trace trace,
                                final int day,
                                final PathwaysDoc doc,
                                final MessageReceiver messageReceiver,
                                final String sourceUuid) {
        final byte[] pathwayKey = pathKeyOf(trace).toString().getBytes(StandardCharsets.UTF_8);
        beforeTheWindow.computeIfAbsent(PathwayNarrower.weekOf(day), week -> new LinkedHashMap<>())
                .merge(DayOfPathway.of(pathwayKey, day),
                        observed(trace, doc, messageReceiver, sourceUuid),
                        DayRecord::merge);
    }

    /**
     * Marks the days gathered from traces whose work ran before the window.
     *
     * <p>Those day records go straight to a bucket and never reach the live shard, so nothing else
     * would ever record that the pathway has them. This is the one case the index exists for.
     *
     * <p>Written into the hold's own transaction, which the caller commits before it writes the
     * buckets. That order is the safe one: a failure in between leaves the index naming a day whose
     * bucket was never written, and a reader opens it, finds nothing and carries on. The other way
     * round leaves a day in a bucket that nothing points at, which is the same as losing it.
     *
     * @return whether any day was marked that was not marked already, which is whether the shard is
     * worth pushing back for this.
     */
    boolean writeDayIndex(final LmdbWriter writer, final PathwaysDb pathwaysDb) {
        // Gathered per pathway first. The index is a row apiece, so writing one means reading it,
        // setting its bits and putting the whole row back - and a week of old history offers the
        // same pathway a day at a time.
        final Map<String, byte[]> keyOf = new LinkedHashMap<>();
        final Map<String, List<Integer>> daysOf = new LinkedHashMap<>();
        beforeTheWindow.values().forEach(days -> days.keySet().forEach(dayOfPathway -> {
            final byte[] key = dayOfPathway.bytes();
            final byte[] pathwayKey = Arrays.copyOf(key, key.length - 1 - Integer.BYTES);
            final String owner = new String(pathwayKey, StandardCharsets.UTF_8);
            keyOf.putIfAbsent(owner, pathwayKey);
            daysOf.computeIfAbsent(owner, o -> new ArrayList<>())
                    .add(dayOf(ByteBuffer.wrap(key)));
        }));

        boolean marked = false;
        for (final Entry<String, List<Integer>> entry : daysOf.entrySet()) {
            marked |= DayIndex.mark(writer, pathwaysDb.getDayIndex(), byteBuffers,
                    keyOf.get(entry.getKey()), entry.getValue());
        }
        return marked;
    }

    /**
     * The days gathered from traces whose work ran before the window, ready to go to their buckets.
     *
     * <p>Handed over rather than written here: what a bucket is and where it lives belongs to
     * whoever owns the store, and this is holding a writer for the live shard, which these must
     * never reach.
     */
    Map<String, Map<DayOfPathway, DayRecord>> drainBeforeTheWindow() {
        final Map<String, Map<DayOfPathway, DayRecord>> byWeek = new LinkedHashMap<>(beforeTheWindow);
        beforeTheWindow.clear();
        return byWeek;
    }

    /**
     * Whether anything was written to the live shard, which is the whole question the caller asks.
     *
     * <p>A model moving is one reason and naming a pathway that had no row is the other. A shard
     * left unpushed throws away the local copy, so either is enough.
     */
    boolean wroteToTheShard() {
        return wroteToTheShard;
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

    // What a pathway is called, however the trace reached us. A day written by a trace inside the
    // window and a day written by one before it are the same record only if they are filed under the
    // same name, so neither route may spell this out for itself.
    private static PathKey pathKeyOf(final Trace trace) {
        return new PathKeyFactoryImpl().create(Collections.singletonList(trace.root()));
    }

    private NodeMutatorImpl mutatorFor(final PathwaysDoc doc) {
        return new NodeMutatorImpl(new CanonicalSpanOrder(doc.getTemporalOrderingTolerance()),
                ignoredAttributes, ignoredSpans);
    }

    private void buildPathways(final LmdbWriter writer,
                               final Trace trace,
                               final PathwaysDoc doc,
                               final MessageReceiver messageReceiver,
                               final PathwaysDb pathwaysDb,
                               final String sourceUuid) {
        final NodeMutatorImpl nodeMutator = mutatorFor(doc);

        final Span root = trace.root();
        final PathKey pathKey = pathKeyOf(trace);
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

            // A row carrying a name and nothing else, written so a pathway given only old traces can
            // be found at all. No create time is what says so. This trace is the one bringing the
            // model into being, so it is treated exactly as it would have been had the row not been
            // there: stamped, counted as a creation rather than an update, and handed no root, so
            // the mutator builds one from nothing and records that as a change like any other.
            if (!Pathway.hasModel(pathway)) {
                created[0] = true;
                final NanoTime stamp = NanoTimeUtil.fromInstant(Instant.now());
                pathway = pathway.copy()
                        .createTime(stamp)
                        .lastUsedTime(stamp)
                        .root(null)
                        .build();
            }

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
    // the whole of a model's day — every node, every constraint and every path taken — which is not
    // something to do once a trace on a path that already rewrites the model itself.
    private final Map<DayOfPathway, DayRecord> observedValues = new LinkedHashMap<>();

    // What each model a hold touches needs for its history to be carried on: how its nodes are
    // numbered, and how busy they were when the last reading was taken. Read once per model rather
    // than once per trace, because neither moves except by this hold's own hand.
    private final Map<String, PathwayHistory> histories = new LinkedHashMap<>();

    // What the day holds now, plus what this trace gave it. Read from disk the first time the hold
    // touches a day and kept in memory after that.
    //
    // The path the trace took is interned straight into the day's own account rather than into an
    // account of its own that would then have to be folded in: folding re-numbers everything it is
    // given, so doing it that way interns the same path twice and costs the size of the day rather
    // than the size of the trace.
    private void given(final LmdbWriter writer,
                       final PathwaysDb pathwaysDb,
                       final byte[] pathwayKey,
                       final NanoTime ranAt,
                       final DayRecord seen,
                       final PathShape shape,
                       final String traceId,
                       final String sourceUuid) {
        final DayOfPathway key = DayOfPathway.of(pathwayKey, dayOf(ranAt));
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
     * counts how many traces took each path and how busy each node was, so every applied trace moves
     * the day it falls on and there is no longer a case where a hold writes none.
     */
    int writeObservedValues(final LmdbWriter writer, final PathwaysDb pathwaysDb) {
        int written = 0;
        for (final Entry<DayOfPathway, DayRecord> entry : observedValues.entrySet()) {
            final DayRecord day = entry.getValue();
            byteBuffers.useBytes(entry.getKey().bytes(), (Consumer<ByteBuffer>) keyByteBuffer ->
                    pathwaySerde.writeObservedValues(day, valueByteBuffer ->
                            pathwaysDb.getObservedValues().insert(writer, keyByteBuffer, valueByteBuffer)));
            written++;
        }
        observedValues.clear();
        return written;
    }

    // One model's values for one day, as the key bytes they are written under, worked out once when
    // the day is first touched rather than again at the end of the hold.
    record DayOfPathway(byte[] bytes) {

        static DayOfPathway of(final byte[] pathwayKey, final int day) {
            return new DayOfPathway(observedValuesKey(pathwayKey, day));
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

    // The prefix every day of one model shares, for reading them back. The day sits at the end of
    // the key in big endian, so one model's days are together and in order and a prefix covers all
    // of them.
    static ByteBuffer observedValuesPrefix(final byte[] pathwayKey) {
        final ByteBuffer prefix = ByteBuffer.allocateDirect(pathwayKey.length + 1);
        prefix.put(pathwayKey).put((byte) 0).flip();
        return prefix;
    }

    // The day one of those keys names. Beside the method that writes them, so the layout is only
    // ever described in one place.
    static int dayOf(final ByteBuffer observedValuesKey) {
        return observedValuesKey.getInt(observedValuesKey.limit() - Integer.BYTES);
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
