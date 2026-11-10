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

import stroom.bytebuffer.ByteBufferUtils;
import stroom.pathways.shared.FindPathwayCriteria;
import stroom.pathways.shared.FindPathwayMutationCriteria;
import stroom.pathways.shared.PathwayAsAt;
import stroom.pathways.shared.PathwayMutationResultPage;
import stroom.pathways.shared.PathwayResultPage;
import stroom.pathways.shared.PathwaySummary;
import stroom.pathways.shared.PathwaysDoc;
import stroom.pathways.shared.otel.trace.NanoTime;
import stroom.pathways.shared.pathway.MutationCounts;
import stroom.pathways.shared.pathway.NodeChange;
import stroom.pathways.shared.pathway.NodeUsage;
import stroom.pathways.shared.pathway.PathNode;
import stroom.pathways.shared.pathway.Pathway;
import stroom.pathways.shared.pathway.PathwayMutation;
import stroom.pathways.shared.pathway.PathwayReplay;
import stroom.pathways.shared.pathway.PathwayUsage;
import stroom.planb.impl.dao.ShardKeyRouter;
import stroom.planb.impl.dao.trace.PathwaysDb;
import stroom.planb.impl.data.archive.BucketGranularityUtil;
import stroom.planb.shared.BucketGranularity;
import stroom.planb.shared.SharedFileStoreSettings;
import stroom.query.api.TimeFilter;
import stroom.query.api.TimeRange;
import stroom.util.logging.LambdaLogger;
import stroom.util.logging.LambdaLoggerFactory;
import stroom.util.shared.CriteriaFieldSort;
import stroom.util.shared.NullSafe;
import stroom.util.shared.PageRequest;
import stroom.util.shared.PageResponse;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Answers a pathway search from every shard of a document's model.
 *
 * <p>A pathway is keyed on its operation name and that name decides its shard, so no pathway appears
 * in two shards and nothing has to be combined — only gathered and put back in order.
 *
 * <p>Done in two passes. The first reads names only, which costs no deserialising, and sorts them to
 * get the one order a page can be taken from. The second fetches just the page, and knows which shard
 * to ask because the name says so. The alternative — deserialising every match to find out which
 * belong on the page — would read the whole model to return twenty rows.
 */
@Singleton
public class ShardedPathwayReader {

    private static final Comparator<PathwaySummary> BY_NAME =
            Comparator.comparing(PathwaySummary::getName, Comparator.nullsFirst(Comparator.naturalOrder()));

    private static final LambdaLogger LOGGER = LambdaLoggerFactory.getLogger(ShardedPathwayReader.class);

    private final PathwaysShardStore shardStore;
    private final PathwaySerde pathwaySerde;
    private final MutationLog mutationLog;

    @Inject
    public ShardedPathwayReader(final PathwaysShardStore shardStore,
                                final PathwaySerde pathwaySerde,
                                final MutationLog mutationLog) {
        this.shardStore = shardStore;
        this.pathwaySerde = pathwaySerde;
        this.mutationLog = mutationLog;
    }

    public PathwayResultPage findPathways(final PathwaysDoc doc, final FindPathwayCriteria criteria) {
        final SharedFileStoreSettings settings = doc.getSharedFileStore();
        if (settings == null || NullSafe.isBlankString(settings.getSharedPath())) {
            // Nothing configured, so nothing was ever written for it.
            return new PathwayResultPage(List.of(), PageResponse.empty());
        }
        final int shardCount = shardCountOf(settings);
        final PageRequest pageRequest = criteria.getPageRequest();

        // Every matching pathway, not just the ones on the page: a sort on anything but the name has
        // to see them all before it can say which belong on it. Only each one's header is read, and
        // the scan of the keys has that in hand already.
        final List<PathwaySummary> summaries = new ArrayList<>();
        for (int shard = 0; shard < shardCount; shard++) {
            summaries.addAll(matchingSummaries(doc, shard, criteria.getFilter()));
        }
        summaries.sort(comparator(criteria.getSortList()));

        final List<PathwaySummary> page = summaries.stream()
                .skip(pageRequest.getOffset())
                .limit(pageRequest.getLength())
                .toList();

        final PageResponse pageResponse = PageResponse
                .builder()
                .offset(pageRequest.getOffset())
                .length(page.size())
                .total((long) summaries.size())
                .exact(true)
                .build();
        return new PathwayResultPage(page, pageResponse);
    }

