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

import stroom.cell.expander.client.ExpanderCell;
import stroom.config.global.client.presenter.ListDataProvider;
import stroom.data.client.presenter.ColumnSizeConstants;
import stroom.data.client.presenter.HasContextMenusCell;
import stroom.data.grid.client.MyDataGrid;
import stroom.data.grid.client.PagerView;
import stroom.entity.client.presenter.TreeRowHandler;
import stroom.pathways.shared.otel.trace.NanoTime;
import stroom.pathways.shared.pathway.AbstractRange;
import stroom.pathways.shared.pathway.ConstraintValue;
import stroom.pathways.shared.pathway.HistoryBar;
import stroom.pathways.shared.pathway.HistoryRow;
import stroom.pathways.shared.pathway.MutationCounts;
import stroom.pathways.shared.pathway.MutationType;
import stroom.pathways.shared.pathway.PathNode;
import stroom.pathways.shared.pathway.PathwayDifference;
import stroom.pathways.shared.pathway.PathwayMutation;
import stroom.preferences.client.DateTimeFormatter;
import stroom.svg.client.SvgPresets;
import stroom.svg.shared.SvgImage;
import stroom.util.client.DataGridUtil;
import stroom.util.shared.Expander;
import stroom.util.shared.NullSafe;
import stroom.widget.button.client.ButtonView;
import stroom.widget.button.client.InlineSvgToggleButton;
import stroom.widget.util.client.MultiSelectionModelImpl;

import com.google.gwt.dom.client.Document;
import com.google.gwt.dom.client.Element;
import com.google.gwt.user.cellview.client.Column;
import com.google.gwt.user.client.ui.InsertPanel;
import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;
import com.gwtplatform.mvp.client.MyPresenterWidget;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * What each trace changed in the learnt model, newest first.
 *
 * <p>The model shows what a pathway ended up as; this shows how it got there — which trace taught it
 * what, and what each constraint held before it moved.
 */
public class PathwayMutationListPresenter extends MyPresenterWidget<PagerView> {

    // An OpenTelemetry id is a fixed length — 16 bytes for a trace and 8 for a span, written as hex —
    // so these columns are given a width that holds one rather than being measured against the rows.
    // A span id shows only on a change, and every trace starts closed, so measuring would find nothing
    // there to measure. The change names come from a closed set, the longest being "Constraint
    // Optional".
    private static final int TRACE_ID_COL = 270;
    private static final int SPAN_ID_COL = 150;
    private static final int CHANGE_COL = 170;

    private final PagerView pagerView;
    private final DateTimeFormatter dateTimeFormatter;
    private final TraceNavigation traceNavigation;
    private final MyDataGrid<MutationRow> dataGrid;
    private final MultiSelectionModelImpl<MutationRow> selectionModel;

    private final ListDataProvider<MutationRow> dataProvider;
    private final MutationTreeAction treeAction = new MutationTreeAction();
    private Column<MutationRow, String> timeColumn;
    private Column<MutationRow, Expander> expanderColumn;
    // The whole history, which the rows are built from every time the list is drawn. Held apart from
    // the rows because a trace opening or closing changes the rows and not the history.
    private List<HistoryRow> mutations = new ArrayList<>();
    // Where the traces that taught this model are kept, or null where nothing keeps them.
    private List<String> sources = List.of();
    // What the button says when there is a filter to take off. It says nothing else, because it is
    // only there when there is something to undo — the filtering itself is asked for on the drawing,
    // over the node it is about.
    private static final String FILTER_ON_TITLE = "Show every change";
    private static final String FILTER_OFF_TITLE = "Right click a node on the drawing to filter";

    private final InlineSvgToggleButton filterButton;
    private final ButtonView expandAllButton;
    private Filter filter = Filter.OFF;
    // The node picked out on the drawing, by uuid, or null where none is.
    private String selectedNode;
    private final ButtonView collapseAllButton;
    // The same strip the traces list puts over its rows, here over the changes. Built from the history
    // already in hand rather than asked for, so it costs nothing to keep beside them.
    private final HistogramWidget histogram;

