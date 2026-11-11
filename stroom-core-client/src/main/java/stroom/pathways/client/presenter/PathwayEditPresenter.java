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
import stroom.content.client.event.RefreshContentTabEvent;
import stroom.content.client.presenter.ContentTabPresenter;
import stroom.core.client.HasSave;
import stroom.core.client.event.CloseContentEvent;
import stroom.core.client.event.CloseContentEvent.DirtyMode;
import stroom.dispatch.client.DefaultErrorHandler;
import stroom.dispatch.client.RestFactory;
import stroom.docref.DocRef;
import stroom.pathways.client.presenter.PathwayEditPresenter.PathwayEditView;
import stroom.pathways.shared.FetchPathwayRequest;
import stroom.pathways.shared.FindPathwayMutationCriteria;
import stroom.pathways.shared.PathwayAsAt;
import stroom.pathways.shared.PathwaysDoc;
import stroom.pathways.shared.PathwaysResource;
import stroom.pathways.shared.UpdatePathway;
import stroom.pathways.shared.otel.trace.NanoTime;
import stroom.pathways.shared.pathway.PathNode;
import stroom.pathways.shared.pathway.PathUse;
import stroom.pathways.shared.pathway.Paths;
import stroom.pathways.shared.pathway.Pathway;
import stroom.pathways.shared.pathway.PathwayMutation;
import stroom.query.api.TimeRange;
import stroom.query.api.TimeRanges;
import stroom.query.client.view.TimeRangeSelector;
import stroom.svg.client.Preset;
import stroom.svg.client.SvgPresets;
import stroom.svg.shared.SvgImage;
import stroom.util.shared.CriteriaFieldSort;
import stroom.util.shared.NullSafe;
import stroom.util.shared.PageRequest;
import stroom.util.shared.PageResponse;
import stroom.widget.button.client.ButtonView;
import stroom.widget.menu.client.presenter.IconMenuItem;
import stroom.widget.menu.client.presenter.Item;
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
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * One pathway open as a tab of its own. A tab rather than a dialog because reading a model is not a
 * question to be answered and dismissed — two can be held open side by side, and the drawing keeps
 * whatever the reader had scrolled to while they look at something else.
 */