    // What one shard holds that the filter accepts. The name is taken from the key so a rejected
    // pathway costs nothing beyond that; only an accepted one has its header decoded.
    private List<PathwaySummary> matchingSummaries(final PathwaysDoc doc,
                                                   final int shard,
                                                   final String filter) {
        final List<PathwaySummary> summaries = new ArrayList<>();
        readShard(doc, shard, db -> {
            db.getPathways().iterate((key, val) -> {
                final String name = ByteBufferUtils.toString(key);
                if (NullSafe.isBlankString(filter) || name.contains(filter)) {
                    summaries.add(pathwaySerde.readSummary(val));
                }
            });
            return summaries;
        });
        return summaries;
    }

    // Orders by the columns the grid was sorted on, in the order they were clicked. Falls back to the
    // name where nothing was asked for, which is where the grid starts.
    private static Comparator<PathwaySummary> comparator(final List<CriteriaFieldSort> sortList) {
        Comparator<PathwaySummary> comparator = null;
        for (final CriteriaFieldSort sort : NullSafe.list(sortList)) {
            final Comparator<PathwaySummary> field = fieldComparator(sort);
            if (field != null) {
                comparator = comparator == null
                        ? field
                        : comparator.thenComparing(field);
            }
        }
        return comparator == null
                ? BY_NAME
                : comparator;
    }

    private static Comparator<PathwaySummary> fieldComparator(final CriteriaFieldSort sort) {
        final Comparator<PathwaySummary> comparator = switch (NullSafe.string(sort.getId())) {
            case PathwaySummary.FIELD_NAME -> sort.isIgnoreCase()
                    ? Comparator.comparing(PathwaySummary::getName,
                            Comparator.nullsFirst(String.CASE_INSENSITIVE_ORDER))
                    : BY_NAME;
            case PathwaySummary.FIELD_CREATE_TIME -> byTime(PathwaySummary::getCreateTime);
            case PathwaySummary.FIELD_UPDATE_TIME -> byTime(PathwaySummary::getUpdateTime);
            case PathwaySummary.FIELD_LAST_USED_TIME -> byTime(PathwaySummary::getLastUsedTime);
            case PathwaySummary.FIELD_TIMES_USED -> Comparator.comparingLong(PathwaySummary::getTimesUsed);
            case PathwaySummary.FIELD_TIMES_UPDATED -> Comparator.comparingLong(
                    PathwaySummary::getTimesUpdated);
            case PathwaySummary.FIELD_NODES -> Comparator.comparingInt(PathwaySummary::getNodes);
            case PathwaySummary.FIELD_PATHS -> Comparator.comparingInt(PathwaySummary::getPaths);
            case PathwaySummary.FIELD_SIZE -> Comparator.comparingLong(PathwaySummary::getSizeBytes);
            // A column the reader knows nothing about orders on nothing, rather than throwing away a
            // page of results.
            default -> null;
        };
        return comparator == null || !sort.isDesc()
                ? comparator
                : comparator.reversed();
    }

    // A pathway that has never recorded the time sorts before one that has, rather than blowing up.
    private static Comparator<PathwaySummary> byTime(final Function<PathwaySummary, NanoTime> time) {
        return Comparator.comparing(time, Comparator.nullsFirst(Comparator.naturalOrder()));
    }

