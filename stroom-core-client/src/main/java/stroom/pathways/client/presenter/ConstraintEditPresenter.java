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

import stroom.pathways.client.presenter.ConstraintEditPresenter.ConstraintEditView;
import stroom.pathways.shared.otel.trace.NanoTime;
import stroom.pathways.shared.pathway.AbstractRange;
import stroom.pathways.shared.pathway.AbstractSet;
import stroom.pathways.shared.pathway.AnyBoolean;
import stroom.pathways.shared.pathway.AnyTypeValue;
import stroom.pathways.shared.pathway.BooleanValue;
import stroom.pathways.shared.pathway.Constraint;
import stroom.pathways.shared.pathway.ConstraintValue;
import stroom.pathways.shared.pathway.ConstraintValueType;
import stroom.pathways.shared.pathway.DoubleRange;
import stroom.pathways.shared.pathway.DoubleSet;
import stroom.pathways.shared.pathway.DoubleValue;
import stroom.pathways.shared.pathway.IntegerRange;
import stroom.pathways.shared.pathway.IntegerSet;
import stroom.pathways.shared.pathway.IntegerValue;
import stroom.pathways.shared.pathway.LongRange;
import stroom.pathways.shared.pathway.LongSet;
import stroom.pathways.shared.pathway.LongValue;
import stroom.pathways.shared.pathway.NanoTimeRange;
import stroom.pathways.shared.pathway.NanoTimeValue;
import stroom.pathways.shared.pathway.Regex;
import stroom.pathways.shared.pathway.StringSet;
import stroom.pathways.shared.pathway.StringValue;
import stroom.widget.popup.client.event.HidePopupRequestEvent;
import stroom.widget.popup.client.event.ShowPopupEvent;
import stroom.widget.popup.client.presenter.PopupSize;
import stroom.widget.popup.client.presenter.PopupType;

import com.google.gwt.user.client.ui.Focus;
import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;
import com.gwtplatform.mvp.client.MyPresenterWidget;
import com.gwtplatform.mvp.client.View;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * The dialog that adds a constraint to a node, or changes the value or optional flag of one it has.
 * Each type of value has an editor of its own.
 */
public class ConstraintEditPresenter extends MyPresenterWidget<ConstraintEditView> {

    private Constraint constraint;

    @Inject
    public ConstraintEditPresenter(final EventBus eventBus, final ConstraintEditView view) {
        super(eventBus, view);
    }

    /**
     * @param constraint the constraint to change, or null to add one. The name can only be typed when
     *                   adding.
     */
    public void read(final Constraint constraint) {
        this.constraint = constraint;
        final ConstraintEditView view = getView();
        view.clear();
        view.setName(constraint == null
                ? ""
                : constraint.getName());
        view.setNameEditable(constraint == null);
        view.setOptional(constraint != null && constraint.isOptional());

        final ConstraintValue value = constraint == null
                ? null
                : constraint.getValue();
        if (value == null) {
            view.setType(ConstraintValueType.STRING);
            return;
        }
        final ConstraintValueType type = value.valueType();
        switch (type) {
            case DURATION_VALUE -> view.setDuration(((NanoTimeValue) value).getValue().toEpochNanos());
            case DURATION_RANGE, DURATION_AT_LEAST, DURATION_AT_MOST -> {
                final NanoTimeRange range = (NanoTimeRange) value;
                view.setDurationRange(nanos(range.getMin()), nanos(range.getMax()));
            }
            case STRING -> view.setText(((StringValue) value).getValue());
            case INTEGER, LONG, DOUBLE -> view.setText(value.toString());
            case STRING_SET, INTEGER_SET, LONG_SET, DOUBLE_SET -> {
                final List<String> items = new ArrayList<>();
                ((AbstractSet<?>) value).getSet().forEach(item -> items.add(String.valueOf(item)));
                items.sort(String::compareTo);
                view.setSet(items);
            }
            case INTEGER_RANGE, LONG_RANGE, DOUBLE_RANGE -> {
                final AbstractRange<?> range = (AbstractRange<?>) value;
                view.setRange(String.valueOf(range.getMin()), String.valueOf(range.getMax()));
            }
            case REGEX -> view.setPattern(((Regex) value).getValue());
            case BOOLEAN -> view.setBoolean(Boolean.TRUE.equals(((BooleanValue) value).getValue()));
            default -> {
                // Any and Any Boolean have nothing to show.
            }
        }
        view.setType(type == ConstraintValueType.DURATION_AT_LEAST || type == ConstraintValueType.DURATION_AT_MOST
                ? ConstraintValueType.DURATION_RANGE
                : type);
    }

    private static Long nanos(final NanoTime nanoTime) {
        return nanoTime == null
                ? null
                : nanoTime.toEpochNanos();
    }

    /**
     * @return the constraint as entered, or null where something entered is not valid, which the
     * dialog then says beside the value.
     */
    public Constraint write() {
        final String name = getView().getName().trim();
        if (name.isEmpty()) {
            getView().setError("A constraint must have a name");
            return null;
        }
        final ConstraintValue value;
        try {
            value = value(getView().getType());
        } catch (final IllegalArgumentException e) {
            getView().setError(e.getMessage());
            return null;
        }
        getView().setError(null);

        final Constraint.Builder builder = constraint == null
                ? Constraint.builder()
                : constraint.copy();
        return builder.name(name).value(value).optional(getView().isOptional()).build();
    }