public class PathwayEditPresenter
        extends ContentTabPresenter<PathwayEditView>
        implements HasSave, CloseContentEvent.Handler {

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
    private PathUse drawnPath;
    private DocRef docRef;
    // The name the pathway was opened under, which is what it is saved back against. Held rather than
    // read off the model, so a tab can be labelled before the model it is waiting for arrives.
    private String name;
    private boolean dirty;
    // What the tab was last called. The label carries whether there is anything unsaved, so it has to
    // be repainted when that changes — and only then.
    private String lastLabel;
    // Told when a save lands, so the list this was opened from can show the new size and time.
    private Runnable savedHandler;
    // Whether the split between the drawing and the constraints has been set. Once only, so that
    // reading something else and coming back does not undo wherever the reader dragged it to.
    private boolean split;
    private List<PathwayMutation> history = Collections.emptyList();
    // The span the tab is showing, and what the server sent back for it. One field rather than one
    // per reader, so the toolbar, the tables and the drawing cannot disagree about what is on screen.
    // Held as it was picked: a range is written as expressions, and resolving them happens where the
    // parser for them lives, which is not here.
    private TimeRange timeRange;
    private PathwayAsAt asAt;
    // What came back for the range with no change picked out. Kept so that letting go of a row does
    // not ask the server for something already in hand.
    private PathwayAsAt rangeAsAt;
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
    private final ButtonView saveButton;
    private final ButtonView playButton;
    private final ButtonView stopButton;
    private final Timer stepper = new Timer() {
        @Override
        public void run() {
            if (!mutationListPresenter.selectNextTrace()) {
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

        // On the tab's own toolbar rather than on any one panel's, because it is the pathway that is
        // saved rather than anything on show.
        saveButton = view.addButton(SvgPresets.SAVE);
        // At the far end of the same row as Save: what the tab does on the left, what it is showing
        // on the right.
        view.addToolbarWidget(timeRangeSelector);
        saveButton.setEnabled(false);

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
        registerHandler(saveButton.addClickHandler(e -> save()));
        // The end of the range picked is the moment the tab answers for. Asked of the server rather
        // than worked out here: the changes it is counted from are filed by the day their work ran,
        // so a date never promises an unbroken run of them.
        registerHandler(timeRangeSelector.addValueChangeHandler(e -> showOver(e.getValue())));
        // Constraints are the only thing on this screen that writes, and they are written straight
        // into the model the moment the constraint dialog is accepted. This is how the tab learns
        // there is something to save.
        registerHandler(constraintListPresenter.addDirtyHandler(e -> setDirty(true)));
        registerHandler(playButton.addClickHandler(e -> play()));
        registerHandler(stopButton.addClickHandler(e -> stop()));
        // Still driven by which drawing is on show: only the graph is worth watching change, and the
        // tree says the same thing a row at a time.
        pathwayTreePresenter.setViewChangeHandler(this::showPlayButtons);

        // What a reader can ask of a node, offered where the node is. Built here rather than on the
        // drawing because everything it offers narrows the table beside it, and the drawing knows
        // nothing of that.
        pathwayTreePresenter.setNodeMenuHandler(this::showNodeMenu);

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
            if (!Objects.equals(drawnChange, mutationListPresenter.getSelectedSequence())) {
                showAtChange(mutationListPresenter.getSelectedSequence());
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
        // The same span the drawing answers for, so the table beside it lists the changes it was
        // made from rather than every change there has ever been.
        final FindPathwayMutationCriteria criteria = new FindPathwayMutationCriteria(
                new PageRequest(0, MAX_HISTORY),
                List.of(new CriteriaFieldSort(PathwayMutation.FIELD_TIME, false, false)),
                docRef,
                name,
                timeRange);

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
                    mutationListPresenter.setData(history,
                            result.getWindowFrom(), result.getWindowTo());
                    historyArrived = true;
                    drawWhenReady();
                })
                .onFailure(new DefaultErrorHandler(this, () -> {
                    // Asked and not answered. The drawing waits for these, so it has to be told that
                    // none are coming or it waits for ever.
                    historyArrived = true;
                    drawWhenReady();
                }))
                .taskMonitorFactory(this)
                .exec();
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

    // Steps to the next trace every second and a half, so the model can be watched changing rather
    // than having to be clicked through — long enough to take in what moved between one trace and
    // the next, which is the point of watching it.
    private void play() {
        if (!mutationListPresenter.selectNextTrace()) {
            // Nothing after the one picked, which is where a replay ends and where the top of the list
            // starts. Pressing play there means watch it again, so it goes back to the beginning
            // rather than doing nothing. Cleared without telling anyone: the step taken straight after
            // draws the model, and announcing the clearing first would draw it twice.
            mutationListPresenter.getSelectionModel().clear(false);
            if (!mutationListPresenter.selectNextTrace()) {
                // No trace taught this model anything, so there is nothing to watch.
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

    // Narrowing the paths to a node, from the drawing. The node has already been picked out by the
    // time this runs, so every entry acts on the one the reader asked about.
    private void showNodeMenu(final PathNode node, final PopupPosition position) {
        if (node == null) {
            return;
        }
        // What the menu offers is what the table under it can do, so it follows whichever tab is up.
        final List<Item> items = changesTab.equals(selectedTab)
                ? changeFilterItems()
                : pathFilterItems();

        ShowMenuEvent
                .builder()
                .items(items)
                .popupPosition(position)
                .fire(this);
    }

    private void showPlayButtons() {
        final boolean graph = pathwayTreePresenter.isGraphShown();
        playButton.setVisible(graph && !stepper.isRunning());
        stopButton.setVisible(graph && stepper.isRunning());
        if (!graph) {
            stepper.cancel();
        }
    }

    // The model as it stood at the change being looked at, or as it stands now where none is.
    private void showModel() {
        if (pathway == null) {
            return;
        }

        drawnChange = mutationListPresenter.getSelectedSequence();
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

        // Winding the model back takes nodes out of it. Placing what is left from the model as it
        // stands now keeps every node where it was, rather than closing the gaps and moving
        // everything the reader was looking at.
        //
        // A folded model is laid out from itself instead. It names its nodes by a stand-in worked
        // out from the path of span names that reaches each one, so looking them up among the
        // learnt model's uuids finds nothing and the drawing comes out empty. It is also not wound
        // back from anything, so there are no gaps to hold open. And it can hold nodes the learnt
        // model never had, from history loaded after the fact, which the layout would leave out.
        final boolean folded = NullSafe.isTrue(asAt, PathwayAsAt::isFolded);
        pathwayTreePresenter.setLayout(folded
                ? NullSafe.get(shown, Pathway::getRoot)
                : pathway.getRoot());
        pathwayTreePresenter.setFigures(asAt);
        pathwayTreePresenter.setAsAt(NullSafe.get(asAt, PathwayAsAt::getAsAt),
                mutationListPresenter.getSelectedSequence());
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

    private void showSettledConstraints() {
        final PathNode node = pathwayTreePresenter.getSelectionModel().getSelectedObject();
        constraintListPresenter.setData(node,
                readOnly,
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
     * Opens one pathway. The model is fetched here rather than handed over, so a tab holds its own
     * copy — constraints are edited straight into it, and a shared one would carry those edits into
     * every other screen showing the same pathway whether or not they were ever saved.
     */
    public void read(final PathwaysDoc pathwaysDoc, final String name, final boolean readOnly) {
        this.readOnly = readOnly;
        this.docRef = pathwaysDoc.asDocRef();
        this.name = name;
        setDirty(false);
        // Opened on the model as it stands. Nothing older is read until a range is picked that
        // reaches further back.
        timeRangeSelector.setValue(TimeRanges.ALL_TIME);
        showOver(TimeRanges.ALL_TIME);
    }

    // A different range is a different set of rows, so both tables are rebuilt and the histogram
    // with them. The drawing answers for the latest time in it, which the server works out.
    private void showOver(final TimeRange wanted) {
        timeRange = wanted;
        fetch(FetchPathwayRequest.over(docRef, name, wanted), this::show);
    }

    // A change picked out of the table, or null to go back to the model as it stands. Only the
    // drawing moves: the row picked is still a row of the same table, so rebuilding it would take
    // the selection away and send the histogram back to where it started.
    private void showAtChange(final Long sequence) {
        if (sequence == null) {
            // Nothing picked out, which is the range's own model - already fetched and still here.
            showPosition(rangeAsAt);
            return;
        }
        fetch(FetchPathwayRequest.atChange(docRef, name, sequence), this::showPosition);
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

    // What came back for a position, which is the drawing and nothing else.
    private void showPosition(final PathwayAsAt asAt) {
        this.asAt = asAt;
        showModel();
    }

    private void show(final PathwayAsAt asAt) {
        this.asAt = asAt;
        this.rangeAsAt = asAt;
        final Pathway fetched = NullSafe.get(asAt, PathwayAsAt::getPathway);
        // The model as it stands is what the drawing is laid out from, so a wound-back one is kept
        // beside it rather than over it: placing what is left where it already was keeps every node
        // still, rather than closing the gaps and moving everything the reader was looking at.
        if (asAt != null && asAt.getAsAt() == null && fetched != null) {
            this.pathway = fetched;
        }
        final Pathway pathway = this.pathway;
        if (pathway == null) {
            return;
        }

        // Where the pathway has learnt from, which both tables name their rows' stores by position in.
        // It comes with the model rather than from the configuration, so a store pointed elsewhere
        // since does not take its old rows' trace links with it.
        final List<String> sources = NullSafe.get(pathway.getPaths(), Paths::getSources);
        pathListPresenter.setSources(sources);
        mutationListPresenter.setSources(sources);

        // The paths arrive on the pathway itself, so they are on show as soon as it is opened rather
        // than waiting on the history the way the changes do.
        pathListPresenter.setData(pathway);

        // The drawing is coloured and sized from the changes behind the model, which are asked for at
        // the end of this. Nothing is handed over until they arrive: an empty list would say this
        // pathway's changes had arrived and were none, and the drawing would be made with every node
        // at its smallest.
        pathwayTreePresenter.read(pathway);
        showConstraints();
        showPlayButtons();
        mutationListPresenter.setData(Collections.emptyList());

        // Only the changes. How busy each node was comes back with the model now, so the readings
        // are no longer asked for separately.
        historyArrived = false;
        fetchHistory(docRef, pathway.getName());
    }

    private Pathway write() {
        // A pathway is named after the operation it was learnt from, which is how it is found again.
        // Nothing here can rename it, so the name is carried through rather than read back.
        final NanoTime now = NanoTime.ofMillis(System.currentTimeMillis());
        return pathway.copy().updateTime(now).build();
    }

    /**
     * Whether anything has been changed since the pathway was opened or last saved. What the Save
     * button, the Save menu item and the prompt on closing all read.
     */
    @Override
    public boolean isDirty() {
        return dirty;
    }

    @Override
    public void save() {
        if (!dirty || pathway == null) {
            return;
        }
        try {
            restFactory
                    .create(PATHWAYS_RESOURCE)
                    .method(res -> res.updatePathway(new UpdatePathway(docRef, name, write())))
                    .onSuccess(response -> {
                        setDirty(false);
                        if (savedHandler != null) {
                            savedHandler.run();
                        }
                    })
                    .onFailure(new DefaultErrorHandler(this, null))
                    .taskMonitorFactory(this)
                    .exec();
        } catch (final RuntimeException e) {
            AlertEvent.fireError(this, e.getMessage(), null);
        }
    }

    /**
     * Told whenever a save lands, so whatever opened this can show the pathway's new size and time.
     */
    public void setSavedHandler(final Runnable savedHandler) {
        this.savedHandler = savedHandler;
    }

    private void setDirty(final boolean dirty) {
        this.dirty = dirty;
        saveButton.setEnabled(dirty);
        // The label says whether there is anything unsaved, so the tab is repainted when that changes
        // and not on every edit. Nothing is repainted the first time, which is the pathway being read
        // before its tab exists to carry a label.
        final String label = getLabel();
        final String was = lastLabel;
        lastLabel = label;
        if (was != null && !was.equals(label)) {
            RefreshContentTabEvent.fire(this, this);
        }
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
        final DirtyMode dirtyMode = event.getDirtyMode();
        if (!dirty || DirtyMode.FORCE == dirtyMode) {
            close(event, true);
        } else if (DirtyMode.CONFIRM_DIRTY == dirtyMode) {
            ConfirmEvent.fire(this,
                    "Pathway '" + name + "' has unsaved changes. Are you sure you want to close it?",
                    ok -> close(event, ok));
        }
        // SKIP_DIRTY leaves a pathway with unsaved changes open, which is what it asks for.
    }

    private void close(final CloseContentEvent event, final boolean ok) {
        if (ok) {
            // Nothing to step through once the tab has gone, and a clock left running would keep
            // moving a selection in a list nobody is looking at.
            stop();
        }
        event.getCallback().closeTab(ok);
    }

    @Override
    public String getLabel() {
        return dirty
                ? "* " + name
                : name;
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

        ButtonView addButton(Preset preset);

        /**
         * Puts a widget in the toolbar beside the buttons, for the date the tab is showing.
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