    /**
     * The changes made to one learnt pathway, from wherever they are now.
     *
     * <p>Only that pathway's shard is opened, and only its own run of keys is walked, because the
     * mutation key begins with the name it belongs to. The same is true of each week bucket read.
     */
    public PathwayMutationResultPage findMutations(final PathwaysDoc doc,
                                                   final FindPathwayMutationCriteria criteria) {
        final SharedFileStoreSettings settings = doc.getSharedFileStore();
        final String name = criteria.getPathwayName();
        if (settings == null || NullSafe.isBlankString(settings.getSharedPath())
            || NullSafe.isBlankString(name)) {
            return new PathwayMutationResultPage(List.of(), PageResponse.empty());
        }

        final int shard = ShardKeyRouter.computeShardIndex(name, shardCountOf(settings));
        final byte[] keyBytes = name.getBytes(StandardCharsets.UTF_8);
        final List<PathwayMutation> live = new ArrayList<>();
        readShard(doc, shard, db -> {
            live.addAll(mutationLog.read(db.getMutations(), keyBytes));
            return null;
        });

        // The range the reader picked, on the same clock everything else here runs on: when the
        // learner took the change in, not when its work ran.
        final TimeFilter window = TraceHistograms.resolveTimeFilter(criteria.getTimeRange());

        // The window splits one history across the live shard and the week buckets, so a list of it
        // covers both. Only the weeks the range touches are opened; a range left open touches them
        // all, which is what asking for every change there has ever been costs.
        final List<PathwayMutation> archived = new ArrayList<>();
        eachWeekIn(doc, shard,
                window == null
                        ? 0L
                        : window.getFrom(),
                window == null
                        ? System.currentTimeMillis()
                        : window.getTo(),
                db -> archived.addAll(mutationLog.read(db.getMutations(), keyBytes)));

        final List<PathwayMutation> mutations = new ArrayList<>(joinedChanges(live, archived));
        if (window != null) {
            mutations.removeIf(mutation -> outside(mutation, window));
        }

        mutations.sort(mutationComparator(criteria.getSortList()));

        final PageRequest pageRequest = criteria.getPageRequest();
        final List<PathwayMutation> page = mutations.stream()
                .skip(pageRequest.getOffset())
                .limit(pageRequest.getLength())
                .toList();
        final PageResponse pageResponse = PageResponse
                .builder()
                .offset(pageRequest.getOffset())
                .length(page.size())
                .total((long) mutations.size())
                .exact(true)
                .build();
        // The span asked for, resolved, so the plot over these rows covers what was picked rather
        // than running on to now. Null where an end was left open.
        return new PathwayMutationResultPage(page, pageResponse,
                bound(window, TimeFilter::getFrom, 0L),
                bound(window, TimeFilter::getTo, Long.MAX_VALUE));
    }

    // Ordered the way the view orders it, so the two cannot drift apart. Newest first where nothing
    // was asked for, which is what a list of recent changes wants.
    private static Comparator<PathwayMutation> mutationComparator(final List<CriteriaFieldSort> sortList) {
        Comparator<PathwayMutation> comparator = null;
        for (final CriteriaFieldSort sort : NullSafe.list(sortList)) {
            final Comparator<PathwayMutation> field =
                    PathwayMutation.comparator(sort.getId(), sort.isDesc());
            comparator = comparator == null
                    ? field
                    : comparator.thenComparing(field);
        }
        return comparator == null
                ? PathwayMutation.comparator(PathwayMutation.FIELD_TIME, true)
                : comparator;
    }

    /**
     * One learnt pathway in full, or empty where the document holds none by that name.
     *
     * <p>Separate from the list because the list does not want this: a pathway keeps every path it has
     * seen an operation take, so reading one to render a row is what a page of rows could not afford.
     */
    public Optional<Pathway> fetchPathway(final PathwaysDoc doc, final String name) {
        final SharedFileStoreSettings settings = doc.getSharedFileStore();
        if (settings == null || NullSafe.isBlankString(settings.getSharedPath())
            || NullSafe.isBlankString(name)) {
            return Optional.empty();
        }
        final int shard = ShardKeyRouter.computeShardIndex(name, shardCountOf(settings));
        final Pathway[] found = {null};
        readShard(doc, shard, db -> {
            withKey(name, keyBuffer -> db.getPathways().get(keyBuffer, valueBuffer -> {
                if (valueBuffer != null) {
                    found[0] = pathwaySerde.readPathway(valueBuffer);
                }
                return null;
            }));
            return null;
        });
        return Optional.ofNullable(found[0]);
    }

