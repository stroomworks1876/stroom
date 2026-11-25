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

package stroom.pathways.client.view;

import stroom.item.client.SelectionBox;

import com.google.gwt.user.client.ui.Composite;
import com.google.gwt.user.client.ui.FlowPanel;
import com.google.gwt.user.client.ui.TextBox;

/**
 * A duration typed as a number and a unit, down to nanoseconds.
 */
public class DurationBox extends Composite {

    private static final String[] UNITS = {"s", "ms", "µs", "ns"};
    private static final long[] NANOS = {1_000_000_000L, 1_000_000L, 1_000L, 1L};
    private static final String DEFAULT_UNIT = "ms";

    private final TextBox number = new TextBox();
    private final SelectionBox<String> unit = new SelectionBox<>();

    public DurationBox() {
        number.addStyleName("constraint-duration-number");
        unit.addStyleName("constraint-duration-unit");
        for (final String name : UNITS) {
            unit.addItem(name);
        }
        unit.setValue(DEFAULT_UNIT);
        final FlowPanel panel = new FlowPanel();
        panel.addStyleName("constraint-duration");
        panel.add(number);
        panel.add(unit);
        initWidget(panel);
    }

    /**
     * @return the duration in nanoseconds, or null where nothing, or no number of zero or more, has
     * been typed.
     */
    public Long getNanos() {
        final String text = number.getText().trim();
        if (text.isEmpty()) {
            return null;
        }
        final double value;
        try {
            value = Double.parseDouble(text);
        } catch (final NumberFormatException e) {
            return null;
        }
        if (Double.isNaN(value) || Double.isInfinite(value) || value < 0) {
            return null;
        }
        return Math.round(value * NANOS[indexOf(unit.getValue())]);
    }

    /**
     * Shows the duration in the largest unit it is at least one of, as an exact decimal so that
     * reading it back gives the same nanoseconds, or empty in milliseconds where it is null. Zero
     * keeps the unit already shown.
     */
    public void setNanos(final Long nanos) {
        if (nanos == null) {
            number.setText("");
            unit.setValue(DEFAULT_UNIT);
            return;
        }
        if (nanos == 0) {
            number.setText("0");
            return;
        }
        int index = NANOS.length - 1;
        for (int i = 0; i < NANOS.length; i++) {
            if (nanos >= NANOS[i]) {
                index = i;
                break;
            }
        }
        number.setText(decimal(nanos, NANOS[index]));
        unit.setValue(UNITS[index]);
    }

    // The whole units, then the remainder as decimal places with trailing zeros dropped.
    private static String decimal(final long nanos, final long perUnit) {
        final String whole = String.valueOf(nanos / perUnit);
        final long remainder = nanos % perUnit;
        if (remainder == 0) {
            return whole;
        }
        String fraction = String.valueOf(perUnit + remainder).substring(1);
        while (fraction.endsWith("0")) {
            fraction = fraction.substring(0, fraction.length() - 1);
        }
        return whole + "." + fraction;
    }

    public void setEnabled(final boolean enabled) {
        number.setEnabled(enabled);
        unit.setEnabled(enabled);
    }

    private static int indexOf(final String name) {
        for (int i = 0; i < UNITS.length; i++) {
            if (UNITS[i].equals(name)) {
                return i;
            }
        }
        return NANOS.length - 1;
    }
}