    @Inject
    public PathwayMutationListPresenter(final EventBus eventBus,
                                        final PagerView view,
                                        final DateTimeFormatter dateTimeFormatter,
                                        final TraceNavigation traceNavigation) {
        super(eventBus, view);
        this.pagerView = view;
        this.dateTimeFormatter = dateTimeFormatter;
        this.traceNavigation = traceNavigation;

        dataGrid = new MyDataGrid<>(this);
        selectionModel = dataGrid.addDefaultSelectionModel(true);
        pagerView.setDataWidget(dataGrid);

        // Held here rather than fetched a page at a time: the view around this one already has the
        // whole history so it can wind the model back without asking the server, and this pages
        // through that same copy so the two cannot disagree.
        dataProvider = new ListDataProvider<>();
        dataProvider.addDataDisplay(dataGrid);

        // Narrows the table to the changes made to the node being looked at. On this toolbar rather
        // than on the drawing because it is this table it changes, and the node it works from is
        // picked out on the drawing either way.
        filterButton = new InlineSvgToggleButton();
        filterButton.setSvg(SvgImage.FILTER);
        filterButton.setTitle(FILTER_OFF_TITLE);
        // Off and out of reach until there is a filter on. Narrowing is asked for from the drawing,
        // where the node being narrowed to is the one under the pointer; all this does is undo it,
        // which is nothing to offer while nothing has been done.
        filterButton.setEnabled(false);
        pagerView.addButton(filterButton);

        expandAllButton = pagerView.addButton(SvgPresets.EXPAND_ALL);
        collapseAllButton = pagerView.addButton(SvgPresets.COLLAPSE_ALL);

        histogram = new HistogramWidget(dateTimeFormatter);
        histogram.setEmptyText("Nothing has changed this model yet");
        // The bars cover everything that has ever taught this model, which runs to days — so the time
        // on its own would not say which day either end of it was.
        histogram.setShowDate(true);
        // Dragging across the bars walks the model through its own history, which is the same thing
        // the play button does and the same thing clicking down the rows does — by hand, and as fast
        // or as slowly as the reader likes.
        histogram.setScrubHandler(this::selectNearest);
        // Over the rows, the way the traces list puts one over its own. The pager view takes only a
        // grid as its data widget, so this goes into the same box in front of it, and that box is made
        // a column so the strip keeps its height and the rows take what is left.
        if (dataGrid.getParent() instanceof final InsertPanel above) {
            dataGrid.getParent().addStyleName("dock-container-vertical");
            histogram.addStyleName("dock-min");
            dataGrid.addStyleName("dock-max");
            above.insert(histogram, 0);
        }

        addColumns();
        // Sizes the expander column to the depth on show, which is what keeps the indent from
        // taking a fixed slice of the width whether anything is open or not.
        dataProvider.setTreeRowHandler(new TreeRowHandler<>(treeAction, dataGrid, expanderColumn));
    }

    // The traces that taught the model something, counted into equal slices of the time they cover.
    // Traces rather than changes: one trace can move a dozen constraints at once, and counting those
    // would say the model was busy when all that happened was one trace arriving with a lot in it.
    // The same thing the rows say, which open as one row per trace.
    //
    // Worked out here rather than asked for, because the whole history is already held for the model
    // to be wound back through it.
    // The span the rows were asked for, resolved by the server because a range is picked as
    // expressions and only it can read those. Null where an end was left open.
    private Long windowFrom;
    private Long windowTo;
    // The plot over the rows, as the server counted it.
    private List<HistoryBar> bars = List.of();

    /**
     * The change rows among what the table is showing.
     *
     * <p>The plot and the selection are both about what the learner did, and a difference is not
     * that: it is two days compared, with no trace behind it and no place in the numbered history.
     * Differences reach the plot in their own right, as bars a day wide, which is not built yet.
     */
    private List<PathwayMutation> changes() {
        final List<PathwayMutation> changes = new ArrayList<>(mutations.size());
        for (final HistoryRow row : mutations) {
            if (row instanceof final PathwayMutation change) {
                changes.add(change);
            }
        }
        return changes;
    }