    /**
     * One learnt pathway as it stood at one moment, with the figures a drawing of it needs.
     *
     * <p>One shard is opened and the model, its changes and its usage readings all come out of that
     * one open.
     *
     * @param asAtMs when to show it as it stood, read against when the learner took each trace in,
     *               or null for as it stands now.
     */
    public Optional<PathwayAsAt> fetchPathwayAsAt(final PathwaysDoc doc,
                                                  final String name,
                                                  final TimeRange timeRange,
                                                  final Long toSequence) {
        final SharedFileStoreSettings settings = doc.getSharedFileStore();
        if (settings == null || NullSafe.isBlankString(settings.getSharedPath())
            || NullSafe.isBlankString(name)) {
            return Optional.empty();
        }

        final int shard = ShardKeyRouter.computeShardIndex(name, shardCountOf(settings));
        final byte[] keyBytes = name.getBytes(StandardCharsets.UTF_8);
        final Pathway[] found = {null};
        final List<PathwayMutation> mutations = new ArrayList<>();
        final List<PathwayUsage> usage = new ArrayList<>();
        readShard(doc, shard, db -> {
            withKey(name, keyBuffer -> db.getPathways().get(keyBuffer, valueBuffer -> {
                if (valueBuffer != null) {
                    found[0] = pathwaySerde.readPathway(valueBuffer);
                }
                return null;
            }));
            if (found[0] != null) {
                mutations.addAll(mutationLog.read(db.getMutations(), keyBytes));
                usage.addAll(mutationLog.readUsage(db.getMutations(), keyBytes));
            }
            return null;
        });

        if (found[0] == null) {
            return Optional.empty();
        }
        final Long asAtMs = latestIn(timeRange);
        addArchived(doc, shard, keyBytes, asAtMs, mutations, usage);
        return Optional.of(asAt(found[0], mutations, usage, asAtMs, toSequence));
    }

    // The latest time the range covers, or null where it has no end - which is the model as it
    // stands. The range is resolved here rather than in the browser because that is where the parser
    // for expressions like day() and now()-24h lives.
    static Long latestIn(final TimeRange timeRange) {
        final TimeFilter filter = TraceHistograms.resolveTimeFilter(timeRange);
        return filter == null || filter.getTo() == Long.MAX_VALUE
                ? null
                : filter.getTo();
    }

    // The window splits one pathway's history across the live shard and the week buckets, so winding
    // the model back past what the shard still holds means reading the weeks between the time asked
    // for and now. Nothing can be left out: a wind-back passes through every change between the two.
    private void addArchived(final PathwaysDoc doc,
                             final int shard,
                             final byte[] keyBytes,
                             final Long asAtMs,
                             final List<PathwayMutation> mutations,
                             final List<PathwayUsage> usage) {
        if (asAtMs == null) {
            // The model as it stands, so there is nothing to wind back through.
            return;
        }
        final List<PathwayMutation> archivedChanges = new ArrayList<>();
        final List<PathwayUsage> archivedUsage = new ArrayList<>();
        eachWeekIn(doc, shard, asAtMs, System.currentTimeMillis(), db -> {
            archivedChanges.addAll(mutationLog.read(db.getMutations(), keyBytes));
            archivedUsage.addAll(mutationLog.readUsage(db.getMutations(), keyBytes));
        });
        if (archivedChanges.isEmpty() && archivedUsage.isEmpty()) {
            return;
        }

        final List<PathwayMutation> allChanges = joinedChanges(mutations, archivedChanges);
        mutations.clear();
        mutations.addAll(allChanges);
        final List<PathwayUsage> allUsage = joinedUsage(usage, archivedUsage);
        usage.clear();
        usage.addAll(allUsage);
    }