    private ConstraintValue value(final ConstraintValueType type) {
        final ConstraintEditView view = getView();
        return switch (type) {
            case ANY -> new AnyTypeValue();
            case ANY_BOOLEAN -> new AnyBoolean();
            case STRING -> new StringValue(view.getText());
            case INTEGER -> new IntegerValue(parse(view.getText(), Integer::valueOf));
            case LONG -> new LongValue(parse(view.getText(), Long::valueOf));
            case DOUBLE -> new DoubleValue(parse(view.getText(), Double::valueOf));
            case DURATION_VALUE -> new NanoTimeValue(NanoTime.ofNanos(duration(view.getDuration())));
            case DURATION_RANGE, DURATION_AT_LEAST, DURATION_AT_MOST -> durationRange();
            case STRING_SET -> new StringSet(set(Function.identity()));
            case INTEGER_SET -> new IntegerSet(set(item -> parse(item, Integer::valueOf)));
            case LONG_SET -> new LongSet(set(item -> parse(item, Long::valueOf)));
            case DOUBLE_SET -> new DoubleSet(set(item -> parse(item, Double::valueOf)));
            case INTEGER_RANGE -> range(Integer::valueOf, IntegerRange::new);
            case LONG_RANGE -> range(Long::valueOf, LongRange::new);
            case DOUBLE_RANGE -> range(Double::valueOf, DoubleRange::new);
            case REGEX -> regex();
            case BOOLEAN -> new BooleanValue(view.getBoolean());
        };
    }

    private NanoTimeRange durationRange() {
        final ConstraintEditView view = getView();
        if (view.isNoMin() && view.isNoMax()) {
            throw new IllegalArgumentException("A duration range needs a minimum or a maximum");
        }
        final Long min = view.isNoMin()
                ? null
                : duration(view.getMinDuration());
        final Long max = view.isNoMax()
                ? null
                : duration(view.getMaxDuration());
        if (min != null && max != null) {
            checked(min, max);
        }
        return new NanoTimeRange(min == null
                ? null
                : NanoTime.ofNanos(min),
                max == null
                        ? null
                        : NanoTime.ofNanos(max));
    }

    private <T extends Comparable<T>> ConstraintValue range(final Function<String, T> parser,
                                                            final BiFunction<T, T, ConstraintValue> maker) {
        final T min = parse(getView().getMin(), parser);
        final T max = parse(getView().getMax(), parser);
        checked(min, max);
        return maker.apply(min, max);
    }

    private static long duration(final Long nanos) {
        if (nanos == null) {
            throw new IllegalArgumentException("A duration must be a number of zero or more");
        }
        return nanos;
    }

    private <T> Set<T> set(final Function<String, T> parser) {
        final List<String> items = getView().getSet();
        if (items.isEmpty()) {
            throw new IllegalArgumentException("A set needs at least one value");
        }
        final Set<T> set = new HashSet<>();
        for (final String item : items) {
            set.add(parser.apply(item));
        }
        return set;
    }

    private Regex regex() {
        final String pattern = getView().getPattern();
        if (pattern == null || pattern.isEmpty()) {
            throw new IllegalArgumentException("A pattern cannot be empty");
        }
        return new Regex(pattern);
    }

    private static <T> T parse(final String text, final Function<String, T> parser) {
        final String trimmed = text == null
                ? ""
                : text.trim();
        try {
            return parser.apply(trimmed);
        } catch (final NumberFormatException e) {
            throw new IllegalArgumentException(trimmed.isEmpty()
                    ? "A number is needed"
                    : "'" + trimmed + "' is not a number of this type");
        }
    }

    private static <T extends Comparable<T>> void checked(final T min, final T max) {
        if (min.compareTo(max) > 0) {
            throw new IllegalArgumentException("The minimum is greater than the maximum");
        }
    }

    public void show(final String caption, final HidePopupRequestEvent.Handler handler) {
        final PopupSize popupSize = PopupSize.resizableX(600);
        ShowPopupEvent.builder(this)
                .popupType(PopupType.OK_CANCEL_DIALOG)
                .popupSize(popupSize)
                .caption(caption)
                .onShow(e -> getView().focus())
                .onHideRequest(handler)
                .fire();
    }

    public interface ConstraintEditView extends View, Focus {

        /**
         * Empties every value editor and the message beside them.
         */
        void clear();

        String getName();

        void setName(String name);

        void setNameEditable(boolean editable);

        ConstraintValueType getType();

        /**
         * Picks the type, and with it the editor shown for the value.
         */
        void setType(ConstraintValueType type);

        boolean isOptional();

        void setOptional(boolean optional);

        /**
         * The text of a single string or number.
         */
        String getText();

        void setText(String text);

        /**
         * A single duration in nanoseconds, or null where none has been entered.
         */
        Long getDuration();

        void setDuration(Long nanos);

        List<String> getSet();

        void setSet(List<String> items);

        String getMin();

        String getMax();

        void setRange(String min, String max);

        Long getMinDuration();

        Long getMaxDuration();

        boolean isNoMin();

        boolean isNoMax();

        /**
         * @param min null to tick "No minimum".
         * @param max null to tick "No maximum".
         */
        void setDurationRange(Long min, Long max);

        String getPattern();

        void setPattern(String pattern);

        boolean getBoolean();

        void setBoolean(boolean value);

        /**
         * Says what is wrong with what was entered, or clears the message where null.
         */
        void setError(String message);
    }
}