    /**
     * The trace stores this pathway has learnt from, by position. A row names its store by position
     * in this list rather than carrying a uuid of its own, and a row learnt from a store that has
     * since been pointed elsewhere still names the one it came from.
     */
    public void setSources(final List<String> sources) {
        this.sources = NullSafe.list(sources);
    }

    @Override
    protected void onBind() {
        super.onBind();
        registerHandler(filterButton.addClickHandler(e -> {
            // Only reachable while something is filtered, so the only thing it can mean is stop.
            setFilter(Filter.OFF);
        }));
        registerHandler(expandAllButton.addClickHandler(event -> {
            treeAction.expandAll(groups());
            order();
        }));
        registerHandler(collapseAllButton.addClickHandler(event -> {
            treeAction.collapseAll();
            order();
        }));

        // The bar holding whatever row is being looked at, picked out so the two read together: the
        // row says which trace, the bar says where in the model's life it landed. A change opened
        // under a trace falls in the same bar as the trace itself, which is what it should do.
        // The change event rather than the selection event, because the two tabs clear each other's
        // tables without telling anyone — the switch draws the model itself rather than being the
        // third of three to ask for it — and only this one is raised either way. A line left standing
        // after the table under it emptied would be pointing at a row that is no longer picked.
        registerHandler(selectionModel.addSelectionChangeHandler(e ->
                histogram.setSelectedTime(selectedMs())));
    }

    // The trace nearest the moment scrubbed to. Nearest rather than the one before it, so dragging to
    // the far left lands on the first trace rather than on nothing.
    private void selectNearest(final Long ms) {
        if (ms == null) {
            return;
        }
        MutationRow nearest = null;
        long best = Long.MAX_VALUE;
        long latest = Long.MIN_VALUE;
        for (final MutationRow row : dataProvider.getList()) {
            if (row.isParent() && row.getTime() != null) {
                final long at = row.getTime().toEpochMillis();
                latest = Math.max(latest, at);
                final long away = Math.abs(at - ms);
                if (away < best) {
                    best = away;
                    nearest = row;
                }
            }
        }

        // The plot runs to now, and the stretch past the last change is time in which nothing happened
        // to the model. There is no change to be looking at there, so nothing is picked and the model
        // is shown as it stands — which is what the tab opens on. Holding the last change instead would
        // say the reader was looking at a moment they had dragged well past.
        if (ms > latest) {
            if (selectionModel.getSelected() != null) {
                releaseKeyboard();
                selectionModel.clear();
            }
            return;
        }
        // Only where it has moved. A drag reports every pixel it crosses, and each of those lands on
        // the same trace many times over — telling the view around this one each time would wind the
        // model back to where it already is, over and over, while the reader is still dragging.
        if (nearest != null && !Objects.equals(nearest, selectionModel.getSelected())) {
            releaseKeyboard();
            selectionModel.setSelected(nearest);
        }
    }

    private Long selectedMs() {
        final NanoTime time = NullSafe.get(selectionModel.getSelected(), MutationRow::getTime);
        return time == null
                ? null
                : time.toEpochMillis();
    }

    /**
     * The row being looked at. Held onto by the view around this one so it knows when to redraw.
     */
    public MultiSelectionModelImpl<MutationRow> getSelectionModel() {
        return selectionModel;
    }

