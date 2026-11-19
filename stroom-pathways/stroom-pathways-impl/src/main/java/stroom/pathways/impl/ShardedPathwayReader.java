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
import stroom.pathways.shared.pathway.DifferenceType;
import stroom.pathways.shared.pathway.HistoryBar;
import stroom.pathways.shared.pathway.HistoryRow;
import stroom.pathways.shared.pathway.MutationCounts;
import stroom.pathways.shared.pathway.NodeChange;
import stroom.pathways.shared.pathway.NodeUsage;
import stroom.pathways.shared.pathway.PathNode;
import stroom.pathways.shared.pathway.Pathway;
import stroom.pathways.shared.pathway.PathwayDifference;
import stroom.pathways.shared.pathway.PathwayMutation;
import stroom.pathways.shared.pathway.PathwayReplay;
import stroom.pathways.shared.pathway.PathwayUsage;
import stroom.planb.impl.dao.ShardKeyRouter;
import stroom.planb.impl.dao.trace.PathwaysDb;
import stroom.planb.impl.dao.trace.PathwaysDb.SimpleDb;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
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

    /**
     * How many bars the days before the model existed are shown in, at most.
     *
     * <p>Fewer than the change side gets, because the change side is what a reader is usually
     * reading and these sit beside it. Below about this many the hatching stops reading: it repeats
     * every seven pixels or so across a bar, so a bar much narrower shows one stripe or none, and a
     * day bar that cannot show its hatching is just a bar.
     */
    private static final int DAY_BARS = 40;

    /**
     * How many slices the stretch the change history reaches is cut into.
     *
     * <p>A count rather than a width, because the plot gives every bar the same room on screen, so
     * this is also how much of that room the changes get beside the days. Sixty against a handful of
     * days leaves the days clearly marked without burying them.
     */
    private static final int CHANGE_BARS = 60;

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
     *
     * <p>The plot comes back with the rows, counted over every row the range holds rather than over
     * the page, and over one node's changes where the criteria names a node. A page of no length is
     * how the plot is asked for on its own, which is what narrowing the table to a node does.
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
        // The model's own row as well as its changes, because where the range reaches back past the
        // moment it came into being there are no changes to list for those days and they have to be
        // worked out instead.
        final Pathway[] found = {null};
        readShard(doc, shard, db -> {
            withKey(name, keyBuffer -> db.getPathways().get(keyBuffer, valueBuffer -> {
                if (valueBuffer != null) {
                    found[0] = pathwaySerde.readPathway(valueBuffer);
                }
                return null;
            }));
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

        final List<HistoryRow> rows = new ArrayList<>(
                differences(doc, shard, keyBytes, found[0], window));
        rows.addAll(mutations);
        rows.sort(rowComparator(criteria.getSortList()));

        final PageRequest pageRequest = criteria.getPageRequest();
        final List<HistoryRow> page = rows.stream()
                .skip(pageRequest.getOffset())
                .limit(pageRequest.getLength())
                .toList();
        final PageResponse pageResponse = PageResponse
                .builder()
                .offset(pageRequest.getOffset())
                .length(page.size())
                .total((long) rows.size())
                .exact(true)
                .build();
        // The span asked for, resolved, so the plot over these rows covers what was picked rather
        // than running on to now. Null where an end was left open.
        return new PathwayMutationResultPage(page, pageResponse,
                bound(window, TimeFilter::getFrom, 0L),
                bound(window, TimeFilter::getTo, Long.MAX_VALUE),
                bars(rows, window, found[0], criteria.getPlotNode()));
    }

    /**
     * How each day in the range differed from the day before, for the days the change history does
     * not reach.
     *
     * <p>Worked out here rather than stored. Only the days that were given traces of their own are
     * described, and where two of those run together the first one's fold is handed on as the
     * second one's "before" rather than being folded again.
     *
     * <p>Only days before the model came into being. From that day on there are change rows, and a
     * day would otherwise be listed twice in two vocabularies.
     */
    private List<PathwayDifference> differences(final PathwaysDoc doc,
                                                final int shard,
                                                final byte[] keyBytes,
                                                final Pathway pathway,
                                                final TimeFilter window) {
        if (pathway == null) {
            return List.of();
        }
        // No range asked for means the whole history, the same as it does for the changes beside
        // these - so every day the model existed before is described, not none of them.
        final int lastDay = Math.min(window == null
                        ? Integer.MAX_VALUE
                        : dayOf(window.getTo()),
                lastDayBeforeTheModel(pathway));
        // Never further back than the oldest bucket. A range left open at its start resolves to the
        // epoch, and folding every day since would be fifty thousand shard reads for an answer that
        // is empty until the first bucket - a day before the model came into being can only have a
        // record in a bucket, because the trace that made the model is the one that created it.
        final int firstDay = Math.max(window == null
                        ? Integer.MIN_VALUE
                        : dayOf(window.getFrom()),
                oldestArchivedDay(doc, shard));
        if (lastDay < firstDay) {
            return List.of();
        }

        final ByteBuffer prefix = TraceProcessor.observedValuesPrefix(keyBytes);
        final List<PathwayDifference> differences = new ArrayList<>();
        DayRecord carried = null;
        int carriedDay = Integer.MIN_VALUE;
        for (final int day : daysGiven(doc, shard, prefix, firstDay, lastDay)) {
            // The day before, so the day reads as what it changed rather than as though everything
            // on it arrived at once. Carried over where the days run together, because one day's
            // fold is the next day's "before" and folding both ends of every day doubles the work.
            final DayRecord before = carriedDay == day - 1
                    ? carried
                    : foldOf(doc, shard, prefix, day - 1);
            final DayRecord after = foldOf(doc, shard, prefix, day);
            differences.addAll(DayDifferences.between(before, after, day));
            carried = after;
            carriedDay = day;
        }
        return differences;
    }

    /**
     * The days in the range that were given traces of their own, oldest first.
     *
     * <p>Only these are described. A day's model is a window of days folded together, so the day the
     * oldest of them falls out behind the window's edge holds less than the day before it. Nothing
     * happened on that day - the traces simply aged out - and a row saying the work had gone would
     * be read as the work having stopped.
     *
     * <p>The day is in the key, so this reads no records and decodes nothing. It also decides how
     * much folding there is to do: a range of a year where two days were given something costs two
     * days of folding rather than three hundred and sixty five.
     */
    private SortedSet<Integer> daysGiven(final PathwaysDoc doc,
                                         final int shard,
                                         final ByteBuffer prefix,
                                         final int firstDay,
                                         final int lastDay) {
        final SortedSet<Integer> days = new TreeSet<>();
        readShard(doc, shard, db -> {
            daysOf(db, prefix, firstDay, lastDay, days);
            return null;
        });
        eachWeekIn(doc, shard, startOfDay(firstDay), endOfDay(lastDay), db ->
                daysOf(db, prefix, firstDay, lastDay, days));
        return days;
    }

    private static void daysOf(final PathwaysDb db,
                               final ByteBuffer prefix,
                               final int firstDay,
                               final int lastDay,
                               final Set<Integer> days) {
        db.getObservedValues().iteratePrefix(prefix.duplicate(), (key, value) -> {
            final int day = TraceProcessor.dayOf(key);
            if (day >= firstDay && day <= lastDay) {
                days.add(day);
            }
        });
    }

    /**
     * The first day any bucket could hold a record for, or a day after the end of time where there
     * are none.
     *
     * <p>One directory listing. The buckets are named for the week they cover, so the oldest name is
     * the oldest day that can have anything in it.
     */
    private int oldestArchivedDay(final PathwaysDoc doc, final int shard) {
        return archivedDays(doc, shard)[0];
    }

    private int newestArchivedDay(final PathwaysDoc doc, final int shard) {
        return archivedDays(doc, shard)[1];
    }

    // The first and last day the buckets of one shard could hold anything for, read off their names
    // without opening any of them. Both are the whole of what is known about where a pathway's
    // records begin and end before any of them are read, and neither depends on what is being
    // looked at - which is what lets a range of days share one answer.
    private int[] archivedDays(final PathwaysDoc doc, final int shard) {
        final List<String> weeks;
        try {
            weeks = shardStore.archivedWeeks(doc, shard);
        } catch (final IOException | RuntimeException e) {
            LOGGER.error(() -> "Could not list the buckets of shard " + shard + " of " + doc.getName()
                               + ", so no day before the model came into being is described: "
                               + e.getMessage(), e);
            return new int[]{Integer.MAX_VALUE, Integer.MIN_VALUE};
        }
        int oldest = Integer.MAX_VALUE;
        int newest = Integer.MIN_VALUE;
        for (final String week : weeks) {
            final BucketGranularity granularity = BucketGranularityUtil.detect(week);
            if (granularity != null) {
                final Instant start = BucketGranularityUtil.bucketStart(granularity, week);
                final Instant end = BucketGranularityUtil.bucketEnd(granularity, week);
                if (start != null) {
                    oldest = Math.min(oldest, dayOf(start.toEpochMilli()));
                }
                if (end != null) {
                    newest = Math.max(newest, dayOf(end.toEpochMilli() - 1));
                }
            }
        }
        return new int[]{oldest, newest};
    }

    // The last day that has no change row of its own. A model comes into being with its first
    // change, so everything before that day is answered by folding and everything after by the
    // history itself.
    private static int lastDayBeforeTheModel(final Pathway pathway) {
        // No row at all. Nothing is described, because there is nothing for a described day to come
        // before.
        //
        // A row with no model behind it answers the largest day there is: no model came into being,
        // so no day is left to the change history. That is a bound to narrow, never one to count up
        // to - every caller meets it with something of its own.
        return Pathway.hasModel(pathway)
                ? dayOf(pathway.getCreateTime().toEpochMillis()) - 1
                : pathway == null
                        ? Integer.MIN_VALUE
                        : Integer.MAX_VALUE;
    }

    private DayRecord foldOf(final PathwaysDoc doc,
                             final int shard,
                             final ByteBuffer prefix,
                             final int day) {
        final int oldestDay = PathwayNarrower.oldestDayInWindow(doc, day);
        final DayRecord[] live = {DayRecord.empty()};
        readShard(doc, shard, db -> {
            live[0] = daysIn(db, prefix, oldestDay, day);
            return null;
        });
        return DayRecord.merge(live[0], bucketDays(doc, shard, prefix, oldestDay, day));
    }

    /**
     * The plot over these rows, oldest bar first.
     *
     * <p>Of two kinds, because the rows are. A day the change history does not reach gets one bar
     * covering the whole of it. The stretch the history does reach is cut into equal slices, which
     * are minutes or hours where a day is a day. The plot gives every bar the same width on screen
     * and hatches the day-wide ones, so the two are never read against each other.
     *
     * <p>The two sides do not count the same thing and cannot. A change bar counts distinct traces,
     * one counting once however much it taught, and a run of the narrowing counts as one because it
     * is a row in the table with no trace of its own. A day has no trace to count by: its record
     * keeps one trace per bound, so counting those would give about five however many ran. A day bar
     * counts what moved instead, which is the number of rows opening that day shows. The plot scales
     * each side against its own tallest bar and hatches the day-wide ones, because a count of traces
     * and a count of things that moved put on one scale would flatten whichever is smaller.
     *
     * <p>Counted over every row the range covers rather than over the page, so turning a page leaves
     * the plot where it was.
     */
    private static List<HistoryBar> bars(final List<HistoryRow> rows,
                                         final TimeFilter window,
                                         final Pathway pathway,
                                         final String plotNode) {
        // The day side is not narrowed, because the table does not narrow it either: filtering to a
        // node takes changes out of the list and leaves the days alone, and a plot that answered
        // differently from the rows under it would be the thing this was meant to fix.
        final List<HistoryBar> bars = new ArrayList<>(dayBars(rows, pathway));
        bars.addAll(changeBars(rows, window, plotNode));
        return bars;
    }

    /**
     * Bars covering every day from the first that was given something to the day before the model
     * came into being, counting what moved in each.
     *
     * <p>The height is everything that moved, which is what opening those days in the table shows.
     * Worked out from the fold rather than counted as traces arrive, so loading the same history
     * twice leaves the bar where it was.
     *
     * <p>A stretch that was given nothing gets a bar of no height rather than no bar. Every bar takes
     * the same room on screen, so leaving the quiet days out would stand a day in October next to one
     * in November and say the work ran on both and on nothing in between.
     *
     * <p>They start at the first day that has something, not at the oldest bucket. A model whose
     * records begin a year before it did would otherwise start with a year of empty bars.
     *
     * <p>How many days a bar covers is worked out rather than fixed, the same way the change side
     * works out how long each of its slices is. A day apiece is the floor, because a day is the
     * finest model there is, and the count is what gives way once there are more days than the panel
     * can show them in. A width taken from a ladder would hold the edges still across requests, which
     * is what the traces histogram needs because its span runs to {@code now()} and creeps. This span
     * is pinned at both ends by the records themselves, so there is nothing to hold still.
     */
    private static List<HistoryBar> dayBars(final List<HistoryRow> rows, final Pathway pathway) {
        final NavigableMap<Integer, Long> byDay = new TreeMap<>();
        for (final HistoryRow row : rows) {
            if (row instanceof final PathwayDifference difference) {
                byDay.merge(difference.getDay(), 1L, Long::sum);
            }
        }
        if (byDay.isEmpty()) {
            return List.of();
        }
        final int firstDay = byDay.firstKey();
        // Where a model came into being, the quiet days run up to the day before it did. Where none
        // did there is nothing for them to run up to, so they stop at the newest day with a record.
        final int lastDay = Pathway.hasModel(pathway)
                ? Math.max(lastDayBeforeTheModel(pathway), byDay.lastKey())
                : byDay.lastKey();
        final int days = (lastDay - firstDay) + 1;
        final int perBar = Math.max(1, ((days + DAY_BARS) - 1) / DAY_BARS);

        final List<HistoryBar> bars = new ArrayList<>(((days + perBar) - 1) / perBar);
        for (int day = firstDay; day <= lastDay; day += perBar) {
            // Never past the last described day: a bar standing over days the model already existed
            // for would be answering with a fold for days the change history covers.
            final int to = Math.min(lastDay, (day + perBar) - 1);
            long moved = 0L;
            for (int each = day; each <= to; each++) {
                moved += byDay.getOrDefault(each, 0L);
            }
            bars.add(new HistoryBar(startOfDay(day), endOfDay(to), moved, true));
        }
        return bars;
    }

    private static List<HistoryBar> changeBars(final List<HistoryRow> rows,
                                               final TimeFilter window,
                                               final String plotNode) {
        // Where the slices fall is worked out over every row, narrowed or not, so that turning the
        // filter on changes the heights and leaves the bars themselves where they were. Worked out
        // over the narrowed rows instead, the plot would rescale under the reader and the bar they
        // were looking at would move.
        long first = Long.MAX_VALUE;
        long last = Long.MIN_VALUE;
        for (final HistoryRow row : rows) {
            final Long at = appliedAt(row);
            if (at != null) {
                first = Math.min(first, at);
                last = Math.max(last, at);
            }
        }
        if (first > last) {
            return List.of();
        }
        // Out to the end asked for, or to now where the range was left open. A model nothing has
        // taught for an hour then reads as an hour of quiet rather than as one busy to the last bar.
        final long to = window == null || window.getTo() == Long.MAX_VALUE
                ? Math.max(last, System.currentTimeMillis())
                : window.getTo();

        // Always the same number of slices, however little time the changes cover. Fitting the
        // slices to the history instead would leave a model taught by a single trace with one bar
        // across the whole of its side, which reads as a model changing steadily throughout when
        // what happened was one change at one moment.
        final long span = Math.max(1L, (to - first) + 1);
        final long width = Math.max(1L, ((span + CHANGE_BARS) - 1) / CHANGE_BARS);
        final List<Set<String>> counted = new ArrayList<>(CHANGE_BARS);
        for (int i = 0; i < CHANGE_BARS; i++) {
            counted.add(new HashSet<>());
        }
        for (final HistoryRow row : rows) {
            final Long at = appliedAt(row);
            if (at != null && touches(row, plotNode)) {
                final int i = (int) Math.min(CHANGE_BARS - 1, (at - first) / width);
                counted.get(i).add(PathwayMutation.groupOf((PathwayMutation) row));
            }
        }
        final List<HistoryBar> bars = new ArrayList<>(CHANGE_BARS);
        for (int i = 0; i < CHANGE_BARS; i++) {
            final long start = first + ((long) i * width);
            bars.add(new HistoryBar(start, (start + width) - 1, (long) counted.get(i).size(), false));
        }
        return bars;
    }

    // When the learner took a change in, or null for anything that is not a change. The plot runs on
    // that clock because the list does, and because it is the only order a model can be wound back
    // along.
    private static Long appliedAt(final HistoryRow row) {
        if (row instanceof final PathwayMutation mutation && mutation.getTime() != null) {
            return mutation.getTime().toEpochMillis();
        }
        return null;
    }

    /**
     * How the table orders both kinds of row.
     *
     * <p>Sorting by time puts every difference before every change, because a model comes into being
     * with its first change and a difference describes a day before that. Within the differences the
     * day is the order; within the changes it is the sequence, because every change one trace made
     * carries the same timestamp and ordering on that alone would shuffle changes that happened in a
     * definite order. Sorting by anything else puts the two kinds among each other on the value of
     * whichever column was picked, which is why they travel in one list.
     */
    private static Comparator<HistoryRow> rowComparator(final List<CriteriaFieldSort> sortList) {
        Comparator<HistoryRow> comparator = null;
        for (final CriteriaFieldSort sort : NullSafe.list(sortList)) {
            final Comparator<HistoryRow> field = rowComparator(sort.getId(), sort.isDesc());
            comparator = comparator == null
                    ? field
                    : comparator.thenComparing(field);
        }
        return comparator == null
                ? rowComparator(PathwayMutation.FIELD_TIME, true)
                : comparator;
    }

    private static Comparator<HistoryRow> rowComparator(final String field, final boolean descending) {
        final Comparator<HistoryRow> comparator =
                PathwayMutation.FIELD_TIME.equals(field) || PathwayMutation.FIELD_TRACE_TIME.equals(field)
                        ? Comparator.comparingLong(ShardedPathwayReader::inOrder)
                        : Comparator.comparing(row -> text(row, field),
                                Comparator.nullsFirst(Comparator.naturalOrder()));
        return descending
                ? comparator.reversed()
                : comparator;
    }

    // Where a row sits when the list is ordered by time. Differences first, by the day they describe;
    // changes after, by their place in the history. Kept apart by the offset rather than by comparing
    // a day against an instant, because the two are not on the same clock.
    private static long inOrder(final HistoryRow row) {
        return row instanceof final PathwayDifference difference
                ? Long.MIN_VALUE + difference.getDay()
                : ((PathwayMutation) row).getSequence();
    }

    private static String text(final HistoryRow row, final String field) {
        return switch (field) {
            case PathwayMutation.FIELD_TYPE -> row.getTypeDisplayValue();
            case PathwayMutation.FIELD_PATH -> String.join(" / ", NullSafe.list(row.getNodePath()));
            case PathwayMutation.FIELD_CONSTRAINT -> row.getConstraint();
            case PathwayMutation.FIELD_OLD_VALUE -> NullSafe.toString(row.getOldValue());
            case PathwayMutation.FIELD_NEW_VALUE -> NullSafe.toString(row.getNewValue());
            case PathwayMutation.FIELD_TRACE_ID -> row.getTraceId();
            case PathwayMutation.FIELD_SPAN_ID -> row.getSpanId();
            default -> null;
        };
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
        final Long asAtMs = latestIn(timeRange);
        // Read once and used for both the answer's moment and the weeks gathered below, so a reply
        // cannot say it describes one instant while having been built from another.
        final long nowMs = System.currentTimeMillis();
        final ByteBuffer prefix = TraceProcessor.observedValuesPrefix(keyBytes);
        final Pathway[] found = {null};
        final boolean[] folding = {false};
        final List<PathwayMutation> mutations = new ArrayList<>();
        final List<PathwayUsage> usage = new ArrayList<>();
        final DayRecord[] days = {DayRecord.empty()};
        // The day the model is folded from, which is the day asked for unless that day has nothing.
        final int[] foldDay = {0};
        readShard(doc, shard, db -> {
            withKey(name, keyBuffer -> db.getPathways().get(keyBuffer, valueBuffer -> {
                if (valueBuffer != null) {
                    found[0] = pathwaySerde.readPathway(valueBuffer);
                }
                return null;
            }));
            if (found[0] == null) {
                return null;
            }
            // Which of the two answers this date gets is settled while the shard is open, because
            // they want entirely different things out of it. Reading both would mean decoding every
            // change this model has ever made only to throw them away.
            folding[0] = asAtMs != null && toSequence == null && !reaches(found[0], asAtMs);
            if (folding[0]) {
                final int wanted = dayOf(asAtMs);
                final int windowStart = oldestDayOfWindow(doc, asAtMs);
                foldDay[0] = wanted;
                days[0] = daysIn(db, prefix, windowStart, wanted);
                if (days[0].nodes().isEmpty()) {
                    // Nothing in the window asked for. Rather than draw nothing, go back to the
                    // last day this pathway was seen at all and show that - still answering for the
                    // moment asked for, so every node is coloured by how long ago it really was.
                    //
                    // The index is what makes that cheap. Without it, finding that day means
                    // opening a bucket per week until one has something, and a pathway last seen a
                    // year ago is fifty weeks of copying down and decompressing to learn one date.
                    final OptionalInt lastSeen = lastIndexedDay(db, keyBytes, wanted);
                    if (lastSeen.isPresent() && lastSeen.getAsInt() < windowStart) {
                        foldDay[0] = lastSeen.getAsInt();
                        days[0] = daysIn(db, prefix,
                                oldestDayOfWindow(doc, endOfDay(foldDay[0])), foldDay[0]);
                    }
                }
            } else {
                mutations.addAll(mutationLog.read(db.getMutations(), keyBytes));
                usage.addAll(mutationLog.readUsage(db.getMutations(), keyBytes));
            }
            return null;
        });

        if (found[0] == null) {
            return Optional.empty();
        }
        if (folding[0]) {
            return Optional.of(folded(doc, shard, prefix, found[0], asAtMs, foldDay[0], days[0]));
        }
        // A wind-back needs every change between the moment asked for and now, and gathering them
        // opens a bucket per week in between - so a date from a year ago opens a year of them. That
        // is why the choice above is made first: where the history does not reach the date, none of
        // those changes can help.
        addArchived(doc, shard, keyBytes, asAtMs, nowMs, mutations, usage);
        return Optional.of(asAt(found[0], mutations, usage, asAtMs, toSequence, nowMs));
    }

    // The newest day at or before this one that this pathway has a record for, as far as the index
    // knows. The index covers days whose records have gone to a bucket, which are the ones nothing
    // else can answer cheaply; a day still in the shard is found by reading the shard.
    private static OptionalInt lastIndexedDay(final PathwaysDb db, final byte[] keyBytes, final int day) {
        final SimpleDb index = db.getDayIndex();
        if (index == null) {
            // A shard no writer has been through since the index was added. Nothing is marked, so
            // there is no day to go back to and the date answers as it did before.
            return OptionalInt.empty();
        }
        final ByteBuffer key = ByteBuffer.allocateDirect(keyBytes.length);
        key.put(keyBytes).flip();
        return index.get(key, value -> DayIndex.read(value).lastOnOrBefore(day));
    }

    // Whether the change history reaches a moment, which is the whole test for which of the two
    // answers a date gets. A pathway comes into being with its first change, so nothing before that
    // can be wound back to. The time is on the row already in hand and nothing ever moves it
    // backwards - a trace whose work ran before the window writes no change row and teaches no
    // model.
    //
    // No time at all means the row carries a name and nothing else, written so a pathway given only
    // old traces can be found. There is no history to wind back through, so every date is answered
    // by folding that date's days.
    private static boolean reaches(final Pathway pathway, final long asAtMs) {
        return Pathway.hasModel(pathway)
               && pathway.getCreateTime().toEpochMillis() <= asAtMs;
    }

    /**
     * The model a date gets where the change history does not reach it: built from what the window
     * ending on that date was given, rather than wound back from changes that do not exist.
     *
     * <p>It carries no changes and no count of them, because a folded node has never been through
     * the learner. The drawing sizes a node and picks its colour from how much it has changed, so
     * every node of a folded model comes out at its smallest and in the never-changed colour. Only
     * the links vary, because their width comes from use. That is what the differences between days
     * are for, and they are not built yet.
     *
     * @param asAtMs the moment answered for, which is what the drawing counts ages back from.
     * @param day    the day the model is folded from. The same day as {@code asAtMs} unless that
     *               day's window holds nothing, in which case it is the last day this pathway was
     *               seen at all - so the picture is of that day while still being coloured by how
     *               long ago it was.
     */
    private PathwayAsAt folded(final PathwaysDoc doc,
                               final int shard,
                               final ByteBuffer prefix,
                               final Pathway pathway,
                               final long asAtMs,
                               final int day,
                               final DayRecord fromTheShard) {
        final PathwayFold fold = new PathwayFold(DayRecord.merge(fromTheShard,
                bucketDays(doc, shard, prefix, oldestDayOfWindow(doc, endOfDay(day)), day)));
        final Pathway model = fold.model(pathway.getPathKey(), pathway.getName());

        // Every day record either answer needs, read once. The window's days say how much each node
        // changed shape, and every described day says what the drawing is laid out from.
        final int windowStart = oldestDayOfWindow(doc, endOfDay(day));
        final int firstDescribed = oldestArchivedDay(doc, shard);
        // The same for every day in the range, never the day being looked at. The tree below is
        // what the drawing is laid out from, and a tree that changed with the date would be a
        // different set of nodes at every step - which is a drawing built again rather than moved.
        //
        // Where no model came into being the day before it did says nothing, so the newest day the
        // buckets could hold anything for stands in. Read off their names, so it costs no more than
        // the oldest day already does and does not read every bucket up to now.
        final int lastDescribed = Pathway.hasModel(pathway)
                ? lastDayBeforeTheModel(pathway)
                : newestArchivedDay(doc, shard);
        final boolean described = firstDescribed <= lastDescribed;
        final int from = described
                ? Math.min(PathwayNarrower.oldestDayInWindow(doc, windowStart - 1), firstDescribed)
                : PathwayNarrower.oldestDayInWindow(doc, windowStart - 1);
        final int to = described
                ? Math.max(day, lastDescribed)
                : day;
        final Map<Integer, DayRecord> byDay = dayRecordsIn(doc, shard, prefix, from, to);

        final Map<String, NodeChange> changes = shapeChanges(doc, byDay, windowStart, day);
        // Scaled against its own window rather than against the learnt model's lifetime. The learnt
        // figures mean nothing here: a folded node has never been through the learner, so it has no
        // count of its own, and the uses a day record carries are that day's rather than every day's.
        return new PathwayAsAt(model,
                changes,
                fold.usage(),
                NanoTime.ofMillis(asAtMs),
                most(changes),
                mostUsed(model.getRoot()),
                true,
                described
                        ? union(byDay, firstDescribed, lastDescribed, pathway)
                        : null);
    }

    /**
     * How much each node of a folded model changed shape over the window it was folded from, and
     * when it last did.
     *
     * <p>The drawing sizes a node by how much it has changed and colours it by how long ago. A learnt
     * node carries both because the learner raised them as it went. A folded node went through
     * nothing, so they are worked out here by comparing each day in the window with the day before.
     *
     * <p>Named the way the folded model names its nodes, which is a stand-in worked out from the
     * span path. Keyed any other way the drawing finds nothing and comes out empty.
     */
    private static Map<String, NodeChange> shapeChanges(final PathwaysDoc doc,
                                                        final Map<Integer, DayRecord> byDay,
                                                        final int firstDay,
                                                        final int lastDay) {
        final Map<String, long[]> counted = new HashMap<>();
        final Map<String, NanoTime> moved = new HashMap<>();
        DayRecord before = foldFrom(doc, byDay, firstDay - 1);
        for (int day = firstDay; day <= lastDay; day++) {
            final DayRecord after = foldFrom(doc, byDay, day);
            for (final PathwayDifference difference : DayDifferences.between(before, after, day)) {
                if (!changedShape(difference.getType())) {
                    continue;
                }
                counted.computeIfAbsent(difference.getNodeUuid(), uuid -> new long[1])[0]++;
                if (difference.getRanAt() != null
                    && (moved.get(difference.getNodeUuid()) == null
                        || difference.getRanAt().toEpochMillis()
                           > moved.get(difference.getNodeUuid()).toEpochMillis())) {
                    moved.put(difference.getNodeUuid(), difference.getRanAt());
                }
            }
            before = after;
        }

        final Map<String, NodeChange> changes = new HashMap<>(counted.size());
        counted.forEach((uuid, count) ->
                changes.put(uuid, new NodeChange(count[0], moved.get(uuid))));
        return changes;
    }

    /**
     * Every node any described day has, as one model.
     *
     * <p>What the drawing is laid out from. A folded model holds only the nodes its own day was
     * given, so laying each day out from itself means every step draws a different set of nodes and
     * the drawing is built afresh rather than moved. Laid out from this, each day draws the same set
     * and a step changes how a node looks rather than where it is. A node a day did not have is
     * drawn as absent, which is what the learnt half already does for a node not yet learnt.
     */
    private static PathNode union(final Map<Integer, DayRecord> byDay,
                                  final int firstDay,
                                  final int lastDay,
                                  final Pathway pathway) {
        DayRecord all = DayRecord.empty();
        for (int day = firstDay; day <= lastDay; day++) {
            final DayRecord record = byDay.get(day);
            if (record != null) {
                all = DayRecord.merge(all, record);
            }
        }
        return new PathwayFold(all).model(pathway.getPathKey(), pathway.getName()).getRoot();
    }

    /**
     * Whether a difference says the work itself changed, rather than a bound drifting.
     *
     * <p>A node is sized by these alone. Timing bounds move on nearly every node every day simply
     * because runs vary - three quarters of everything the learner writes is that - so counting them
     * would give every node much the same figure and the sizes would say nothing. What is left is a
     * node or a constraint arriving or going, a value changing kind, and a constraint starting or
     * stopping being allowed to be absent.
     */
    private static boolean changedShape(final DifferenceType type) {
        return switch (type) {
            case NODE_ARRIVED, NODE_LEFT, CONSTRAINT_ARRIVED, CONSTRAINT_LEFT, TYPE_CHANGED,
                 BECAME_OPTIONAL, BECAME_REQUIRED -> true;
            case WIDENED, NARROWED, SHIFTED -> false;
        };
    }

    private static long most(final Map<String, NodeChange> changes) {
        long most = 0L;
        for (final NodeChange change : changes.values()) {
            most = Math.max(most, change.getCount());
        }
        return most;
    }

    // Every day record of one pathway over a span, from wherever it lives, keyed by its day. Two days
    // can hold a record apiece in the shard and in a bucket where a narrowing was interrupted between
    // storing and dropping, so they are merged rather than one replacing the other.
    private Map<Integer, DayRecord> dayRecordsIn(final PathwaysDoc doc,
                                                 final int shard,
                                                 final ByteBuffer prefix,
                                                 final int fromDay,
                                                 final int toDay) {
        final Map<Integer, DayRecord> byDay = new HashMap<>();
        readShard(doc, shard, db -> {
            eachRecord(db, prefix, fromDay, toDay, byDay);
            return null;
        });
        eachWeekIn(doc, shard, startOfDay(fromDay), endOfDay(toDay), db ->
                eachRecord(db, prefix, fromDay, toDay, byDay));
        return byDay;
    }

    private void eachRecord(final PathwaysDb db,
                            final ByteBuffer prefix,
                            final int fromDay,
                            final int toDay,
                            final Map<Integer, DayRecord> byDay) {
        db.getObservedValues().iteratePrefix(prefix.duplicate(), (key, value) -> {
            final int day = TraceProcessor.dayOf(key);
            if (day >= fromDay && day <= toDay) {
                byDay.merge(day, pathwaySerde.readObservedValues(value), DayRecord::merge);
            }
        });
    }

    // The model for one day, out of records already in hand. The same window the narrowing measures
    // by, so a day aged out under one and folded under the other cannot disagree.
    private static DayRecord foldFrom(final PathwaysDoc doc,
                                      final Map<Integer, DayRecord> byDay,
                                      final int day) {
        DayRecord folded = DayRecord.empty();
        for (int d = PathwayNarrower.oldestDayInWindow(doc, day); d <= day; d++) {
            final DayRecord record = byDay.get(d);
            if (record != null) {
                folded = DayRecord.merge(folded, record);
            }
        }
        return folded;
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
    //
    // Now is handed in rather than read here, and is the same reading the answer's own moment is
    // taken from, so a reply cannot say it describes one instant while having been gathered up to
    // another.
    private void addArchived(final PathwaysDoc doc,
                             final int shard,
                             final byte[] keyBytes,
                             final Long asAtMs,
                             final long nowMs,
                             final List<PathwayMutation> mutations,
                             final List<PathwayUsage> usage) {
        if (asAtMs == null) {
            // The model as it stands, so there is nothing to wind back through.
            return;
        }
        final List<PathwayMutation> archivedChanges = new ArrayList<>();
        final List<PathwayUsage> archivedUsage = new ArrayList<>();
        eachWeekIn(doc, shard, asAtMs, nowMs, db -> {
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
    static Long lastChangeBy(final List<PathwayMutation> mutations, final long asAtMs) {
        Long last = null;
        for (final PathwayMutation mutation : mutations) {
            final NanoTime time = mutation.getTime();
            if (time != null
                && time.toEpochMillis() <= asAtMs
                && (last == null || mutation.getSequence() > last)) {
                last = mutation.getSequence();
            }
        }
        return last;
    }

    /**
     * One pathway as it stood at one moment, with the figures a drawing of it needs.
     *
     * @param asAtMs  the moment to wind back to, or null for the model as it stands.
     * @param nowMs   the server's clock, which is the moment answered for when none was asked for.
     *                Taken here rather than read from the clock inside, so the moment the answer
     *                carries is one the caller stated.
     */
    static PathwayAsAt asAt(final Pathway pathway,
                            final List<PathwayMutation> mutations,
                            final List<PathwayUsage> usage,
                            final Long asAtMs,
                            final Long toSequence,
                            final long nowMs) {
        // Taken before anything is wound back, because the scale is what the pathway has ever done
        // rather than what it had done by the moment being shown.
        final long changeCeiling = mostChanged(pathway.getRoot());
        final long usageCeiling = mostUsed(pathway.getRoot());

        // Every answer says which instant it describes, so the drawing measures how long ago each
        // node changed against a time this machine produced rather than against the browser's own
        // clock. The two never agree exactly, and the times being measured are this machine's.
        final NanoTime moment = NanoTime.ofMillis(asAtMs == null
                ? nowMs
                : asAtMs);

        // A particular change, picked rather than searched for: stepping through the history asks
        // for them one at a time and every change one trace made carries the same timestamp.
        if (toSequence != null) {
            // When that change was taken in. A change nobody can find falls back to the moment
            // asked for, so the answer still says which instant it describes.
            final NanoTime taken = timeOf(mutations, toSequence);
            return woundTo(pathway, mutations, usage, toSequence,
                    taken == null
                            ? moment
                            : taken,
                    changeCeiling, usageCeiling);
        }

        if (asAtMs == null) {
            return new PathwayAsAt(
                    pathway,
                    changesByUuid(pathway, MutationCounts.of(mutations, 0L), moment),
                    Map.of(),
                    moment,
                    changeCeiling,
                    usageCeiling,
                    false,
                    null);
        }

        final Long lastChange = lastChangeBy(mutations, asAtMs);
        if (lastChange == null && reaches(pathway, asAtMs)) {
            // The model was there by then, but none of the changes it had made by then are in hand:
            // they have moved out of the shard, and only the weeks after the moment are read. Every
            // change in hand therefore came later, so all of them come off.
            return undoneInHand(pathway, mutations, moment, changeCeiling, usageCeiling);
        }
        if (lastChange == null) {
            // The learner had taken nothing in by then, so there is nothing to wind back through.
            // Folding the day records is the other answer, and the caller takes that route before
            // reaching here by asking the model when it came into being. Getting here means it could
            // not say, which is a model written before it recorded that.
            return new PathwayAsAt(
                    pathway.copy().root(null).build(),
                    Map.of(),
                    Map.of(),
                    moment,
                    changeCeiling,
                    usageCeiling,
                    false,
                    // Every node has come off, so the pathway's own tree is what says where they
                    // all used to sit. Sent for the same reason a wound-back model sends one.
                    pathway.getRoot());
        }

        // Nothing to undo means the time asked for is now, however the range was written - so the
        // model as it stands is the answer, and each node's own count of how busy it is goes with it.
        //
        // The readings below must not be used for it. A reading says how busy each node was when it
        // was taken, and one is only taken when the model learns something; a node gets busier every
        // time a trace passes through it. So a reading is usually behind, and using one here would
        // draw thinner lines for a time that is not past at all.
        final List<PathwayMutation> later = after(mutations, lastChange);
        if (later.isEmpty()) {
            return new PathwayAsAt(
                    pathway,
                    changesByUuid(pathway, MutationCounts.of(mutations, lastChange), moment),
                    Map.of(),
                    moment,
                    changeCeiling,
                    usageCeiling,
                    false,
                    null);
        }

        return woundTo(pathway, mutations, usage, lastChange, moment, changeCeiling, usageCeiling);
    }

    // The model with every change in hand taken off, which with none in hand is the model as it
    // stands. None of those changes had been made by the moment answered for, so none is counted;
    // each node says for itself how much it had changed by then, where it can.
    private static PathwayAsAt undoneInHand(final Pathway pathway,
                                            final List<PathwayMutation> mutations,
                                            final NanoTime moment,
                                            final long changeCeiling,
                                            final long usageCeiling) {
        final MutationCounts noneCounted = MutationCounts.of(List.of(), 0L);
        if (mutations.isEmpty()) {
            return new PathwayAsAt(pathway, changesByUuid(pathway, noneCounted, moment), Map.of(),
                    moment, changeCeiling, usageCeiling, false, null);
        }
        final Pathway shown = wound(pathway, mutations);
        return new PathwayAsAt(shown, changesByUuid(shown, noneCounted, moment), Map.of(),
                moment, changeCeiling, usageCeiling, false, pathway.getRoot());
    }

    // The model as it stood just after one change, with the figures a drawing of it needs. Both ways
    // in end here - by change number for a step through the history, by moment for a date - and they
    // differ only in which change they wind back to and what moment the answer describes.
    //
    // Nodes have come off, so the answer carries the pathway's own tree as the layout: it is what
    // says where the missing ones used to sit, and holding them open is what keeps the rest of the
    // drawing still.
    private static PathwayAsAt woundTo(final Pathway pathway,
                                       final List<PathwayMutation> mutations,
                                       final List<PathwayUsage> usage,
                                       final long sequence,
                                       final NanoTime moment,
                                       final long changeCeiling,
                                       final long usageCeiling) {
        final Pathway shown = wound(pathway, after(mutations, sequence));
        return new PathwayAsAt(
                shown,
                changesByUuid(shown, MutationCounts.of(mutations, sequence), moment),
                usageByUuid(usage, sequence),
                moment,
                changeCeiling,
                usageCeiling,
                false,
                pathway.getRoot());
    }

    // Whether a change is one the plot is being counted over. A bar's height is a count of traces,
    // and the table keeps a trace in the list when any one of its changes touched the node, so
    // counting the changes that touched it puts the trace in the same bar either way - every change
    // one trace made carries the one time, so they all fall in the same slice.
    private static boolean touches(final HistoryRow row, final String plotNode) {
        return plotNode == null
               || (row instanceof final PathwayMutation change
                   && plotNode.equals(change.getNodeUuid()));
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

    private static List<PathwayMutation> after(final List<PathwayMutation> mutations,
                                               final long lastChange) {
        final List<PathwayMutation> later = new ArrayList<>();
        for (final PathwayMutation mutation : mutations) {
            if (mutation.getSequence() > lastChange) {
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
    //
    // A node whose last change came at or before the moment says exactly how much it had changed by
    // then and when it last did, so its own figures are used. Only a node changed since has to be
    // counted from the change rows, which reach back no further than the narrowing window - the
    // older ones are in the weekly buckets.
    private static Map<String, NodeChange> changesByUuid(final Pathway shown,
                                                         final MutationCounts counts,
                                                         final NanoTime moment) {
        final Map<String, NodeChange> byUuid = new HashMap<>();
        collectChanges(shown.getRoot(), counts, moment.toEpochMillis(), byUuid);
        return byUuid;
    }

    private static void collectChanges(final PathNode node,
                                       final MutationCounts counts,
                                       final long momentMs,
                                       final Map<String, NodeChange> byUuid) {
        if (node == null) {
            return;
        }
        final NanoTime lastChanged = node.getLastChangedTime();
        final NodeChange change = lastChanged != null && lastChanged.toEpochMillis() <= momentMs
                // At least one: a node the narrowing alone has changed has a time and no tally, and
                // the change rows count a narrowing as one.
                ? new NodeChange(Math.max(1L, node.getTimesChanged()), lastChanged)
                : counts.node(node.getNodePath());
        if (change != null) {
            byUuid.put(node.getUuid(), change);
        }
        NullSafe.list(node.getChildren()).forEach(child -> collectChanges(child, counts, momentMs, byUuid));
    }

    // The reading kept at the moment being shown, which is the earliest taken at or after the change
    // picked. Empty for the model as it stands, where each node's own tally is what the drawing reads.
    private static Map<String, NodeUsage> usageByUuid(final List<PathwayUsage> usage,
                                                      final long lastChange) {
        final Map<String, NodeUsage> byUuid = new HashMap<>();
        PathwayUsage reading = null;
        for (final PathwayUsage candidate : usage) {
            if (candidate.getSequence() >= lastChange
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

    /**
     * One store's days for the window.
     *
     * <p>A day record starts life in the live shard and moves to the bucket for its week once the
     * narrowing window has passed it. So a window ending on a recent day is all in the shard, one
     * ending on an old day is all in buckets, and one ending near the move is split across both.
     * They hold them the same way under the same keys, so one reading serves all three.
     */
    private DayRecord daysIn(final PathwaysDb db,
                             final ByteBuffer prefix,
                             final int oldestDay,
                             final int newestDay) {
        final DayRecord[] folded = {DayRecord.empty()};
        db.getObservedValues().iteratePrefix(prefix.duplicate(), (key, value) -> {
            // The day is in the key, so one outside the window costs a comparison rather than a
            // record read.
            final int day = TraceProcessor.dayOf(key);
            if (day >= oldestDay && day <= newestDay) {
                folded[0] = DayRecord.merge(folded[0], pathwaySerde.readObservedValues(value));
            }
        });
        return folded[0];
    }

    private DayRecord bucketDays(final PathwaysDoc doc,
                                 final int shard,
                                 final ByteBuffer prefix,
                                 final int oldestDay,
                                 final int newestDay) {
        final DayRecord[] folded = {DayRecord.empty()};
        eachWeekIn(doc, shard, startOfDay(oldestDay), endOfDay(newestDay), db ->
                folded[0] = DayRecord.merge(folded[0], daysIn(db, prefix, oldestDay, newestDay)));
        return folded[0];
    }

    private static int oldestDayOfWindow(final PathwaysDoc doc, final long asAtMs) {
        return PathwayNarrower.oldestDayInWindow(doc, dayOf(asAtMs));
    }

    // The end of a range left open is Long.MAX_VALUE, which is more days than an int can hold. Left
    // to wrap it comes out as a large negative day, and a range that asked for everything describes
    // nothing. So the ends of the count stay at the ends.
    private static int dayOf(final long millis) {
        final long day = millis / TraceProcessor.MILLIS_PER_DAY;
        return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, day));
    }

    private static long startOfDay(final int day) {
        return day * TraceProcessor.MILLIS_PER_DAY;
    }

    private static long endOfDay(final int day) {
        return ((day + 1L) * TraceProcessor.MILLIS_PER_DAY) - 1L;
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
