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

package stroom.pathways.client.presenter;

import stroom.alert.client.event.AlertEvent;
import stroom.alert.client.event.ConfirmEvent;
import stroom.content.client.presenter.ContentTabPresenter;
import stroom.core.client.event.CloseContentEvent;
import stroom.dispatch.client.DefaultErrorHandler;
import stroom.dispatch.client.RestFactory;
import stroom.docref.DocRef;
import stroom.pathways.client.presenter.PathwayEditPresenter.PathwayEditView;
import stroom.pathways.shared.EditConstraint;
import stroom.pathways.shared.FetchPathwayRequest;
import stroom.pathways.shared.FindPathwayMutationCriteria;
import stroom.pathways.shared.PathwayAsAt;
import stroom.pathways.shared.PathwaysDoc;
import stroom.pathways.shared.PathwaysResource;
import stroom.pathways.shared.RemoveConstraints;
import stroom.pathways.shared.RemoveNode;
import stroom.pathways.shared.RollbackNode;
import stroom.pathways.shared.otel.trace.NanoTime;
import stroom.pathways.shared.pathway.Constraint;
import stroom.pathways.shared.pathway.HistoryRow;
import stroom.pathways.shared.pathway.PathNode;
import stroom.pathways.shared.pathway.PathUse;
import stroom.pathways.shared.pathway.Paths;
import stroom.pathways.shared.pathway.Pathway;
import stroom.pathways.shared.pathway.PathwayMutation;
import stroom.query.api.TimeRange;
import stroom.query.api.TimeRanges;
import stroom.query.client.view.TimeRangeSelector;
import stroom.svg.client.SvgPresets;
import stroom.svg.shared.SvgImage;
import stroom.util.shared.CriteriaFieldSort;
import stroom.util.shared.NullSafe;
import stroom.util.shared.PageRequest;
import stroom.widget.button.client.ButtonView;
import stroom.widget.customdatebox.client.ClientDateUtil;
import stroom.widget.menu.client.presenter.IconMenuItem;
import stroom.widget.menu.client.presenter.Item;
import stroom.widget.menu.client.presenter.Separator;
import stroom.widget.menu.client.presenter.ShowMenuEvent;
import stroom.widget.popup.client.presenter.PopupPosition;
import stroom.widget.tab.client.presenter.TabData;
import stroom.widget.tab.client.presenter.TabDataImpl;
import stroom.widget.tab.client.view.LinkTabBar;

import com.google.gwt.core.client.GWT;
import com.google.gwt.core.client.Scheduler;
import com.google.gwt.user.client.Timer;
import com.google.gwt.user.client.ui.Widget;
import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;
import com.gwtplatform.mvp.client.LayerContainer;
import com.gwtplatform.mvp.client.View;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * One pathway open as a tab of its own. A tab rather than a dialog because reading a model is not a
 * question to be answered and dismissed — two can be held open side by side, and the drawing keeps
 * whatever the reader had scrolled to while they look at something else.
 */