    /**
     * Which nodes the selected row changed, as paths. A trace answers with everything it touched,
     * which is the whole point of picking one rather than picking through its changes.
     */
    public List<List<String>> getSelectedNodePaths() {
        final MutationRow selected = selectionModel.getSelected();
        final List<List<String>> paths = new ArrayList<>();
        if (selected == null) {
            return paths;
        }

        // Nothing is picked out for the trace the pathway was created on. Those nodes were learnt on
        // that trace rather than changed by it, so marking them would say a change had been made
        // where the count beside them says none was.
        final String creating = MutationCounts.creatingTrace(changes());
        if (creating != null && creating.equals(selected.getTraceId())) {
            return paths;
        }

        for (final PathwayMutation mutation : changes()) {
            final boolean mine = selected.isParent()
                    ? Objects.equals(selected.getGroup(), PathwayMutation.groupOf(mutation))
                    : mutation.getSequence() == selected.getSequence();
            if (mine && !paths.contains(mutation.getNodePath())) {
                paths.add(mutation.getNodePath());
            }
        }
        return paths;
    }

    /**
     * The constraints of one node that the selected row changed, so they can be picked out beside the
     * node that was. A trace row gives everything that trace did to the node; a single row gives the
     * one constraint it names.
     *
     * @param path the node being shown, or null where none is.
     * @return the constraint names, empty where the selection changed nothing of that node.
     */
    public Set<String> getSelectedConstraints(final List<String> path) {
        final MutationRow selected = selectionModel.getSelected();
        final Set<String> names = new HashSet<>();
        if (selected == null || path == null) {
            return names;
        }

        // Nothing is picked out for the trace the pathway was created on, for the same reason the
        // nodes are not: what that trace did was the model being learnt rather than changed.
        final String creating = MutationCounts.creatingTrace(changes());
        if (creating != null && creating.equals(selected.getTraceId())) {
            return names;
        }

        for (final PathwayMutation mutation : changes()) {
            final boolean mine = selected.isParent()
                    ? Objects.equals(selected.getGroup(), PathwayMutation.groupOf(mutation))
                    : mutation.getSequence() == selected.getSequence();
            if (mine && mutation.getConstraint() != null && path.equals(mutation.getNodePath())) {
                names.add(mutation.getConstraint());
            }
        }
        return names;
    }

    /**
     * Moves the selection on to the next stop forward, so the model can be watched changing one step
     * at a time. Starts at the oldest where nothing is selected yet.
     *
     * <p>A stop is a trace or a whole day, never one of the rows beneath either. One trace is one
     * thing that happened to the model and its changes were all made at once. A day is one model, the
     * whole of that day folded, so there is nothing within it to stop at.
     *
     * @return whether there was another stop to move on to.
     */
    public boolean selectNextStop() {
        final List<MutationRow> rows = dataProvider.getList();
        if (NullSafe.isEmptyCollection(rows)) {
            return false;
        }

        // The next stop forward in time, not the next row down. Which way the list happens to be
        // sorted is how the reader wants to read it; it says nothing about which way a model grew, so
        // stepping follows the history rather than the order the rows are in. On a list newest first
        // that walks up it rather than down.
        //
        // By position rather than by sequence, because a day has no sequence. The days come first and
        // are walked one at a time, which is all a day offers: there is nothing within one to stop at,
        // the model for a day being the whole of it folded.
        final MutationRow selected = selectionModel.getSelected();
        final long after = selected == null
                ? Long.MIN_VALUE
                : selected.getPosition();

        MutationRow next = null;
        for (final MutationRow row : rows) {
            if (row.isParent()
                && row.getPosition() > after
                && (next == null || row.getPosition() < next.getPosition())) {
                next = row;
            }
        }
        if (next == null) {
            return false;
        }

        // The single-select form. Handed a flag instead, each step would add to the selection rather
        // than move it, and the list would fill up with everything stepped through.
        releaseKeyboard();
        selectionModel.setSelected(next);
        return true;
    }

    // Clicking a row focuses it so the table can then be worked by keyboard, and the browser draws
    // its own ring around whatever has focus. A selection made from the plot or the step buttons is
    // not the reader working the table, so the focus is given up rather than left on a row the
    // selection has moved off — which shows as a ring flickering from row to row. Clicking a row
    // takes it back.
    private void releaseKeyboard() {
        final Element focused = activeElement(Document.get());
        if (focused != null && dataGrid.getElement().isOrHasChild(focused)) {
            focused.blur();
        }
    }

