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

package stroom.pathways.shared;

import stroom.docref.DocRef;
import stroom.query.api.TimeRange;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Asks for one learnt pathway, by the operation name it is keyed on, as it stood at one moment.
 *
 * <p>The name is also what decides its shard, so finding it needs no more than the name and the
 * document.
 */
@JsonInclude(Include.NON_NULL)
public class FetchPathwayRequest {

    @JsonProperty
    private final DocRef pathwaysDocRef;
    @JsonProperty
    private final String name;
    /**
     * The span being looked at, or null for the model as it stands now. The pathway is answered for
     * the latest time in it, because a drawing is one instant rather than a span.
     *
     * <p>Held as it was picked rather than as a time. A range is written as expressions -
     * {@code day()}, {@code now()-24h} - and turning those into times is done where the parser for
     * them lives, which is not the browser.
     *
     * <p>Read against when the learner took each change in, not when the work ran. Those differ, and
     * it is the first that can be used: showing an earlier model means taking changes off it newest
     * first, which only works on an unbroken run, and changes picked by when their work ran are
     * scattered through the order they were learnt in.
     */
    @JsonProperty
    private final TimeRange timeRange;
    /**
     * One particular change to show the model just after, or null to ask by moment instead.
     *
     * <p>Asked for by number rather than by time because every change one trace made carries the same
     * timestamp, so a time cannot tell them apart. Stepping through the history picks changes one at a
     * time and needs each one exactly.
     */
    @JsonProperty
    private final Long toSequence;

    @JsonCreator
    public FetchPathwayRequest(@JsonProperty("pathwaysDocRef") final DocRef pathwaysDocRef,
                               @JsonProperty("name") final String name,
                               @JsonProperty("timeRange") final TimeRange timeRange,
                               @JsonProperty("toSequence") final Long toSequence) {
        this.pathwaysDocRef = pathwaysDocRef;
        this.name = name;
        this.timeRange = timeRange;
        this.toSequence = toSequence;
    }

    public FetchPathwayRequest(final DocRef pathwaysDocRef, final String name) {
        this(pathwaysDocRef, name, null, null);
    }

    public static FetchPathwayRequest over(final DocRef pathwaysDocRef,
                                           final String name,
                                           final TimeRange timeRange) {
        return new FetchPathwayRequest(pathwaysDocRef, name, timeRange, null);
    }

    public static FetchPathwayRequest atChange(final DocRef pathwaysDocRef,
                                               final String name,
                                               final Long toSequence) {
        return new FetchPathwayRequest(pathwaysDocRef, name, null, toSequence);
    }

    public DocRef getPathwaysDocRef() {
        return pathwaysDocRef;
    }

    public String getName() {
        return name;
    }

    public TimeRange getTimeRange() {
        return timeRange;
    }

    public Long getToSequence() {
        return toSequence;
    }

    @Override
    public String toString() {
        return "FetchPathwayRequest{pathwaysDocRef=" + pathwaysDocRef + ", name='" + name
               + "', timeRange=" + timeRange + ", toSequence=" + toSequence + "}";
    }
}