    /**
     * Runs {@code work} against each week bucket the given span touches, oldest first.
     *
     * <p>The bucket names are the index, so which weeks to open is decided from a directory listing
     * and no bucket outside the span is copied down. A week with nothing archived for it has no
     * bucket and is simply not listed.
     */
    private void eachWeekIn(final PathwaysDoc doc,
                            final int shard,
                            final long fromMs,
                            final long toMs,
                            final Consumer<PathwaysDb> work) {
        final List<String> weeks;
        try {
            weeks = shardStore.archivedWeeks(doc, shard);
        } catch (final IOException | RuntimeException e) {
            LOGGER.error(() -> "Could not list the buckets of shard " + shard + " of " + doc.getName()
                               + ", so only what the live shard holds is in this answer: "
                               + e.getMessage(), e);
            return;
        }
        for (final String week : weeks) {
            if (!covers(week, fromMs, toMs)) {
                continue;
            }
            try {
                shardStore.readBucket(doc, shard, week, db -> {
                    work.accept(db);
                    return null;
                });
            } catch (final IOException | RuntimeException e) {
                LOGGER.error(() -> "Could not read the " + week + " bucket of shard " + shard + " of "
                                   + doc.getName() + ", so its changes are missing from this answer: "
                                   + e.getMessage(), e);
            }
        }
    }

    // Whether a bucket's own span overlaps the one asked for. An unreadable label is taken rather
    // than skipped, because reading a bucket that turns out not to be wanted costs a copy and
    // skipping one that was gives a wrong answer.
    static boolean covers(final String week, final long fromMs, final long toMs) {
        final BucketGranularity granularity = BucketGranularityUtil.detect(week);
        if (granularity == null) {
            return true;
        }
        final Instant start = BucketGranularityUtil.bucketStart(granularity, week);
        final Instant end = BucketGranularityUtil.bucketEnd(granularity, week);
        return start == null
               || end == null
               || BucketGranularityUtil.overlaps(start, end, fromMs, toMs);
    }

    // Both sides in one list, in the order they were made. Numbered by the same sequence wherever
    // they are, so a row that is in both - which is what a narrowing interrupted between storing and
    // dropping leaves - is taken once.
    static List<PathwayMutation> joinedChanges(final List<PathwayMutation> live,
                                               final List<PathwayMutation> archived) {
        final Map<Long, PathwayMutation> bySequence = new HashMap<>();
        archived.forEach(change -> bySequence.put(change.getSequence(), change));
        live.forEach(change -> bySequence.put(change.getSequence(), change));
        final List<PathwayMutation> joined = new ArrayList<>(bySequence.values());
        joined.sort(Comparator.comparingLong(PathwayMutation::getSequence));
        return joined;
    }

    // As above. A reading only means anything in its place in the chain, so order matters as much
    // here as it does for the changes.
    static List<PathwayUsage> joinedUsage(final List<PathwayUsage> live,
                                          final List<PathwayUsage> archived) {
        final Map<Long, PathwayUsage> bySequence = new HashMap<>();
        archived.forEach(reading -> bySequence.put(reading.getSequence(), reading));
        live.forEach(reading -> bySequence.put(reading.getSequence(), reading));
        final List<PathwayUsage> joined = new ArrayList<>(bySequence.values());
        joined.sort(Comparator.comparingLong(PathwayUsage::getSequence));
        return joined;
    }