    // Which element has focus. Not on Document in this version of GWT, and the one helper that has it
    // is not visible outside its own package.
    private static native Element activeElement(Document doc) /*-{
        return doc.activeElement;
    }-*/;

    /**
     * When the selected row happened, or null where nothing is selected. What the model is being
     * shown as at.
     */
    public NanoTime getSelectedTime() {
        return NullSafe.get(selectionModel.getSelected(), MutationRow::getTime);
    }

    /**
     * The point in the history being looked at, or null where there is none. A trace answers with the
     * last change it made, so selecting one shows the model as that trace left it. A day answers with
     * none, because the days described this way came before the model did.
     */
    public Long getSelectedSequence() {
        final MutationRow selected = selectionModel.getSelected();
        // Null for a day as well as for nothing picked. A day has no place in the history, so asking
        // to be shown the model just after it would wind every change there has ever been off the
        // model. Its date is what it asks by.
        return selected == null || selected.getSequence() == MutationRow.NO_SEQUENCE
                ? null
                : selected.getSequence();
    }

    /**
     * Shows the changes made to one pathway. Given rather than fetched: the view around this one
     * already holds the whole history so it can wind the model back without asking the server, and one
     * copy means the grid and the model on show cannot disagree.
     */
    public void setData(final List<HistoryRow> mutations) {
        setData(mutations, null, null, List.of());
    }

    /**
     * The same, with the span these rows were asked for and the plot over them.
     *
     * <p>The bars are worked out where the rows are, because they are of two widths and only that
     * end knows where the change history runs out - and because the rows are capped, so a plot
     * counted from them would quietly say less than the truth about a pathway with a long history.
     */
    public void setData(final List<HistoryRow> mutations,
                        final Long windowFrom,
                        final Long windowTo,
                        final List<HistoryBar> bars) {
        this.windowFrom = windowFrom;
        this.windowTo = windowTo;
        this.bars = NullSafe.list(bars);
        // Whatever was selected belonged to the list being replaced, and the view around this one asks
        // what is selected to decide which model to show.
        selectionModel.clear();

        // This is reused for every pathway opened, so which of the last one's traces were open, and
        // whatever it was narrowed to, are not carried over to the next.
        treeAction.collapseAll();
        selectedNode = null;
        setFilter(Filter.OFF);
        this.mutations = new ArrayList<>(NullSafe.list(mutations));
        order();
        histogram.setBars(bars);
        histogram.setSelectedTime(selectedMs());
    }

    // Newest first, always. The only order that means anything here is the one the changes were made
    // in, and a reader who turned it round would be looking at a model growing backwards — so the
    // columns offer nothing to sort by and this has nothing to read.
    /**
     * Which changes the table is showing: all of them, or only those made to the node picked out on
     * the drawing. There is no counterpart to the paths table's "avoiding" filter, because a change
     * is made to one node and the changes that were not made to it are not about it at all.
     */
    public enum Filter {
        OFF,
        SELECTED_NODE
    }

    /**
     * The node picked out on the drawing, which is what the filter works from.
     *
     * <p>Moving to another node takes the filter off rather than quietly aiming it somewhere else. A
     * filter that re-aimed itself would change what the table was showing while the reader was reading
     * it, and leave them no way of telling which node it had settled on.
     */
    public void setSelectedNode(final PathNode node) {
        final String uuid = NullSafe.get(node, PathNode::getUuid);
        if (!Objects.equals(uuid, selectedNode)) {
            selectedNode = uuid;
            if (filter != Filter.OFF) {
                setFilter(Filter.OFF);
            }
        }
    }

