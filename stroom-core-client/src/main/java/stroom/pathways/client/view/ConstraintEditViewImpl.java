/*
 * Copyright 2016 Crown Copyright
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

package stroom.pathways.client.view;

import stroom.item.client.SelectionBox;
import stroom.pathways.client.presenter.ConstraintEditPresenter.ConstraintEditView;
import stroom.pathways.shared.pathway.ConstraintValueType;
import stroom.svg.shared.SvgImage;
import stroom.widget.button.client.Button;
import stroom.widget.button.client.InlineSvgButton;
import stroom.widget.tickbox.client.view.CustomCheckBox;

import com.google.gwt.dom.client.Document;
import com.google.gwt.event.dom.client.KeyCodes;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.ui.FlowPanel;
import com.google.gwt.user.client.ui.Label;
import com.google.gwt.user.client.ui.RadioButton;
import com.google.gwt.user.client.ui.TextArea;
import com.google.gwt.user.client.ui.TextBox;
import com.google.gwt.user.client.ui.Widget;
import com.google.inject.Inject;
import com.gwtplatform.mvp.client.ViewImpl;

import java.util.ArrayList;
import java.util.List;

public class ConstraintEditViewImpl extends ViewImpl implements ConstraintEditView {

    private final Widget widget;
    private final List<String> items = new ArrayList<>();
    private final RadioButton trueButton;
    private final RadioButton falseButton;

    @UiField
    TextBox name;
    @UiField
    SelectionBox<ConstraintValueType> type;
    @UiField
    FlowPanel anyEditor;
    @UiField
    FlowPanel textEditor;
    @UiField
    TextBox text;
    @UiField
    FlowPanel durationEditor;
    @UiField
    DurationBox duration;
    @UiField
    FlowPanel setEditor;
    @UiField
    FlowPanel setItems;
    @UiField
    TextArea setInput;
    @UiField
    Button setAdd;
    @UiField
    FlowPanel rangeEditor;
    @UiField
    TextBox min;
    @UiField
    TextBox max;
    @UiField
    FlowPanel durationRangeEditor;
    @UiField
    DurationBox minDuration;
    @UiField
    CustomCheckBox noMin;
    @UiField
    DurationBox maxDuration;
    @UiField
    CustomCheckBox noMax;
    @UiField
    FlowPanel regexEditor;
    @UiField
    TextBox pattern;
    @UiField
    FlowPanel booleanEditor;
    @UiField
    Label error;
    @UiField
    CustomCheckBox optional;

    @Inject
    public ConstraintEditViewImpl(final Binder binder) {
        widget = binder.createAndBindUi(this);
        for (final ConstraintValueType valueType : ConstraintValueType.values()) {
            // A duration range with one end open is picked as a duration range with "No minimum" or
            // "No maximum" ticked.
            if (valueType != ConstraintValueType.DURATION_AT_LEAST
                && valueType != ConstraintValueType.DURATION_AT_MOST) {
                type.addItem(valueType);
            }
        }
        type.addValueChangeHandler(e -> showEditor());

        final String group = Document.get().createUniqueId();
        trueButton = new RadioButton(group, "true");
        falseButton = new RadioButton(group, "false");
        booleanEditor.add(trueButton);
        booleanEditor.add(falseButton);

        setInput.addKeyDownHandler(e -> {
            if (e.getNativeKeyCode() == KeyCodes.KEY_ENTER) {
                e.preventDefault();
                e.stopPropagation();
                addItems();
            }
        });
        setAdd.addClickHandler(e -> addItems());
        noMin.addValueChangeHandler(e -> minDuration.setEnabled(!noMin.getValue()));
        noMax.addValueChangeHandler(e -> maxDuration.setEnabled(!noMax.getValue()));
        showEditor();
    }

    @Override
    public Widget asWidget() {
        return widget;
    }

    @Override
    public void focus() {
        if (name.isEnabled()) {
            name.setFocus(true);
        } else {
            type.focus();
        }
    }

    @Override
    public void clear() {
        text.setText("");
        duration.setNanos(null);
        items.clear();
        drawItems();
        setInput.setText("");
        min.setText("");
        max.setText("");
        setDurationRange(null, null);
        noMin.setValue(false);
        noMax.setValue(false);
        minDuration.setEnabled(true);
        maxDuration.setEnabled(true);
        pattern.setText("");
        setBoolean(true);
        setError(null);
    }

    @Override
    public String getName() {
        return name.getText();
    }

    @Override
    public void setName(final String name) {
        this.name.setText(name);
    }

    @Override
    public void setNameEditable(final boolean editable) {
        name.setEnabled(editable);
    }

    @Override
    public ConstraintValueType getType() {
        return type.getValue();
    }

    @Override
    public void setType(final ConstraintValueType type) {
        this.type.setValue(type);
        showEditor();
    }

    @Override
    public boolean isOptional() {
        return optional.getValue();
    }

    @Override
    public void setOptional(final boolean optional) {
        this.optional.setValue(optional);
    }

    @Override
    public String getText() {
        return text.getText();
    }

    @Override
    public void setText(final String text) {
        this.text.setText(text);
    }

    @Override
    public Long getDuration() {
        return duration.getNanos();
    }

    @Override
    public void setDuration(final Long nanos) {
        duration.setNanos(nanos);
    }

    @Override
    public List<String> getSet() {
        // A value typed but not yet added is taken as well, rather than lost on OK.
        addItems();
        return new ArrayList<>(items);
    }

    @Override
    public void setSet(final List<String> items) {
        this.items.clear();
        this.items.addAll(items);
        drawItems();
    }

    @Override
    public String getMin() {
        return min.getText();
    }

    @Override
    public String getMax() {
        return max.getText();
    }

    @Override
    public void setRange(final String min, final String max) {
        this.min.setText(min);
        this.max.setText(max);
    }

    @Override
    public Long getMinDuration() {
        return minDuration.getNanos();
    }

    @Override
    public Long getMaxDuration() {
        return maxDuration.getNanos();
    }

    @Override
    public boolean isNoMin() {
        return noMin.getValue();
    }

    @Override
    public boolean isNoMax() {
        return noMax.getValue();
    }

    @Override
    public void setDurationRange(final Long min, final Long max) {
        minDuration.setNanos(min);
        maxDuration.setNanos(max);
        noMin.setValue(min == null);
        noMax.setValue(max == null);
        minDuration.setEnabled(min != null);
        maxDuration.setEnabled(max != null);
    }

    @Override
    public String getPattern() {
        return pattern.getText();
    }

    @Override
    public void setPattern(final String pattern) {
        this.pattern.setText(pattern);
    }

    @Override
    public boolean getBoolean() {
        return trueButton.getValue();
    }

    @Override
    public void setBoolean(final boolean value) {
        trueButton.setValue(value);
        falseButton.setValue(!value);
    }

    @Override
    public void setError(final String message) {
        error.setText(message == null
                ? ""
                : message);
        error.setVisible(message != null);
    }

    private void showEditor() {
        final ConstraintValueType valueType = type.getValue();
        anyEditor.setVisible(valueType == null
                             || valueType == ConstraintValueType.ANY
                             || valueType == ConstraintValueType.ANY_BOOLEAN);
        textEditor.setVisible(valueType == ConstraintValueType.STRING
                              || valueType == ConstraintValueType.INTEGER
                              || valueType == ConstraintValueType.LONG
                              || valueType == ConstraintValueType.DOUBLE);
        durationEditor.setVisible(valueType == ConstraintValueType.DURATION_VALUE);
        setEditor.setVisible(valueType == ConstraintValueType.STRING_SET
                             || valueType == ConstraintValueType.INTEGER_SET
                             || valueType == ConstraintValueType.LONG_SET
                             || valueType == ConstraintValueType.DOUBLE_SET);
        rangeEditor.setVisible(valueType == ConstraintValueType.INTEGER_RANGE
                               || valueType == ConstraintValueType.LONG_RANGE
                               || valueType == ConstraintValueType.DOUBLE_RANGE);
        durationRangeEditor.setVisible(valueType == ConstraintValueType.DURATION_RANGE);
        regexEditor.setVisible(valueType == ConstraintValueType.REGEX);
        booleanEditor.setVisible(valueType == ConstraintValueType.BOOLEAN);
        setError(null);
    }

    // Takes whatever is in the add box, one value per line, and drops any already in the set.
    private void addItems() {
        for (final String line : setInput.getText().split("\n")) {
            final String item = line.trim();
            if (!item.isEmpty() && !items.contains(item)) {
                items.add(item);
            }
        }
        setInput.setText("");
        drawItems();
    }

    private void drawItems() {
        setItems.clear();
        for (final String item : items) {
            final FlowPanel row = new FlowPanel();
            row.addStyleName("constraint-set-item");
            final Label label = new Label(item);
            label.addStyleName("constraint-set-value");
            final InlineSvgButton remove = new InlineSvgButton();
            remove.setSvg(SvgImage.CLOSE);
            remove.setTitle("Remove " + item);
            remove.addClickHandler(e -> {
                items.remove(item);
                drawItems();
            });
            row.add(label);
            row.add(remove);
            setItems.add(row);
        }
    }

    public interface Binder extends UiBinder<Widget, ConstraintEditViewImpl> {

    }
}
