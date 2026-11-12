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

package stroom.pathways.shared.pathway;

import stroom.docref.HasDisplayValue;

/**
 * How one day's model differs from the day before's.
 *
 * <p>Named after the fold rather than after anything the learner did, because for these days the
 * learner did nothing: there are no change rows, and what is listed is worked out by comparing two
 * days. {@link MutationType} is the other vocabulary, for days the change history reaches, and the
 * two are deliberately not the same list. "Added" there is a thing that happened once; here a node
 * can come into the model, leave it when traffic stops for longer than the window, and come into it
 * again when traffic resumes - and all three are true.
 */
public enum DifferenceType implements HasDisplayValue {

    /** The day held a node the day before did not. */
    NODE_ARRIVED("Node Arrived"),
    /**
     * The day before held a node this day does not.
     *
     * <p>Not a retirement. A day's model is the fold of a window, so a node leaves it as soon as the
     * last day that carried it falls out behind the window's edge. Nothing happened to the work; it
     * simply stopped being inside the span being looked at.
     */
    NODE_LEFT("Node Left"),
    /** The day held a constraint the day before did not. */
    CONSTRAINT_ARRIVED("Constraint Arrived"),
    /** The day before held a constraint this day does not. */
    CONSTRAINT_LEFT("Constraint Left"),
    /** The constraint admits something it did not the day before. */
    WIDENED("Widened"),
    /** The constraint no longer admits something it did the day before. */
    NARROWED("Narrowed"),
    /** The constraint admits something new and no longer admits something it did. */
    SHIFTED("Shifted"),
    /**
     * The two days hold values of different types, so neither covers the other.
     *
     * <p>Its own answer rather than a widening, because a type changing is not a bound moving and
     * reading it as one would say the work had started doing something it had always done.
     */
    TYPE_CHANGED("Type Changed"),
    /** The thing the constraint describes was absent somewhere it was looked for, and was not before. */
    BECAME_OPTIONAL("Became Optional"),
    /** The thing the constraint describes was present everywhere it was looked for, and was not before. */
    BECAME_REQUIRED("Became Required");

    private final String displayValue;

    DifferenceType(final String displayValue) {
        this.displayValue = displayValue;
    }

    @Override
    public String getDisplayValue() {
        return displayValue;
    }
}