    /**
     * Narrows the table, or stops narrowing it. Asked for from the drawing as well as from the button
     * beside the table, so the button is brought into line with whatever was asked.
     */
    public void setFilter(final Filter filter) {
        this.filter = filter;
        final boolean on = filter != Filter.OFF && selectedNode != null;
        filterButton.setState(on);
        filterButton.setEnabled(on);
        filterButton.setTitle(on
                ? FILTER_ON_TITLE
                : FILTER_OFF_TITLE);
        order();
        // The plot is not touched. It covers the range, and the range has not changed - narrowing to
        // one node narrows the table beneath it. The bars are counted over every row the range
        // holds, which is more rows than are listed here, so they cannot be re-counted from what is
        // in hand anyway.
    }

    public Filter getFilter() {
        return filter;
    }

    // Whether a change is one the table is showing. With a filter on and nothing picked out there is
    // nothing to narrow by, so everything is shown rather than nothing.
    private boolean shown(final PathwayMutation mutation) {
        return filter == Filter.OFF
               || selectedNode == null
               || selectedNode.equals(mutation.getNodeUuid());
    }

    private void order() {
        dataProvider.setCompleteList(buildRows());
        updateButtons();
    }

    // The trace that made these changes, which is the one worth opening to see what it actually did.
    // Right clicking it offers to go there — nothing, where no traces store feeds this pathway.
    private void addTraceIdColumn() {
        final Column<MutationRow, String> column = new Column<MutationRow, String>(
                new HasContextMenusCell<String>((context, traceId) ->
                        traceNavigation.getMenuItems(this, sourceOf(traceId), traceId, whenMade(traceId))) {
                }) {
            @Override
            public String getValue(final MutationRow row) {
                return row.getTraceId();
            }
        };
        dataGrid.addResizableColumn(column, PathwayMutation.FIELD_TRACE_ID, TRACE_ID_COL);
    }

    // When the trace actually ran. Not the time beside it in the table: that is when the model learnt
    // from the trace, which is however long after the trace ran that it waited to be applied.
    // Which trace store a row's trace came from, resolved against the list the pathway carries. Null
    // where the row names none, or names one this pathway no longer holds — the menu then offers
    // nothing rather than opening a store that cannot answer.
    private String sourceOf(final String traceId) {
        final int index = indexOfSource(traceId);
        return index < 0 || index >= sources.size()
                ? null
                : sources.get(index);
    }

    private int indexOfSource(final String traceId) {
        for (final PathwayMutation mutation : changes()) {
            if (Objects.equals(traceId, mutation.getTraceId())) {
                return mutation.getSource();
            }
        }
        return -1;
    }

    private Long whenMade(final String traceId) {
        for (final PathwayMutation mutation : changes()) {
            if (Objects.equals(traceId, mutation.getTraceId())) {
                return NullSafe.get(mutation.getTraceTime(), NanoTime::toEpochMillis);
            }
        }
        return null;
    }

    // Nothing to open once everything is open, and nothing to close once everything is closed.
    private void updateButtons() {
        final Set<String> groups = groups();
        int open = 0;
        for (final String group : groups) {
            if (treeAction.isExpanded(group)) {
                open++;
            }
        }
        expandAllButton.setEnabled(open < groups.size());
        collapseAllButton.setEnabled(open > 0);
    }

    // First appearance first, so what the buttons act on is the order the list is built in. Days as
    // well as traces: a day that opening everything left shut is a row the button says it has
    // already opened.
    private Set<String> groups() {
        final Set<String> groups = new LinkedHashSet<>();
        for (final HistoryRow row : mutations) {
            if (row instanceof final PathwayMutation change) {
                groups.add(PathwayMutation.groupOf(change));
            }
        }
        for (final HistoryRow row : mutations) {
            if (row instanceof final PathwayDifference difference) {
                groups.add(MutationRow.groupOfDay(difference.getDay()));
            }
        }
        return groups;
    }