    /**
     * The highest numbered change the learner had taken in by this moment, or null where it had taken
     * none in by then.
     *
     * <p>Null is not the same as none being asked for. Nothing at or before the moment means the
     * change history does not reach it, which is a different answer from showing the model as it
     * stands.
     */
    static Long cutAt(final List<PathwayMutation> mutations, final long asAtMs) {
        Long cut = null;
        for (final PathwayMutation mutation : mutations) {
            final NanoTime time = mutation.getTime();
            if (time != null
                && time.toEpochMillis() <= asAtMs
                && (cut == null || mutation.getSequence() > cut)) {
                cut = mutation.getSequence();
            }
        }
        return cut;
    }

    static PathwayAsAt asAt(final Pathway pathway,
                            final List<PathwayMutation> mutations,
                            final List<PathwayUsage> usage,
                            final Long asAtMs,
                            final Long toSequence) {
        // Taken before anything is wound back, because the scale is what the pathway has ever done
        // rather than what it had done by the moment being shown.
        final long changeCeiling = mostChanged(pathway.getRoot());
        final long usageCeiling = mostUsed(pathway.getRoot());

        // A particular change, picked rather than searched for: stepping through the history asks
        // for them one at a time and every change one trace made carries the same timestamp.
        if (toSequence != null) {
            final Pathway shown = wound(pathway, after(mutations, toSequence));
            return new PathwayAsAt(
                    shown,
                    changesByUuid(shown, MutationCounts.of(mutations, toSequence)),
                    usageByUuid(usage, toSequence),
                    timeOf(mutations, toSequence),
                    changeCeiling,
                    usageCeiling);
        }

        if (asAtMs == null) {
            return new PathwayAsAt(
                    pathway,
                    changesByUuid(pathway, MutationCounts.of(mutations, 0L)),
                    Map.of(),
                    null,
                    changeCeiling,
                    usageCeiling);
        }

        final NanoTime asAt = NanoTime.ofMillis(asAtMs);
        final Long cut = cutAt(mutations, asAtMs);
        if (cut == null) {
            // The learner had taken nothing in by then, so there is nothing to wind back through and
            // no model to show. Folding the day records is the other answer and is not built yet.
            return new PathwayAsAt(
                    pathway.copy().root(null).build(),
                    Map.of(),
                    Map.of(),
                    asAt,
                    changeCeiling,
                    usageCeiling);
        }

        // Nothing to undo means the time asked for is now, however the range was written - so the
        // model as it stands is the answer, and each node's own count of how busy it is goes with it.
        //
        // The readings below must not be used for it. A reading says how busy each node was when it
        // was taken, and one is only taken when the model learns something; a node gets busier every
        // time a trace passes through it. So a reading is usually behind, and using one here would
        // draw thinner lines for a time that is not past at all.
        final List<PathwayMutation> later = after(mutations, cut);
        if (later.isEmpty()) {
            return new PathwayAsAt(
                    pathway,
                    changesByUuid(pathway, MutationCounts.of(mutations, cut)),
                    Map.of(),
                    asAt,
                    changeCeiling,
                    usageCeiling);
        }

        final Pathway shown = wound(pathway, later);
        return new PathwayAsAt(
                shown,
                changesByUuid(shown, MutationCounts.of(mutations, cut)),
                usageByUuid(usage, cut),
                asAt,
                changeCeiling,
                usageCeiling);
    }

    // When the change picked was taken in, so the drawing can say what moment it is showing.
    private static NanoTime timeOf(final List<PathwayMutation> mutations, final long sequence) {
        for (final PathwayMutation mutation : mutations) {
            if (mutation.getSequence() == sequence) {
                return mutation.getTime();
            }
        }
        return null;
    }

    // One end of a resolved window, or null where it was left open - which the parser gives back as
    // the extremes rather than as nothing.
    private static Long bound(final TimeFilter window,
                              final java.util.function.ToLongFunction<TimeFilter> end,
                              final long unbounded) {
        if (window == null) {
            return null;
        }
        final long at = end.applyAsLong(window);
        return at == unbounded
                ? null
                : at;
    }

