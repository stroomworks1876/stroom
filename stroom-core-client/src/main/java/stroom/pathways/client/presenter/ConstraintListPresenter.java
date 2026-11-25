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

import stroom.alert.client.event.ConfirmEvent;
import stroom.cell.tickbox.client.TickBoxCell;
import stroom.cell.tickbox.shared.TickBoxState;
import stroom.data.grid.client.MyDataGrid;
import stroom.data.grid.client.PagerView;
import stroom.dispatch.client.RestFactory;
import stroom.pathways.shared.pathway.Constraint;
import stroom.pathways.shared.pathway.PathNode;
import stroom.svg.client.SvgPresets;
import stroom.util.client.DataGridUtil;
import stroom.util.shared.NullSafe;
import stroom.widget.button.client.ButtonView;
import stroom.widget.util.client.MouseUtil;
import stroom.widget.util.client.MultiSelectionModelImpl;

import com.google.gwt.user.cellview.client.Column;
import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;
import com.gwtplatform.mvp.client.MyPresenterWidget;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

public class ConstraintListPresenter
        extends MyPresenterWidget<PagerView> {

//    private static final ConstraintsResource PATHWAYS_RESOURCE = GWT.create(ConstraintsResource.class);

    private final PagerView pagerView;
    private final RestFactory restFactory;
    private final MyDataGrid<Constraint> dataGrid;
    private final MultiSelectionModelImpl<Constraint> selectionModel;
    private final ConstraintEditPresenter constraintEditPresenter;
    private final ButtonView newButton;
    private final ButtonView editButton;
    private final ButtonView removeButton;


    private PathNode pathNode;
    private Set<String> changed = Collections.emptySet();

    //    private String filter;
    private boolean readOnly = true;
    private boolean editable;
    private Consumer<List<String>> removeHandler;
    private EditHandler editHandler;
    private boolean sending;

    @Inject
    public ConstraintListPresenter(final EventBus eventBus,
                                   final PagerView view,
                                   final RestFactory restFactory,
                                   final ConstraintEditPresenter constraintEditPresenter) {
        super(eventBus, view);
        this.pagerView = view;
        this.restFactory = restFactory;
//        view.setDataView(pagerView);
//        view.setUiHandlers(this);

        dataGrid = new MyDataGrid<>(this);
        dataGrid.setTableName("Constraints");
        selectionModel = dataGrid.addDefaultSelectionModel(true);
        pagerView.setDataWidget(dataGrid);
        // What this shows is the node picked out on the drawing, so it has nothing of its own to fetch
        // again. Refreshing is done where the model is fetched, and this follows.
        pagerView.getRefreshButton().setVisible(false);

        this.constraintEditPresenter = constraintEditPresenter;

        newButton = pagerView.addButton(SvgPresets.NEW_ITEM);
        editButton = pagerView.addButton(SvgPresets.EDIT);
        removeButton = pagerView.addButton(SvgPresets.DELETE);

        addColumns();
        // Which rows are marked changes with the selection rather than with the data, so the style is
        // asked for at draw time rather than baked into a row.
        dataGrid.setRowStyles((constraint, index) ->
                constraint != null && changed.contains(constraint.getName())
                        ? "constraint-row--changed"
                        : "");
        enableButtons();
    }

    @Override
    protected void onBind() {
        super.onBind();

        registerHandler(newButton.addClickHandler(event -> {
            if (!readOnly) {
                if (MouseUtil.isPrimary(event)) {
                    edit(null, "New Constraint");
                }
            }
        }));
        registerHandler(editButton.addClickHandler(event -> {
            if (!readOnly) {
                if (MouseUtil.isPrimary(event)) {
                    onEdit();
                }
            }
        }));
        registerHandler(removeButton.addClickHandler(event -> {
            if (!readOnly) {
                if (MouseUtil.isPrimary(event)) {
                    onRemove();
                }
            }
        }));
        registerHandler(selectionModel.addSelectionHandler(event -> {
            if (!readOnly) {
                enableButtons();
                if (editable && event.getSelectionType().isDoubleSelect()) {
                    onEdit();
                }
            }
        }));
//        registerHandler(dataGrid.addColumnSortHandler(event -> refresh()));
    }

//    @Override
//    public void onFilterChange(final String text) {
//        filter = text;
////        refresh();
//    }

    private void enableButtons() {
        final boolean enabled = !readOnly && editable;
        newButton.setEnabled(enabled && pathNode != null);
        editButton.setEnabled(enabled && selectionModel.getSelected() != null);
        removeButton.setEnabled(enabled && !selectionModel.getSelectedItems().isEmpty());

        if (readOnly) {
            newButton.setTitle("New constraint disabled as read only");
            editButton.setTitle("Edit constraint disabled as read only");
            removeButton.setTitle("Remove constraint disabled as read only");
        } else if (editable) {
            newButton.setTitle("New Constraint");
            editButton.setTitle("Edit Constraint");
            removeButton.setTitle("Remove Constraint");
        } else {
            newButton.setTitle("New constraint only works on the model as it stands now");
            editButton.setTitle("Edit constraint only works on the model as it stands now");
            removeButton.setTitle("Remove constraint only works on the model as it stands now");
        }
    }

    private void addColumns() {
        addNameColumn();
        addTypeColumn();
        addValueColumn();
        addOptionalColumn();
    }

    // How much this constraint has been checked and how often it moved. Uses are counted per span, so
    // a node one trace reached forty times adds forty; updates are counted per change.
    //
    // Wide enough for the longest names a model holds, which are the attributes — the room past that
    // is worth more to the value beside it, which is a whole sql statement or a set of names.
    private void addNameColumn() {
        addTextColumn("Name", 220, Constraint::getName);
    }

    private void addTextColumn(final String name, final int width, final Function<Constraint, String> function) {
        final Column<Constraint, String> column = DataGridUtil.textColumnBuilder(function)
                .withSorting(name)
                .build();
        dataGrid.addResizableColumn(column,
                name,
                width);
//        dataGrid.sort(column);
    }

    private void addTypeColumn() {
        addTextColumn("Type", 100, constraint -> {
            if (constraint == null || constraint.getValue() == null) {
                return null;
            }
            return constraint.getValue().valueType().getDisplayValue();
        });
    }

    private void addValueColumn() {
        addTextColumn("Value", 400, constraint -> {
            if (constraint == null || constraint.getValue() == null) {
                return null;
            }
            return constraint.getValue().toString();
        });
    }

    private void addOptionalColumn() {
        final boolean updateable = false;
        final TickBoxCell.Appearance appearance = updateable
                ? new TickBoxCell.DefaultAppearance()
                : new TickBoxCell.NoBorderAppearance();

        final Function<Constraint, TickBoxState> valueExtractor = constraint -> {
            if (constraint == null || constraint.getValue() == null) {
                return null;
            }
            return TickBoxState.fromBoolean(constraint.isOptional());
        };

        final Column<Constraint, TickBoxState> selectionColumn = DataGridUtil.columnBuilder(
                        valueExtractor, () -> TickBoxCell.create(
                                appearance, true, true, updateable))
                .centerAligned()
                .build();
        dataGrid.addColumn(selectionColumn,
                "Optional",
                100);
    }

    private void onEdit() {
        final Constraint selected = selectionModel.getSelected();
        if (selected != null) {
            edit(selected, "Edit Constraint");
        }
    }

    // The dialog stays open while what was entered is not valid, and while the server has not yet
    // accepted it. A refusal shows the server's reason in the dialog. Nothing is sent where the
    // constraint comes back as it went in. While a change is with the server, OK and Cancel are
    // ignored, so the dialog can neither close nor send it twice.
    private void edit(final Constraint previous, final String caption) {
        sending = false;
        constraintEditPresenter.read(previous);
        constraintEditPresenter.show(caption, e -> {
            if (sending) {
                return;
            }
            if (!e.isOk()) {
                e.hide();
                return;
            }
            final Constraint constraint = constraintEditPresenter.write();
            if (constraint == null) {
                e.reset();
                return;
            }
            if (constraint.equals(previous) || editHandler == null) {
                e.hide();
                return;
            }
            sending = true;
            editHandler.edit(previous, constraint, () -> {
                sending = false;
                e.hide();
            }, reason -> {
                sending = false;
                constraintEditPresenter.getView().setError(reason);
                e.reset();
            });
        });
    }

    private void onRemove() {
        final List<Constraint> list = selectionModel.getSelectedItems();
        if (list != null && !list.isEmpty()) {
            final String message = "Are you sure you want to delete the selected constraint"
                                   + (list.size() > 1 ? "s?" : "?");

            ConfirmEvent.fire(this, message, result -> {
                if (result && removeHandler != null) {
                    removeHandler.accept(list.stream()
                            .map(Constraint::getName)
                            .collect(Collectors.toList()));
                }
            });
        }
    }

    public void setRemoveHandler(final Consumer<List<String>> removeHandler) {
        this.removeHandler = removeHandler;
    }

    public void setEditHandler(final EditHandler editHandler) {
        this.editHandler = editHandler;
    }

    /**
     * @param editable whether adding, changing and removing constraints is allowed, which is only so
     *                 while the node shown is the model as it stands now.
     * @param changed the constraints the change being looked at moved, which are marked so the reader
     *                can see which of a node's constraints that change was about. Empty where no
     *                change is being looked at.
     */
    public void setData(final PathNode pathNode,
                        final boolean readOnly,
                        final boolean editable,
                        final Set<String> changed) {
        this.pathNode = pathNode;
        this.readOnly = readOnly;
        this.editable = editable;
        this.changed = NullSafe.set(changed);
        selectionModel.clear(false);
        refresh();
        enableButtons();
    }

    private void refresh() {
        final List<Constraint> constraints = getConstraintList(pathNode);
        dataGrid.setRowData(constraints);
        dataGrid.setRowCount(constraints.size());
    }

    private List<Constraint> getConstraintList(final PathNode pathNode) {
        final List<Constraint> list = new ArrayList<>();

        if (pathNode != null) {
            final Map<String, Constraint> constraints = pathNode.getConstraints();
            if (constraints != null) {
                list.addAll(constraints
                        .values()
                        .stream()
                        .sorted(Comparator.comparing(Constraint::getName))
                        .collect(Collectors.toList()));

//
//
//            if (constraints.getDuration() != null) {
//                list.add(new Constraint("duration", constraints.getDuration(), false));
//            }
//            if (constraints.getFlags() != null) {
//                list.add(new Constraint("flags", constraints.getFlags(), false));
//            }
//            if (constraints.getKind() != null) {
//                list.add(new Constraint("kind", constraints.getKind(), false));
//            }
//
//            final Map<String, ConstraintValue> requiredAttributes = NullSafe.map(constraints.getRequiredAttributes());
//            final Map<String, ConstraintValue> optionalAttributes = NullSafe.map(constraints.getOptionalAttributes());
//            final Set<String> keys = new HashSet<>(requiredAttributes.keySet());
//            keys.addAll(optionalAttributes.keySet());
//            final List<String> sortedKeys = keys.stream().sorted().collect(Collectors.toList());
//            for (final String key : sortedKeys) {
//                final ConstraintValue required = requiredAttributes.get(key);
//                if (required != null) {
//                    list.add(new Constraint("attribute." + key, required, false));
//                }
//                final ConstraintValue optional = optionalAttributes.get(key);
//                if (optional != null) {
//                    list.add(new Constraint("attribute." + key, optional, true));
//                }
//            }
            }
        }
        return list;
    }

//
//    private void refresh() {
//        if (dataProvider == null) {
//            dataProvider = new RestDataProvider<Constraint, ResultPage<Constraint>>(getEventBus()) {
//                @Override
//                protected void exec(final Range range,
//                                    final Consumer<ResultPage<Constraint>> dataConsumer,
//                                    final RestErrorHandler errorHandler) {
//                    final FindConstraintCriteria criteria = new FindConstraintCriteria(
//                            CriteriaUtil.createPageRequest(range),
//                            CriteriaUtil.createSortList(dataGrid.getColumnSortList()),
//                            docRef,
//                            filter,
//                            null);
//
//                    restFactory
//                            .create(PATHWAYS_RESOURCE)
//                            .method(res -> res.findConstraints(criteria))
//                            .onSuccess(dataConsumer)
//                            .onFailure(errorHandler)
//                            .taskMonitorFactory(pagerView)
//                            .exec();
//                }
//            };
//            dataProvider.addDataDisplay(dataGrid);
//
//        } else {
//            dataProvider.refresh();
//        }
//    }

    /**
     * Told when a constraint is added or changed in the dialog. The dialog stays open until one of the
     * two outcomes is called.
     */
    public interface EditHandler {

        /**
         * @param previous   the constraint as the dialog first showed it, null when adding.
         * @param constraint the constraint the dialog ended with.
         * @param accepted   to call when the server accepts the change, or when there is no node to
         *                   send it for; closes the dialog.
         * @param refused    to call with the reason when the server refuses the change; shows the reason
         *                   in the dialog and leaves it open.
         */
        void edit(Constraint previous, Constraint constraint, Runnable accepted, Consumer<String> refused);
    }
}