    /**
     * A row per thing the rows beneath it belong to: a trace, one run of the narrowing, or a day.
     *
     * <p>Everything a trace taught the model is written in one go, so its changes are always next to
     * each other in the history and grouping them is a walk rather than a sort. They are grouped in
     * the order they were made and the result turned round afterwards, so which way the list is
     * sorted cannot split one in two.
     *
     * <p>Days come last, because every one of them is older than the first change - a model comes
     * into being with that change, and a day is only described this way where there is none - and
     * this list is newest first.
     */
    private List<MutationRow> buildRows() {
        final List<MutationRow> rows = new ArrayList<>();
        final List<PathwayMutation> oldestFirst = new ArrayList<>(changes());
        oldestFirst.sort(PathwayMutation.comparator(PathwayMutation.FIELD_TIME, false));

        final List<List<PathwayMutation>> groups = new ArrayList<>();
        List<PathwayMutation> current = null;
        for (final PathwayMutation mutation : oldestFirst) {
            if (current == null
                || !Objects.equals(PathwayMutation.groupOf(current.get(0)), PathwayMutation.groupOf(mutation))) {
                current = new ArrayList<>();
                groups.add(current);
            }
            current.add(mutation);
        }

        Collections.reverse(groups);

        for (final List<PathwayMutation> group : groups) {
            // A trace stands where any one of its changes is one the table is showing, and then shows
            // only those — so narrowing to a node never breaks a change away from the thing that made
            // it, and the trace row still names the change the trace finished on.
            if (group.stream().noneMatch(this::shown)) {
                continue;
            }
            // The last change it made, which is the model as it left it however the list is turned
            // round.
            final PathwayMutation last = group.get(group.size() - 1);
            final MutationRow groupRow = MutationRow.trace(last, group.get(0).getTime(),
                    treeAction.isExpanded(PathwayMutation.groupOf(last)));
            rows.add(groupRow);

            if (groupRow.getExpander().isExpanded()) {
                final List<PathwayMutation> changes = new ArrayList<>(group);
                Collections.reverse(changes);
                for (final PathwayMutation mutation : changes) {
                    if (shown(mutation)) {
                        rows.add(MutationRow.change(mutation));
                    }
                }
            }
        }
        rows.addAll(dayRows());
        return rows;
    }

    // The days that had no change history of their own, newest first like everything else, each
    // holding what moved between it and the day before.
    private List<MutationRow> dayRows() {
        final Map<Integer, List<PathwayDifference>> byDay = new TreeMap<>(Comparator.reverseOrder());
        for (final HistoryRow row : mutations) {
            if (row instanceof final PathwayDifference difference) {
                byDay.computeIfAbsent(difference.getDay(), day -> new ArrayList<>()).add(difference);
            }
        }

        final List<MutationRow> rows = new ArrayList<>();
        byDay.forEach((day, differences) -> {
            final boolean expanded = treeAction.isExpanded(MutationRow.groupOfDay(day));
            rows.add(MutationRow.day(day, expanded));
            if (expanded) {
                differences.forEach(difference -> rows.add(MutationRow.difference(difference)));
            }
        });
        return rows;
    }