    private static boolean outside(final PathwayMutation mutation, final TimeFilter window) {
        final NanoTime time = mutation.getTime();
        if (time == null) {
            return false;
        }
        final long at = time.toEpochMillis();
        return at < window.getFrom() || at > window.getTo();
    }

    private static List<PathwayMutation> after(final List<PathwayMutation> mutations, final long cut) {
        final List<PathwayMutation> later = new ArrayList<>();
        for (final PathwayMutation mutation : mutations) {
            if (mutation.getSequence() > cut) {
                later.add(mutation);
            }
        }
        return later;
    }

    private static Pathway wound(final Pathway pathway, final List<PathwayMutation> later) {
        final PathNode root = PathwayReplay.rewind(pathway.getRoot(), later);
        return root == null
                ? pathway.copy().root(null).build()
                : pathway.copy().root(root).build();
    }

    // Keyed by uuid because that is how a drawing asks for them, and only for nodes the model being
    // shown actually holds.
    private static Map<String, NodeChange> changesByUuid(final Pathway shown,
                                                         final MutationCounts counts) {
        final Map<String, NodeChange> byUuid = new HashMap<>();
        collectChanges(shown.getRoot(), counts, byUuid);
        return byUuid;
    }

    private static void collectChanges(final PathNode node,
                                       final MutationCounts counts,
                                       final Map<String, NodeChange> byUuid) {
        if (node == null) {
            return;
        }
        final NodeChange change = counts.node(node.getNodePath());
        if (change != null) {
            byUuid.put(node.getUuid(), change);
        }
        NullSafe.list(node.getChildren()).forEach(child -> collectChanges(child, counts, byUuid));
    }

    // The reading kept at the moment being shown, which is the earliest taken at or after the change
    // picked. Empty for the model as it stands, where each node's own tally is what the drawing reads.
    private static Map<String, NodeUsage> usageByUuid(final List<PathwayUsage> usage, final long cut) {
        final Map<String, NodeUsage> byUuid = new HashMap<>();
        PathwayUsage reading = null;
        for (final PathwayUsage candidate : usage) {
            if (candidate.getSequence() >= cut
                && (reading == null || candidate.getSequence() < reading.getSequence())) {
                reading = candidate;
            }
        }
        if (reading != null) {
            NullSafe.list(reading.getNodes())
                    .forEach(node -> byUuid.put(node.getNodeUuid(), node));
        }
        return byUuid;
    }

    private static long mostChanged(final PathNode node) {
        if (node == null) {
            return 0L;
        }
        long most = node.getTimesChanged();
        for (final PathNode child : NullSafe.list(node.getChildren())) {
            most = Math.max(most, mostChanged(child));
        }
        return most;
    }

    private static long mostUsed(final PathNode node) {
        if (node == null) {
            return 0L;
        }
        long most = node.getTimesUsed();
        for (final PathNode child : NullSafe.list(node.getChildren())) {
            most = Math.max(most, mostUsed(child));
        }
        return most;
    }

    private static void withKey(final String name, final Consumer<ByteBuffer> consumer) {
        final byte[] keyBytes = name.getBytes(StandardCharsets.UTF_8);
        final ByteBuffer keyBuffer = ByteBuffer.allocateDirect(keyBytes.length);
        keyBuffer.put(keyBytes).flip();
        consumer.accept(keyBuffer);
    }

    // A shard that cannot be read must not fail the whole search: the rest of the model is still worth
    // showing, and a shard nothing has applied to yet has no model at all, which is normal.
    private static int shardCountOf(final SharedFileStoreSettings settings) {
        return Math.max(1, settings.getShardCount());
    }

    private <R> void readShard(final PathwaysDoc doc,
                               final int shard,
                               final Function<PathwaysDb, R> work) {
        try {
            shardStore.readShard(doc, shard, work);
        } catch (final IOException | RuntimeException e) {
            LOGGER.error(() -> "Could not read shard " + shard + " of " + doc.getName()
                               + ", so its pathways are missing from this result: " + e.getMessage(), e);
        }
    }
}