public class PathwayEditPresenter
        extends ContentTabPresenter<PathwayEditView>
        implements CloseContentEvent.Handler {

    private static final PathwaysResource PATHWAYS_RESOURCE = GWT.create(PathwaysResource.class);

    /**
     * How much of a pathway's history is listed in the Changes table. A pathway's history is bounded
     * by what is novel rather than by traffic, so this is not expected to be reached.
     *
     * <p>It no longer bounds what can be drawn: the model for a moment is wound back by the server,
     * which reads the changes it needs there rather than being handed them.
     */
    private static final int MAX_HISTORY = 20000;
    private static final int STEP_MILLIS = 1500;
    /**
     * What the tab is called in the list of open tabs, and the type its synthetic doc ref carries.
     */
    private static final String TAB_TYPE = "Pathway";
    private static final double TREE_SHARE = 0.6;
    private static final double LOWER_SHARE = 0.4;

    private Pathway pathway;
    // What the two tables were showing when the drawing was last made. Everything the drawing takes
    // from either of them follows from the one row it has picked, so a row each says whether a
    // selection event would draw anything different. Written where the drawing is made rather than
    // where a row is clicked, so that a selection cleared without an event — which is how switching
    // tabs starts afresh — leaves nothing stale here to be compared against.
    private Long drawnChange;
    // The moment the drawing was asked for. Kept beside the change because a day has no change, so
    // two different days would otherwise read as the same position and the second would draw nothing.
    private NanoTime drawnAt;
    private PathUse drawnPath;
    private DocRef docRef;
    // The name the pathway was opened under, which is what changes to it are sent against. Held rather
    // than read off the model, so a tab can be labelled before the model it is waiting for arrives.
    private String name;
    // Told when a change to the model lands, so the list this was opened from can show the new size
    // and time.
    private Runnable changedHandler;
    // Whether the split between the drawing and the constraints has been set. Once only, so that
    // reading something else and coming back does not undo wherever the reader dragged it to.
    private boolean split;
    private List<HistoryRow> history = Collections.emptyList();
    // The span the tab is showing, and what the server sent back for it. One field rather than one
    // per reader, so the toolbar, the tables and the drawing cannot disagree about what is on screen.
    // Held as it was picked: a range is written as expressions, and resolving them happens where the
    // parser for them lives, which is not here.
    private TimeRange timeRange;
    private PathwayAsAt asAt;
    // What came back for the range with no change picked out. Kept so that letting go of a row does
    // not ask the server for something already in hand.
    private PathwayAsAt rangeAsAt;
    // What the reader had set up when the rows were last asked for again, to be put back once the
    // new ones are in: the row being looked at, the narrowing, and the node it is aimed at. All
    // nothing at every other time, so that opening a pathway starts clean however the last one was
    // left - which is also what a change of range wants, since it drops the narrowing by design.
    private MutationRow keptRow;
    private PathwayMutationListPresenter.Filter keptFilter;
    private PathNode keptNode;
    // True while rows are being handed over and whatever the reader was on is picked again, so that
    // the picking is not taken for the reader picking something.
    private boolean restoring;
    private final TimeRangeSelector timeRangeSelector = new TimeRangeSelector();
    // Whether each of the two things the drawing is made from has arrived. Both are asked for at once
    // and neither waits on the other, so which lands first is not known.
    private boolean historyArrived;
    private final PathwayTreePresenter pathwayTreePresenter;
    private final ConstraintListPresenter constraintListPresenter;
    private final NodeInfoPresenter nodeInfoPresenter;
    private final PathwayMutationListPresenter mutationListPresenter;
    private final PathwayPathListPresenter pathListPresenter;
    private final TabData changesTab = new TabDataImpl("Changes");
    private final TabData pathsTab = new TabDataImpl("Paths");
    private TabData selectedTab = changesTab;
    // The middle panel's own tabs, which say different things about one node rather than different
    // things about the pathway.
    private final TabData constraintsTab = new TabDataImpl("Constraints");
    private final TabData infoTab = new TabDataImpl("Node Info");
    // Whether a node is already waiting to be shown, so a run of selection changes draws once.
    private boolean nodePending;
    private final ButtonView playButton;
    private final ButtonView stopButton;
    private final Timer stepper = new Timer() {
        @Override
        public void run() {
            if (!mutationListPresenter.selectNextStop()) {
                // The end of the list. Nothing left to step to, so it stops rather than sitting there
                // firing at a selection that cannot move.
                stop();
            }
        }
    };
    private final RestFactory restFactory;
    private boolean readOnly = true;

    @Inject
    public PathwayEditPresenter(final EventBus eventBus,
                                final PathwayEditView view,
                                final PathwayTreePresenter pathwayTreePresenter,
                                final ConstraintListPresenter constraintListPresenter,
                                final NodeInfoPresenter nodeInfoPresenter,
                                final PathwayMutationListPresenter mutationListPresenter,
                                final PathwayPathListPresenter pathListPresenter,
                                final RestFactory restFactory) {
        super(eventBus, view);
        this.restFactory = restFactory;
        this.pathwayTreePresenter = pathwayTreePresenter;
        this.constraintListPresenter = constraintListPresenter;
        this.nodeInfoPresenter = nodeInfoPresenter;
        this.mutationListPresenter = mutationListPresenter;
        this.pathListPresenter = pathListPresenter;
        view.setTree(pathwayTreePresenter.getView());
        view.getNodeTabBar().addTab(constraintsTab);
        view.getNodeTabBar().addTab(infoTab);
        showNodeTab(constraintsTab);
        // One at a time rather than side by side. Both are wide tables of the whole pathway and
        // neither is read while the other is, so sharing the strip left each too narrow to read.
        view.getTabBar().addTab(changesTab);
        view.getTabBar().addTab(pathsTab);

        // At the far end of the tab's own toolbar, because it says what the whole tab is showing.
        view.addToolbarWidget(timeRangeSelector);

        // On the Changes toolbar, beside the buttons that open and close it. What they step through
        // is that list, so they sit with it rather than on the drawing they happen to animate.
        playButton = mutationListPresenter.getView().addButton(SvgPresets.RUN.title("Play"));
        stopButton = mutationListPresenter.getView().addButton(SvgPresets.STOP.title("Stop"));
        playButton.setVisible(false);
        stopButton.setVisible(false);
    }

    @Override
    protected void onBind() {
        super.onBind();
        // The end of the range picked is the moment the tab answers for. Asked of the server rather
        // than worked out here: the changes it is counted from are filed by the day their work ran,
        // so a date never promises an unbroken run of them.
        registerHandler(timeRangeSelector.addValueChangeHandler(e -> {
            // The row being looked at, to be put back once the new rows are in. Taken here rather
            // than in showOver, because opening a pathway goes through that too and must not carry
            // the row from whichever pathway was open before.
            //
            // The row, and the node picked out on the drawing. A different range is a different set
            // of changes, so the narrowing is let go rather than aimed at rows that were not asked
            // for. The node is kept because the drawing keeps it picked out without saying so again,
            // and a table that forgot it would have nothing to narrow to when asked.
            keptRow = mutationListPresenter.getSelectionModel().getSelected();
            keptFilter = null;
            keptNode = pathwayTreePresenter.getSelectionModel().getSelectedObject();
            showOver(e.getValue());
        }));

        // The whole tab asked for again over the range it is already showing - the model as well as
        // the rows, because the two are of one moment and refreshing one of them would leave a
        // drawing made from changes the table no longer lists.
        //
        // What the reader set up on the changes is kept: the narrowing, the node it is aimed at and
        // the row being looked at. A button that fetches the rows again must not also undo what they
        // had done to them.
        //
        // The paths come with the model, so the paths table's button asks for the same thing. That
        // table keeps its own narrowing when given the same pathway again, but not the path being
        // looked at.
        registerHandler(mutationListPresenter.getView().getRefreshButton().addClickHandler(e -> refresh()));
        registerHandler(pathListPresenter.getView().getRefreshButton().addClickHandler(e -> refresh()));
        // Adding, changing and removing a constraint are each sent to the server there and then.
        constraintListPresenter.setRemoveHandler(this::removeConstraints);
        constraintListPresenter.setEditHandler(this::editConstraint);
        registerHandler(playButton.addClickHandler(e -> play()));
        registerHandler(stopButton.addClickHandler(e -> stop()));

        // What a reader can ask of a node, offered where the node is. Built here rather than on the
        // drawing because what it offers narrows the table beside it or removes the node from the
        // model, and the drawing knows nothing of either.
        pathwayTreePresenter.setNodeMenuHandler(this::showNodeMenu);

        // Narrowing the table to one node narrows the plot over it too. Asked for rather than
        // counted here: the plot covers every row the range holds, and those are capped, so one
        // counted from the rows in hand would understate a pathway with a long history.
        mutationListPresenter.setPlotChangeHandler(this::fetchPlot);
        mutationListPresenter.setNodeMenu(this::changeNodeItems);
        mutationListPresenter.setConstraintMenu(this::changeConstraintItems);

        registerHandler(pathwayTreePresenter.getSelectionModel()
                .addSelectionChangeHandler(e -> {
                    // Both tables narrow themselves to the node picked out on the drawing, so both
                    // are told each time that moves.
                    final PathNode selectedNode =
                            pathwayTreePresenter.getSelectionModel().getSelectedObject();
                    pathListPresenter.setSelectedNode(selectedNode);
                    mutationListPresenter.setSelectedNode(selectedNode);
                    showConstraints();
                }));

        // Clicking the row that is already picked asks for nothing new. Drawing anyway would put the
        // same model back against a later clock, and a node is coloured by how long since it last
        // changed — so the whole drawing would come back older without anything having been learnt.
        registerHandler(mutationListPresenter.getSelectionModel().addSelectionHandler(e -> {
            if (restoring) {
                // Rows being handed over, which picks whatever the reader was on. The drawing is
                // decided once those have landed, so this would be a second answer to one event.
                return;
            }
            if (!Objects.equals(drawnChange, mutationListPresenter.getSelectedSequence())
                || !Objects.equals(drawnAt, mutationListPresenter.getSelectedTime())) {
                showAtPosition(mutationListPresenter.getSelectedTime(),
                        mutationListPresenter.getSelectedSequence());
            }
        }));
        registerHandler(pathListPresenter.getSelectionModel().addSelectionHandler(e -> {
            if (!Objects.equals(drawnPath, pathListPresenter.getSelectionModel().getSelected())) {
                showModel();
            }
        }));
        registerHandler(getView().getTabBar().addSelectionHandler(e -> showTab(e.getSelectedItem())));
        registerHandler(getView().getNodeTabBar()
                .addSelectionHandler(e -> showNodeTab(e.getSelectedItem())));
        registerHandler(getView().getTabBar().addShowMenuHandler(e -> getEventBus().fireEvent(e)));

        // Paths first: what the pathway actually does is what a reader opens it for, and the changes
        // are how it came to be that way.
        showTab(changesTab);

//        registerHandler(getView().getDetails().addClickHandler(e -> {
//            final Element target = e.getNativeEvent().getEventTarget().cast();
//            if (target != null) {
//                final Element node = ElementUtil.findParent(target, element ->
//                        NullSafe.isNonBlankString(element.getAttribute("uuid")), 3);
//                if (node != null) {
//                    final String uuid = node.getAttribute("uuid");
////                    AlertEvent.fireInfo(this, uuid, null);
//
//                    final PathNode pathNode = nodeMap.get(uuid);
//                    // Calculate min, max, average time.
//                    NanoTime min = NanoTime.ofSeconds(Long.MAX_VALUE);
//                    NanoTime max = NanoTime.ZERO;
//                    NanoTime sum = NanoTime.ZERO;
//                    final int count = pathNode.getSpans().size();
//
//                    final HtmlBuilder spanDetails = new HtmlBuilder();
//                    final CommonSpanBuilder commonSpanBuilder = new CommonSpanBuilder();
//                    for (final Span span : pathNode.getSpans()) {
//                        commonSpanBuilder.add(span);
//
//                        final NanoTime startTime = NanoTime.fromString(span.getStartTimeUnixNano());
//                        final NanoTime endTime = NanoTime.fromString(span.getEndTimeUnixNano());
//                        final NanoTime duration = endTime.diff(startTime);
//
//                        if (min.isGreaterThan(duration)) {
//                            min = duration;
//                        }
//                        if (max.isLessThan(duration)) {
//                            max = duration;
//                        }
//                        sum = sum.add(duration);
//                    }
//
//
//                    final Span commonSpan = commonSpanBuilder.build();
//                    final SafeHtml durations = getDurationsHtml(min, max, sum, count);
//                    spanDetails.div(div -> {
//                        div.append(durations);
//                    });
//                    spanDetails.div(div -> {
//                        div.append(getSpanHtml(commonSpan));
//                    });
//
////                    getView().setConstraints(HtmlBuilder.builder().div(d -> d.append(getConstraintsHtml(pathNode)),
////                            Attribute.className("pathway-constraints")).toSafeHtml());
//
//                    constraintListPresenter.setData(getConstraintList(pathNode), readOnly);
//
//
////                    getView().setSpans(HtmlBuilder.builder().div(d -> d.append(spanDetails.toSafeHtml()),
////                            Attribute.className("pathway-spans")).toSafeHtml());
//
//                    if (!Objects.equals(selected, node)) {
//                        if (selected != null) {
//                            selected.removeClassName("pathway-nodeName--selected");
//                        }
//                        selected = node;
//                        selected.addClassName("pathway-nodeName--selected");
//                    }
//                }
//            }
//        }));
    }


//
//    private SafeHtml getConstraintsHtml(final PathNode pathNode) {
//        final HtmlBuilder hb = new HtmlBuilder();
//
//        final Constraints constraints = pathNode.getConstraints();
//        if (constraints != null) {
//            if (constraints.getDuration() != null) {
//                append(hb, "Duration", constraints.getDuration().toString());
//            }
//            if (constraints.getFlags() != null) {
//                append(hb, "Flag", constraints.getFlags().toString());
//            }
//            if (constraints.getKind() != null) {
//                append(hb, "Kind", constraints.getKind().toString());
//            }
//            append(hb, "Attributes", "");
//
//            final Map<String, ConstraintValue> requiredAttributes = NullSafe.map(constraints.getRequiredAttributes());
//            final Map<String, ConstraintValue> optionalAttributes = NullSafe.map(constraints.getOptionalAttributes());
//            final Set<String> keys = new HashSet<>(requiredAttributes.keySet());
//            keys.addAll(optionalAttributes.keySet());
//            final List<String> sortedKeys = keys.stream().sorted().collect(Collectors.toList());
//            for (final String key : sortedKeys) {
//                final ConstraintValue required = requiredAttributes.get(key);
//                if (required != null) {
//                    hb.div(div ->
//                            append(div, key, required.toString()), Attribute.className("pathway-attributes"));
//                }
//                final ConstraintValue optional = optionalAttributes.get(key);
//                if (optional != null) {
//                    hb.div(div ->
//                            append(div, key, optional.toString()),
//                            Attribute.className("pathway-attributes-optional"));
//                }
//            }
//        }
//        return hb.toSafeHtml();
//    }
//
//    private SafeHtml getSpanHtml(final Span span) {
//        final HtmlBuilder hb = new HtmlBuilder();
//        if (span.getTraceId() != null) {
//            append(hb, "Trace Id", span.getTraceId());
//        }
//        if (span.getSpanId() != null) {
//            append(hb, "Span Id", span.getSpanId());
//        }
//        if (span.getTraceState() != null) {
//            append(hb, "Trace State", span.getTraceState());
//        }
//        if (span.getParentSpanId() != null) {
//            append(hb, "Parent Span Id", span.getParentSpanId());
//        }
//        if (span.getFlags() != -1) {
//            append(hb, "Flags", String.valueOf(span.getFlags()));
//        }
//        if (span.getName() != null) {
//            append(hb, "Name", span.getName());
//        }
//        if (span.getKind() != null) {
//            append(hb, "Kind", String.valueOf(span.getKind()));
//        }
////        if (span.getstartTimeUnixNano() != null) {
////
////        if (span.getendTimeUnixNano() != null) {
//
//        if (!NullSafe.isEmptyCollection(span.getAttributes())) {
//            append(hb, "Attributes", "");
//            hb.div(div -> {
//                for (final KeyValue keyValue : span.getAttributes()) {
//                    append(div, keyValue.getKey(), keyValue.getValue().toString());
//                }
//            }, Attribute.className("pathway-attributes"));
//        }
//        if (span.getDroppedAttributesCount() != -1) {
//            append(hb, "Dropped Attributes Count", String.valueOf(span.getDroppedAttributesCount()));
//        }
////        if (span.getevents() != null) {
//
//        if (span.getDroppedEventsCount() != -1) {
//            append(hb, "Dropped Events Count", String.valueOf(span.getDroppedEventsCount()));
//        }
////        if (span.getlinks() != null) {
//
//        if (span.getDroppedLinksCount() != -1) {
//            append(hb, "Dropped Links Count", String.valueOf(span.getDroppedLinksCount()));
//        }
//        if (span.getStatus() != null) {
//            append(hb, "Status", "");
//            hb.div(div -> {
//                if (span.getStatus().getCode() != null) {
//                    append(div, "Code", span.getStatus().getCode().toString());
//                }
//                if (span.getStatus().getMessage() != null) {
//                    append(div, "Message", span.getStatus().getMessage());
//                }
//            }, Attribute.className("pathway-attributes"));
//        }
//
//        return hb.toSafeHtml();
//    }
//
//    private void append(final HtmlBuilder hb, final String key, final String value) {
//        hb.div(div -> {
//            div.div(k -> k.append(key + ":"), Attribute.className("pathway-attributeKey"));
//            div.div(v -> v.append(value), Attribute.className("pathway-attributeValue"));
//        }, Attribute.className("pathway-attribute"));
//    }

//    private SafeHtml getDurationsHtml(final NanoTime min,
//                                      final NanoTime max,
//                                      final NanoTime sum,
//                                      final int count) {
//        final HtmlBuilder hb = new HtmlBuilder();
//        append(hb, "Calls", Integer.toString(count));
//        append(hb, "Min Duration", min.toString());
//        append(hb, "Max Duration", max.toString());
//        append(hb, "Average Duration",
//                new NanoTime(sum.getSeconds() / count, sum.getNanos() / count).toString());
//        return hb.toSafeHtml();
//    }

    // The whole history at once, so winding the model back is done here rather than asked for a step
    // at a time. It is bounded by what the model has learnt, not by how many traces went through it.
    private void fetchHistory(final DocRef docRef, final String name) {
        // The node the table will be narrowed to once the rows are in, so the plot arrives counted
        // for it. Taken from what is being kept rather than from what is on screen: the rows are
        // about to be replaced, and the plot has to answer for the table that follows them.
        final String plot = keptFilter == PathwayMutationListPresenter.Filter.SELECTED_NODE
                ? NullSafe.get(keptNode, PathNode::getUuid)
                : null;
        // The same range the drawing answers for, so the table beside it lists the changes it was
        // made from rather than every change there has ever been.
        final FindPathwayMutationCriteria criteria = new FindPathwayMutationCriteria(
                new PageRequest(0, MAX_HISTORY),
                List.of(new CriteriaFieldSort(PathwayMutation.FIELD_TIME, false, false)),
                docRef,
                name,
                timeRange,
                plot);

        restFactory
                .create(PATHWAYS_RESOURCE)
                .method(res -> res.findMutations(criteria))
                .onSuccess(result -> {
                    // This is opened again for other pathways, so a history arriving late must not be
                    // used to wind back a model it does not belong to.
                    if (pathway == null || !name.equals(pathway.getName())) {
                        return;
                    }
                    history = result.getValues();
                    // Said before the rows are handed over, so the table is built once with the
                    // narrowing and the open traces already on it rather than reset and put back.
                    //
                    // The picking that comes out of it is not listened to: the drawing is decided
                    // once below, and answering the event as well would ask the server twice for
                    // the same change.
                    restoring = true;
                    try {
                        mutationListPresenter.keep(keptNode, keptFilter, keptRow);
                        mutationListPresenter.setData(history,
                                result.getWindowFrom(), result.getWindowTo(), result.getBars());
                    } finally {
                        restoring = false;
                    }
                    historyArrived = true;
                    // Whatever came back picked: the model at that change, or the range's own where
                    // no row did. Already in hand for the second, so it costs nothing to ask.
                    showAtPosition(mutationListPresenter.getSelectedTime(),
                            mutationListPresenter.getSelectedSequence());
                    keepNothing();
                })
                .onFailure(new DefaultErrorHandler(this, () -> {
                    // Asked and not answered. The drawing waits for these, so it has to be told that
                    // none are coming or it waits for ever. There are no rows to put anything back
                    // among, so whatever the reader had set up is let go.
                    keepNothing();
                    historyArrived = true;
                    drawWhenReady();
                }))
                // The table's own view, so the refresh button beside it turns while its rows are
                // being fetched rather than the tab reporting it somewhere else.
                .taskMonitorFactory(mutationListPresenter.getView())
                .exec();
    }

    // Nothing left to put back, which is where every fetch of the rows ends. Held only between
    // asking for them and their arriving, so that a fetch nobody kept anything for starts clean.
    private void keepNothing() {
        keptRow = null;
        keptFilter = null;
        keptNode = null;
    }

    // The plot on its own, counted for whichever node the table has been narrowed to. No rows are
    // asked for - the page is of no length - because the ones on screen have not changed and
    // replacing them would shut every trace the reader had opened.
    private void fetchPlot() {
        if (docRef == null || pathway == null) {
            return;
        }
        final String name = pathway.getName();
        final FindPathwayMutationCriteria criteria = new FindPathwayMutationCriteria(
                new PageRequest(0, 0),
                List.of(new CriteriaFieldSort(PathwayMutation.FIELD_TIME, false, false)),
                docRef,
                name,
                timeRange,
                plotNode());

        restFactory
                .create(PATHWAYS_RESOURCE)
                .method(res -> res.findMutations(criteria))
                .onSuccess(result -> {
                    // This is opened again for other pathways, and the filter can be turned off
                    // again while this is on its way, so a plot that no longer answers for what is
                    // on screen is dropped rather than drawn.
                    if (pathway != null && name.equals(pathway.getName())
                        && Objects.equals(criteria.getPlotNode(), plotNode())) {
                        mutationListPresenter.setBars(result.getBars());
                    }
                })
                .onFailure(new DefaultErrorHandler(this, null))
                .taskMonitorFactory(this)
                .exec();
    }

    // Which node the plot is counted over, or null for all of them. The same two things the table
    // asks of itself: a filter is only narrowing anything while a node is picked out.
    private String plotNode() {
        return mutationListPresenter.getFilter() == PathwayMutationListPresenter.Filter.SELECTED_NODE
                ? NullSafe.get(pathwayTreePresenter.getSelectionModel().getSelectedObject(),
                        PathNode::getUuid)
                : null;
    }

    // How much each node had been used at each point the model can be wound back to. Fetched apart
    // from the changes because there is one reading per trace that changed the model rather than one
    // per change, and the drawing needs all of them rather than a page.

    // Once, when everything the drawing is made from is in. The changes say how much each node has
    // moved and the readings say how much has gone through it, and a drawing made with one but not
    // the other is drawn at the wrong sizes and then drawn again — on a large model that is two slow
    // redraws and a jump, for a picture that was never right the first time.
    private void drawWhenReady() {
        if (historyArrived) {
            showModel();
        }
    }

    // Steps on every second and a half, so the model can be watched changing rather than having to
    // be clicked through — long enough to take in what moved between one stop and the next, which is
    // the point of watching it. A trace where the change history reaches, a whole day where it does
    // not.
    private void play() {
        if (!mutationListPresenter.selectNextStop()) {
            // Nothing after the one picked, which is where a replay ends and where the top of the list
            // starts. Pressing play there means watch it again, so it goes back to the beginning
            // rather than doing nothing. Cleared without telling anyone: the step taken straight after
            // draws the model, and announcing the clearing first would draw it twice.
            mutationListPresenter.getSelectionModel().clear(false);
            if (!mutationListPresenter.selectNextStop()) {
                // Nothing ever taught this model and no day was given anything, so there is
                // nothing to watch.
                return;
            }
        }
        pathwayTreePresenter.setStepping(true);
        stepper.scheduleRepeating(STEP_MILLIS);
        showPlayButtons();
    }

    private void stop() {
        stepper.cancel();
        pathwayTreePresenter.setStepping(false);
        showPlayButtons();
    }

    // Narrowing the table to a node, or removing it, from the drawing. The node has already been
    // picked out by the time this runs, so every entry acts on the one the reader asked about.
    private void showNodeMenu(final PathNode node, final PopupPosition position) {
        if (node == null) {
            return;
        }
        // What the menu offers is what the table under it can do, so it follows whichever tab is up.
        final List<Item> items = new ArrayList<>(changesTab.equals(selectedTab)
                ? changeFilterItems()
                : pathFilterItems());
        // Not on the root, because removing that is deleting the pathway, which the list does.
        if (!readOnly && NullSafe.list(node.getNodePath()).size() > 1) {
            items.add(new Separator(10));
            items.add(new IconMenuItem.Builder()
                    .priority(11)
                    .icon(SvgImage.DELETE)
                    .text("Remove node")
                    .command(() -> confirmRemove(node))
                    .enabled(latest())
                    .build());
        }

        ShowMenuEvent
                .builder()
                .items(items)
                .popupPosition(position)
                .fire(this);
    }

    private void confirmRemove(final PathNode node) {
        final int under = count(node) - 1;
        final String what = under == 0
                ? "'" + node.getName() + "'"
                : "'" + node.getName() + "' and the " + under + " node(s) under it";
        ConfirmEvent.fire(this,
                "Remove " + what + " and any paths containing it from the model?",
                ok -> {
                    if (ok) {
                        removeNode(node);
                    }
                });
    }

    // Checked against the model as it stands now, which is also what the current constraint is taken
    // from, so the server can refuse if a trace changes it before OK is pressed. The value and the
    // optional flag rolled back to are the ones the constraint had on the node at the end of the trace
    // or user action the change belongs to.
    private void confirmRollback(final PathwayMutation mutation,
                                 final PathwayAsAt current,
                                 final PathwayAsAt then) {
        final PathNode node = nodeIn(current, mutation.getNodeUuid());
        final String nodeName = NullSafe.last(mutation.getNodePath());
        final String constraintName = mutation.getConstraint();
        if (node == null || node.isRetired()) {
            AlertEvent.fireWarn(this, "The constraint '" + constraintName + "' cannot be rolled back because the "
                                      + "node '" + nodeName + "' has been removed or retired.",
                    null);
            return;
        }
        final Constraint constraint = NullSafe.get(nodeIn(then, mutation.getNodeUuid()),
                PathNode::getConstraints, map -> map.get(constraintName));
        if (constraint == null) {
            AlertEvent.fireWarn(this, "The constraint '" + constraintName + "' was not on node '" + nodeName
                                      + "' at this change.",
                    null);
            return;
        }
        final Constraint previous = NullSafe.get(node.getConstraints(), map -> map.get(constraintName));
        if (constraint.equals(previous)) {
            AlertEvent.fireWarn(this, "The constraint '" + constraintName + "' on '" + node.getName() + "' is already "
                                      + describe(constraint) + ".",
                    null);
            return;
        }
        ConfirmEvent.fire(this,
                "Are you sure you want to rollback the constraint '" + constraintName + "' on node '"
                + node.getName() + "' to " + describe(constraint) + "?",
                ok -> {
                    if (ok) {
                        rollback(node, previous, constraint);
                    }
                });
    }

    private static String describe(final Constraint constraint) {
        return constraint.getValue() + (constraint.isOptional()
                ? ", optional"
                : ", required");
    }

    // Checked against the model as it stands now, which is also what the node's current constraints
    // and retired state are taken from, so the server can refuse if a trace changes either before OK is
    // pressed. The target is every constraint the node held at the end of the trace or user action the
    // change belongs to.
    private void confirmNodeRollback(final PathwayMutation mutation,
                                     final PathwayAsAt current,
                                     final PathwayAsAt then) {
        final String nodeName = NullSafe.last(mutation.getNodePath());
        final PathNode node = nodeIn(current, mutation.getNodeUuid());
        if (node == null) {
            AlertEvent.fireWarn(this, "The node '" + nodeName + "' cannot be rolled back because it is no "
                                      + "longer in the pathway.",
                    null);
            return;
        }
        final PathNode was = nodeIn(then, mutation.getNodeUuid());
        if (was == null) {
            AlertEvent.fireWarn(this, "The node '" + nodeName + "' was not in the pathway at this change.",
                    null);
            return;
        }
        final List<String> above = new ArrayList<>();
        leadsTo(NullSafe.get(current, PathwayAsAt::getPathway, Pathway::getRoot), node.getUuid(), above);
        final Map<String, Constraint> previous = NullSafe.map(node.getConstraints());
        final Map<String, Constraint> target = NullSafe.map(was.getConstraints());
        if (!node.isRetired() && above.isEmpty() && previous.equals(target)) {
            AlertEvent.fireWarn(this, "The node '" + node.getName() + "' already stands as it did at this "
                                      + "change.",
                    null);
            return;
        }
        final Set<String> names = new HashSet<>(previous.keySet());
        names.addAll(target.keySet());
        final long changes = names.stream()
                .filter(constraintName -> !Objects.equals(previous.get(constraintName), target.get(constraintName)))
                .count();
        ConfirmEvent.fire(this,
                "Are you sure you want to rollback node '" + node.getName() + "' to how it stood at this "
                + "change?"
                + (node.isRetired()
                        ? " It will be revived."
                        : "")
                + (above.isEmpty()
                        ? ""
                        : " It will also revive '" + String.join("', '", above) + "'."),
                ok -> {
                    if (ok) {
                        rollbackNode(node, previous, target);
                    }
                });
    }

    // Whether the drawing is the pathway as it stands, which is what a removal or an edit acts on. The server
    // sends no layout, and folds nothing, only when that is what it sent; a node drawn as it was at an
    // earlier moment may already have been removed since.
    private boolean latest() {
        return asAt != null && !asAt.isFolded() && asAt.getLayout() == null;
    }

    // The model and the changes are both asked for again once it lands, because the removal added rows
    // to the one as well as changing the other. Asked for again on a refusal too: the model on show is
    // out of date, which is the reason the server gives one. Whatever opened the tab is told only when
    // the removal lands.
    private void removeNode(final PathNode node) {
        restFactory
                .create(PATHWAYS_RESOURCE)
                .method(res -> res.removeNode(new RemoveNode(docRef, name, node.getUuid())))
                .onSuccess(removed -> {
                    if (Boolean.TRUE.equals(removed)) {
                        modelChanged();
                    } else {
                        AlertEvent.fireWarn(this, "'" + node.getName() + "' was not removed. It has "
                                               + "already been removed or retired since this was drawn.",
                                null);
                    }
                    refresh();
                })
                .onFailure(new DefaultErrorHandler(this, null))
                .taskMonitorFactory(this)
                .exec();
    }

    // The model and the changes are asked for again whatever the answer, because a rollback adds a
    // change row and a refusal means the model on show is out of date.
    private void rollback(final PathNode node, final Constraint previous, final Constraint constraint) {
        restFactory
                .create(PATHWAYS_RESOURCE)
                .method(res -> res.editConstraint(
                        new EditConstraint(docRef, name, node.getUuid(), previous, constraint)))
                .onSuccess(changed -> {
                    if (Boolean.TRUE.equals(changed)) {
                        modelChanged();
                    } else {
                        AlertEvent.fireWarn(this, "The constraint '" + constraint.getName() + "' on '"
                                                  + node.getName() + "' is already " + describe(constraint)
                                                  + ".",
                                null);
                    }
                    refresh();
                })
                .onFailure(error -> {
                    AlertEvent.fireError(this, reason(error.getMessage()), null);
                    refresh();
                })
                .taskMonitorFactory(this)
                .exec();
    }

    // The model and the changes are asked for again whatever the answer, because a rollback adds
    // change rows and a refusal means the model on show is out of date.
    private void rollbackNode(final PathNode node,
                              final Map<String, Constraint> previous,
                              final Map<String, Constraint> target) {
        restFactory
                .create(PATHWAYS_RESOURCE)
                .method(res -> res.rollbackNode(
                        new RollbackNode(docRef, name, node.getUuid(), previous, node.isRetired(), target)))
                .onSuccess(changed -> {
                    if (Boolean.TRUE.equals(changed)) {
                        modelChanged();
                    } else {
                        AlertEvent.fireWarn(this, "The node '" + node.getName() + "' already stands as it did "
                                                  + "at this change.",
                                null);
                    }
                    refresh();
                })
                .onFailure(error -> {
                    AlertEvent.fireError(this, reason(error.getMessage()), null);
                    refresh();
                })
                .taskMonitorFactory(this)
                .exec();
    }

    private void removeConstraints(final List<String> names) {
        final PathNode node = shownNode();
        if (node == null) {
            return;
        }
        restFactory
                .create(PATHWAYS_RESOURCE)
                .method(res -> res.removeConstraints(
                        new RemoveConstraints(docRef, name, node.getUuid(), names)))
                .onSuccess(removed -> {
                    if (Boolean.TRUE.equals(removed)) {
                        modelChanged();
                    } else {
                        AlertEvent.fireWarn(this, "The constraints were not removed from '"
                                                  + node.getName() + "'. The node has been removed or "
                                                  + "retired, or the constraints have gone, since this "
                                                  + "was drawn.",
                                null);
                    }
                    refresh();
                })
                .onFailure(new DefaultErrorHandler(this, null))
                .taskMonitorFactory(this)
                .exec();
    }

    // A refusal passes the server's reason back to the dialog, or a fixed one where the server gave
    // none, and the model is asked for again either way: one the server refused an edit to is out of
    // date on screen.
    private void editConstraint(final Constraint previous,
                                final Constraint constraint,
                                final Runnable accepted,
                                final Consumer<String> refused) {
        final PathNode node = shownNode();
        if (node == null) {
            accepted.run();
            return;
        }
        restFactory
                .create(PATHWAYS_RESOURCE)
                .method(res -> res.editConstraint(
                        new EditConstraint(docRef, name, node.getUuid(), previous, constraint)))
                .onSuccess(changed -> {
                    accepted.run();
                    if (Boolean.TRUE.equals(changed)) {
                        modelChanged();
                    }
                    refresh();
                })
                .onFailure(error -> {
                    refused.accept(reason(error.getMessage()));
                    refresh();
                })
                .taskMonitorFactory(this)
                .exec();
    }

    // The server's reason for refusing a change, or a fixed one where it gave none.
    private static String reason(final String message) {
        return message == null || message.trim().isEmpty()
                ? "The change could not be made"
                : message;
    }

    private void modelChanged() {
        if (changedHandler != null) {
            changedHandler.run();
        }
    }

    private static int count(final PathNode node) {
        int count = 1;
        for (final PathNode child : NullSafe.list(node.getChildren())) {
            count += count(child);
        }
        return count;
    }

    private static PathNode nodeIn(final PathwayAsAt asAt, final String uuid) {
        final PathNode root = NullSafe.get(asAt, PathwayAsAt::getPathway, Pathway::getRoot);
        return root == null
                ? null
                : find(root, uuid);
    }

    private static boolean leadsTo(final PathNode node, final String uuid, final List<String> retired) {
        if (node == null) {
            return false;
        }
        if (Objects.equals(uuid, node.getUuid())) {
            return true;
        }
        for (final PathNode child : NullSafe.list(node.getChildren())) {
            if (leadsTo(child, uuid, retired)) {
                if (node.isRetired()) {
                    retired.add(0, node.getName());
                }
                return true;
            }
        }
        return false;
    }

    private static PathNode find(final PathNode node, final String uuid) {
        if (Objects.equals(uuid, node.getUuid())) {
            return node;
        }
        for (final PathNode child : NullSafe.list(node.getChildren())) {
            final PathNode found = find(child, uuid);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private void showPlayButtons() {
        playButton.setVisible(!stepper.isRunning());
        stopButton.setVisible(stepper.isRunning());
    }

    // The model as it stood at the change being looked at, or as it stands now where none is.
    private void showModel() {
        if (pathway == null) {
            return;
        }

        drawnChange = mutationListPresenter.getSelectedSequence();
        drawnAt = mutationListPresenter.getSelectedTime();
        drawnPath = pathListPresenter.getSelectionModel().getSelected();

        // Set before the model is read, because the nodes are picked out as the drawing is built. The
        // tab on show decides which selection picks them out, so a path left selected behind the
        // Changes tab does not keep marking the drawing while changes are being clicked through.
        if (changesTab.equals(selectedTab)) {
            pathwayTreePresenter.setHighlighted(mutationListPresenter.getSelectedNodePaths());
        } else {
            pathwayTreePresenter.setHighlightedPath(pathListPresenter.getSelectedNodePaths(),
                    pathListPresenter.getSelectedRunStarts(),
                    pathListPresenter.getSelectedHolds());
        }
        // The model on show is whatever came back for the moment asked for, and the figures with it.
        // Neither is worked out here: winding back needs an unbroken run of changes from the newest,
        // and the rows it needs are filed by the day their work ran rather than by when they were
        // learnt, so a date never promises one.
        final Pathway shown = NullSafe.getOrElse(asAt, PathwayAsAt::getPathway, pathway);

        // Everything the drawing is made from, in one go: the figures, the time ages are counted
        // back from, and the tree the nodes are placed from. All three come off what the server
        // sent, and in particular none of them is taken from anything this tab is holding - the
        // learner is still running, so a copy kept from when the tab was opened misses whatever has
        // been learnt since, and a node missing from the layout is never drawn at all.
        pathwayTreePresenter.setFigures(asAt);
        pathwayTreePresenter.read(NullSafe.get(shown, Pathway::getRoot) == null
                ? null
                : shown);

        // Which of a node's constraints the change being looked at moved is marked in the table beside
        // the drawing, and there may no longer be a change being looked at. Whichever way this went,
        // asked for rather than waited for: the node picked out has not moved, so the selection that
        // usually brings this on says nothing.
        showConstraints();
    }

    // Only the selected one is built into the strip, so the other stops drawing rather than being
    // hidden behind it.
    // A change is made to one node, so there is only the one thing to ask of it. There is no
    // counterpart to the paths menu's "avoiding", because the changes that were not made to a node are
    // not about that node at all.
    private List<Item> changeFilterItems() {
        final PathwayMutationListPresenter.Filter filter = mutationListPresenter.getFilter();
        final List<Item> items = new ArrayList<>();
        items.add(new IconMenuItem.Builder()
                .priority(1)
                .icon(SvgImage.FILTER)
                .text("Show changes to the selected node")
                .command(() -> mutationListPresenter.setFilter(
                        PathwayMutationListPresenter.Filter.SELECTED_NODE))
                .enabled(filter != PathwayMutationListPresenter.Filter.SELECTED_NODE)
                .build());
        items.add(new IconMenuItem.Builder()
                .priority(2)
                .icon(SvgImage.UNDO)
                .text("Show every change")
                .command(() -> mutationListPresenter.setFilter(PathwayMutationListPresenter.Filter.OFF))
                .enabled(filter != PathwayMutationListPresenter.Filter.OFF)
                .build());
        return items;
    }

    // Offered on a change's node: the same node on the drawing. Greyed out where the drawing does not
    // show it - wound back to before the change that added it, or with the node retired or removed.
    // On a change, and where the pathway can be edited, also setting the node back to how it stood at
    // the end of the trace or user action the change belongs to, whatever moment the drawing shows,
    // because the rollback is made to the model as it stands now.
    private List<Item> changeNodeItems(final HistoryRow row) {
        final PathNode node = pathwayTreePresenter.findNode(row.getNodePath());
        final Item locate = new IconMenuItem.Builder()
                .icon(SvgImage.LOCATE)
                .text("Locate node")
                .command(() -> pathwayTreePresenter.showNode(node))
                .enabled(node != null)
                .build();
        if (!readOnly && row instanceof final PathwayMutation mutation && mutation.getNodeUuid() != null) {
            return List.of(locate, new IconMenuItem.Builder()
                    .icon(SvgImage.UNDO)
                    .text("Rollback node")
                    .command(() -> fetchRollback(mutation,
                            (current, then) -> confirmNodeRollback(mutation, current, then)))
                    .build());
        }
        return List.of(locate);
    }

    // Offered on a change's constraint: set it back to the value and optional flag it had at the end of
    // the trace or user action the change belongs to. Offered whatever moment the drawing shows,
    // because the rollback is made to the model as it stands now.
    private List<Item> changeConstraintItems(final PathwayMutation mutation) {
        if (readOnly || mutation.getConstraint() == null || mutation.getNewValue() == null) {
            return List.of();
        }
        return List.of(new IconMenuItem.Builder()
                .icon(SvgImage.UNDO)
                .text("Rollback constraint")
                .command(() -> fetchRollback(mutation,
                        (current, then) -> confirmRollback(mutation, current, then)))
                .build());
    }

    private void fetchRollback(final PathwayMutation mutation,
                               final BiConsumer<PathwayAsAt, PathwayAsAt> consumer) {
        final PathwayMutation end = endOf(mutation);
        fetch(new FetchPathwayRequest(docRef, name),
                current -> fetch(FetchPathwayRequest.atChange(docRef, name, upTo(end.getTime()),
                                end.getSequence()),
                        then -> consumer.accept(current, then)));
    }

    private PathwayMutation endOf(final PathwayMutation mutation) {
        final int at = history.indexOf(mutation);
        if (at < 0) {
            return mutation;
        }
        final String group = PathwayMutation.groupOf(mutation);
        PathwayMutation end = mutation;
        for (final int step : new int[]{-1, 1}) {
            int i = at + step;
            while (i >= 0 && i < history.size()
                   && history.get(i) instanceof final PathwayMutation row
                   && group.equals(PathwayMutation.groupOf(row))) {
                if (row.getSequence() > end.getSequence()) {
                    end = row;
                }
                i += step;
            }
        }
        return end;
    }

    private List<Item> pathFilterItems() {
        final PathwayPathListPresenter.Filter filter = pathListPresenter.getFilter();
        final List<Item> items = new ArrayList<>();
        items.add(new IconMenuItem.Builder()
                .priority(1)
                .icon(SvgImage.FILTER)
                .text("Show paths through the selected node")
                .command(() -> pathListPresenter.setFilter(PathwayPathListPresenter.Filter.RUNNING_NODE))
                .enabled(filter != PathwayPathListPresenter.Filter.RUNNING_NODE)
                .build());
        items.add(new IconMenuItem.Builder()
                .priority(2)
                .icon(SvgImage.FILTER)
                .text("Show paths avoiding the selected node")
                .command(() -> pathListPresenter.setFilter(PathwayPathListPresenter.Filter.AVOIDING_NODE))
                .enabled(filter != PathwayPathListPresenter.Filter.AVOIDING_NODE)
                .build());
        items.add(new IconMenuItem.Builder()
                .priority(3)
                .icon(SvgImage.UNDO)
                .text("Show every path")
                .command(() -> pathListPresenter.setFilter(PathwayPathListPresenter.Filter.OFF))
                .enabled(filter != PathwayPathListPresenter.Filter.OFF)
                .build());
        return items;
    }

    private void showTab(final TabData tab) {
        if (tab == null) {
            return;
        }
        selectedTab = tab;
        getView().getTabBar().selectTab(tab);
        getView().getLayerContainer().show(changesTab.equals(tab)
                ? mutationListPresenter
                : pathListPresenter);

        // Stepping through the changes is driven by the list on the Changes tab, and the buttons that
        // start and stop it sit on that list's own toolbar. Left running behind the other tab it would
        // go on picking changes with nothing on show to stop it.
        stop();

        // Neither table's selection says anything on the other tab — a change winds the model back
        // where a path picks nodes out of it as it stands — so the switch starts at the model as it
        // is with nothing picked out. Cleared without telling anyone, because the read below is the
        // one the switch asked for rather than the third of three.
        mutationListPresenter.getSelectionModel().clear(false);
        pathListPresenter.getSelectionModel().clear(false);
        showModel();
    }

    // The node the tree is holding, whichever model it came from. Winding the model back picks the same
    // node out again, and the selection model treats that as no change and says nothing, so this has to
    // be asked for rather than waited for.
    /**
     * Shows what is known about the node picked out on the drawing, once the picking has settled.
     *
     * <p>Deferred and folded into one, because reading a pathway clears the selection and then picks
     * its root, which arrives here as two changes in a row — three where the read itself asks as well.
     * Acting on each empties both panels and fills them again, and a table that empties is shorter
     * than one that does not, so what sits under it jumps for a state that never lasted long enough
     * to read.
     */
    private void showConstraints() {
        if (nodePending) {
            return;
        }
        nodePending = true;
        Scheduler.get().scheduleDeferred(() -> {
            nodePending = false;
            showSettledConstraints();
        });
    }

    private PathNode shownNode() {
        final PathNode selected = pathwayTreePresenter.getSelectionModel().getSelectedObject();
        // A retired or not-yet-added node keeps its selection but is not on the drawing.
        return selected == null
                ? null
                : pathwayTreePresenter.findNode(selected.getNodePath());
    }

    private void showSettledConstraints() {
        final PathNode node = shownNode();
        constraintListPresenter.setData(node,
                readOnly,
                !readOnly && latest(),
                mutationListPresenter.getSelectedConstraints(NullSafe.get(node, PathNode::getNodePath)));
        // How often the model has been taught something about this node is not on the node — the
        // changes say it. Asked of the drawing, which has already worked it out to size the node by,
        // so the two cannot disagree about it.
        nodeInfoPresenter.setData(node,
                pathwayTreePresenter.getChange(node));
    }

    // Only the selected one is built into the panel, so the other stops drawing rather than being
    // hidden behind it.
    private void showNodeTab(final TabData tab) {
        if (tab == null) {
            return;
        }
        getView().getNodeTabBar().selectTab(tab);
        getView().getNodeLayerContainer().show(infoTab.equals(tab)
                ? nodeInfoPresenter
                : constraintListPresenter);
    }

    /**
     * Opens one pathway. The model is fetched here rather than handed over, so the tab shows what the
     * server holds.
     */
    public void read(final PathwaysDoc pathwaysDoc, final String name, final boolean readOnly) {
        this.readOnly = readOnly;
        this.docRef = pathwaysDoc.asDocRef();
        this.name = name;
        // The range picks which changes are listed and plotted, not how much of the model is
        // drawn: one ending now takes nothing off it.
        timeRangeSelector.setValue(TimeRanges.TODAY);
        showOver(TimeRanges.TODAY);
    }

    // A different range is a different set of rows, so both tables are rebuilt and the histogram
    // with them. The drawing answers for the latest time in it, which the server works out.
    private void showOver(final TimeRange wanted) {
        timeRange = wanted;
        fetch(FetchPathwayRequest.over(docRef, name, wanted), this::show);
    }

    // A row picked out of the table, or null to go back to the model as it stands. Only the drawing
    // moves: the row picked is still a row of the same table, so rebuilding it would take the
    // selection away and send the histogram back to where it started.
    private void showAtPosition(final NanoTime time, final Long sequence) {
        if (time == null && sequence == null) {
            // Nothing picked out, which is the range's own model - already fetched and still here.
            showPosition(rangeAsAt);
            return;
        }
        // The moment as well as the change. The change says which one of the several a single trace
        // taught the model, and the moment says which week to look in for the ones that have moved
        // out of the shard. A day has only the moment, and is folded rather than wound back. A change
        // with no moment on it is still asked for by number, the way it was before either carried a
        // moment at all.
        fetch(FetchPathwayRequest.atChange(docRef, name, upTo(time), sequence), this::showPosition);
    }

    // A range ending at the given moment, or null where there is no moment.
    private static TimeRange upTo(final NanoTime time) {
        return time == null
                ? null
                : new TimeRange(null, null, ClientDateUtil.toISOString(time.toEpochMillis()));
    }

    private void fetch(final FetchPathwayRequest request, final Consumer<PathwayAsAt> onSuccess) {
        if (docRef == null || name == null) {
            return;
        }
        restFactory
                .create(PATHWAYS_RESOURCE)
                .method(res -> res.fetchPathway(request))
                .onSuccess(onSuccess)
                .onFailure(new DefaultErrorHandler(this, null))
                .taskMonitorFactory(this)
                .exec();
    }

    private void refresh() {
        if (docRef == null || pathway == null) {
            return;
        }
        keptRow = mutationListPresenter.getSelectionModel().getSelected();
        keptFilter = mutationListPresenter.getFilter();
        keptNode = pathwayTreePresenter.getSelectionModel().getSelectedObject();
        // Asks for the model and then, once it lands, the rows. One call rather than one apiece:
        // asking for the rows here as well would fetch them twice over.
        showOver(timeRange);
    }

    // What came back for a position, which is the drawing and nothing else.
    private void showPosition(final PathwayAsAt asAt) {
        this.asAt = asAt;
        showModel();
    }

    private void show(final PathwayAsAt asAt) {
        this.asAt = asAt;
        this.rangeAsAt = asAt;
        // Whatever came back, whichever moment it was asked for. Winding a model back replaces its
        // nodes and copies the rest of it across, so the paths, the sources and the name are the
        // pathway's current ones however far back the drawing reaches.
        final Pathway pathway = NullSafe.get(asAt, PathwayAsAt::getPathway);
        if (pathway == null) {
            return;
        }
        // Whether what is on screen is this same pathway. If it is, it is still the right thing to
        // be looking at until the new rows land, however the range moved.
        final boolean sameOnScreen = this.pathway != null
                                     && Objects.equals(this.pathway.getName(), pathway.getName());
        this.pathway = pathway;

        // Where the pathway has learnt from, which both tables name their rows' stores by position in.
        // It comes with the model rather than from the configuration, so a store pointed elsewhere
        // since does not take its old rows' trace links with it.
        final List<String> sources = NullSafe.get(pathway.getPaths(), Paths::getSources);
        pathListPresenter.setSources(sources);
        mutationListPresenter.setSources(sources);

        // The paths arrive on the pathway itself, so they are on show as soon as it is opened rather
        // than waiting on the history the way the changes do.
        pathListPresenter.setData(pathway);

        showConstraints();
        showPlayButtons();
        // The drawing is coloured and sized from the changes behind the model, which are asked for
        // at the end of this. Nothing is handed over until they arrive: an empty list would say
        // this pathway's changes had arrived and were none, and the drawing would be made with
        // every node at its smallest.
        //
        // Both are skipped for a pathway already on screen. What is up is still this pathway's,
        // and emptying the table and the plot on the way to filling them again is a blink that
        // answers nothing. Another pathway's rows under this one's name would be a lie, so those
        // do go.
        if (!sameOnScreen) {
            pathwayTreePresenter.read(pathway);
            mutationListPresenter.setData(Collections.emptyList());
        }

        // Only the changes. How busy each node was comes back with the model now, so the readings
        // are no longer asked for separately.
        historyArrived = false;
        fetchHistory(docRef, pathway.getName());
    }

    /**
     * Told whenever a change to the model sent from this tab lands, so whatever opened this can show
     * the pathway's new size and time.
     */
    public void setChangedHandler(final Runnable changedHandler) {
        this.changedHandler = changedHandler;
    }

    /**
     * Told once the tab has been opened. A content tab is shown by being added to a layer rather than
     * put in a slot, so nothing calls the reveal a presenter would otherwise wait for.
     */
    public void onOpened() {
        if (!split) {
            split = true;
            // Three fifths of the height to the model and two to the tables under it. Set before
            // the width below, so that the drawing is centred against the room it actually ends up
            // with rather than against a height that is about to change under it.
            getView().setLowerShare(LOWER_SHARE);
            // Three fifths to the drawing, the rest to the constraints. The drawing wants all the
            // room it can have, but a constraint's value is a whole sql statement or a set of names
            // and is worth nothing cut off at the second word.
            getView().setTreeShare(TREE_SHARE, pathwayTreePresenter::centre);
        }
    }

    @Override
    public void onCloseRequest(final CloseContentEvent event) {
        // Nothing to step through once the tab has gone, and a clock left running would keep moving a
        // selection in a list nobody is looking at.
        stop();
        event.getCallback().closeTab(true);
    }

    @Override
    public String getLabel() {
        return name;
    }

    @Override
    public SvgImage getIcon() {
        return SvgImage.PATHWAYS_CHOICE;
    }

    @Override
    public String getType() {
        return TAB_TYPE;
    }


    public interface PathwayEditView extends View {

        /**
         * Puts a widget at the right of the toolbar, for the date the tab is showing.
         */
        void addToolbarWidget(Widget widget);

        void setLowerShare(double share);

        void setTreeShare(double share, Runnable onSized);

        void setTree(View view);

        /**
         * Which node the constraints below belong to, or nothing where none is picked out.
         */
        LinkTabBar getTabBar();

        LayerContainer getLayerContainer();

        /** The tabs over the middle panel, which are about the node picked out on the drawing. */
        LinkTabBar getNodeTabBar();

        LayerContainer getNodeLayerContainer();
    }
}