    private void addColumns() {
        addExpanderColumn();

        // Both times, named, because they are not the same question and the list runs on one of them
        // while a reader is usually asking about the other. Applied is when Stroom learnt the change,
        // and is the order of the list, the clock the plot above it runs on, and what a picked range
        // selects, because it is the only order the model can be wound back along. Trace time is when
        // the trace itself ran. A trace loaded long after it ran therefore sits at the top of the list
        // with an old trace time, which is correct and is the point of the feature.
        //
        // Neither sorts on the times themselves: every change one trace made shares a timestamp, so
        // ordering on those alone would shuffle changes that happened in a definite order.
        timeColumn = DataGridUtil
                .textColumnBuilder((MutationRow row) -> NullSafe.get(row.getTime(),
                        value -> dateTimeFormatter.format(value.toEpochMillis())))
                .build();
        dataGrid.addResizableColumn(timeColumn, PathwayMutation.FIELD_TIME,
                ColumnSizeConstants.DATE_COL);
        dataGrid.addResizableColumn(DataGridUtil
                        .textColumnBuilder((MutationRow row) -> NullSafe.get(row.getRanAt(),
                                value -> dateTimeFormatter.format(value.toEpochMillis())))
                        .build(),
                PathwayMutation.FIELD_TRACE_TIME,
                ColumnSizeConstants.DATE_COL);

        // What the change belonged to comes before what it was: the list is grouped by trace, so the
        // trace is what a row is found by. Kept on the changes as well as on the trace they sit under,
        // so a row still says which trace made it when the list is copied out of the grid.
        addTraceIdColumn();
        addColumn(PathwayMutation.FIELD_SPAN_ID,
                row -> ofRow(row, HistoryRow::getSpanId),
                SPAN_ID_COL);

        addColumn(PathwayMutation.FIELD_PATH,
                row -> ofRow(row, value -> String.join(" / ", NullSafe.list(value.getNodePath()))),
                400);
        addColumn(PathwayMutation.FIELD_CONSTRAINT,
                row -> ofRow(row, HistoryRow::getConstraint),
                ColumnSizeConstants.MEDIUM_COL);
        addColumn(PathwayMutation.FIELD_TYPE,
                row -> ofRow(row, HistoryRow::getTypeDisplayValue),
                CHANGE_COL);
        addColumn(PathwayMutation.FIELD_OLD_VALUE,
                row -> ofRow(row, value -> text(value, value.getOldValue())),
                ColumnSizeConstants.MEDIUM_COL);
        addColumn(PathwayMutation.FIELD_NEW_VALUE,
                row -> ofRow(row, value -> text(value, value.getNewValue())),
                ColumnSizeConstants.MEDIUM_COL);
    }

    private void addExpanderColumn() {
        expanderColumn = new Column<MutationRow, Expander>(new ExpanderCell()) {
            @Override
            public Expander getValue(final MutationRow row) {
                return row.getExpander();
            }
        };
        expanderColumn.setFieldUpdater((index, row, value) -> {
            treeAction.setExpanded(row.getGroup(), !value.isExpanded());
            order();
        });
        dataGrid.addColumn(expanderColumn, "");
    }

    // The columns below the trace describe one change, so a trace leaves them empty rather than
    // repeating itself across a row that stands for many.
    private static String ofRow(final MutationRow row,
                                final Function<HistoryRow, String> value) {
        return row.isParent()
                ? ""
                : value.apply(row.getRow());
    }

    private Column<MutationRow, String> addColumn(final String name,
                                                  final Function<MutationRow, String> value,
                                                  final int width) {
        final Column<MutationRow, String> column = DataGridUtil
                .textColumnBuilder(value)
                .build();
        dataGrid.addResizableColumn(column, name, width);
        return column;
    }

    // A trace carries one value, so it can only push out one end of a range. Showing the whole range
    // reads as though the end that stayed put had moved as well. Where the constraint held a single
    // value before, that value was itself the end that moved, so it is shown as it stands.
    private static String text(final HistoryRow row, final ConstraintValue value) {
        // Only a change says which end of a range it moved, so only a change shows that end on its
        // own. A difference is two days compared and says what the whole bound was either side.
        final PathwayMutation mutation = row instanceof final PathwayMutation change
                ? change
                : null;
        if (mutation == null) {
            return NullSafe.toString(value);
        }
        return textOfChange(mutation, value);
    }

    private static String textOfChange(final PathwayMutation mutation, final ConstraintValue value) {
        if (value instanceof AbstractRange) {
            final AbstractRange<?> range = (AbstractRange<?>) value;
            final Object end;
            if (MutationType.CONSTRAINT_MIN_EXPANDED.equals(mutation.getType())) {
                end = range.getMin();
            } else if (MutationType.CONSTRAINT_MAX_EXPANDED.equals(mutation.getType())) {
                end = range.getMax();
            } else {
                end = null;
            }
            if (end != null) {
                return end.toString();
            }
        }
        return value == null
                ? ""
                : value.toString();
    }

}
