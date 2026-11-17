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
import stroom.util.shared.BaseCriteria;
import stroom.util.shared.CriteriaFieldSort;
import stroom.util.shared.PageRequest;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Objects;

/**
 * The changes made to one learnt pathway, newest first unless the grid asks otherwise.
 */
@JsonInclude(Include.NON_NULL)
public class FindPathwayMutationCriteria extends BaseCriteria {

    @JsonProperty
    private final DocRef pathwaysDocRef;
    @JsonProperty
    private final String pathwayName;
    /**
     * Which span of time to list, or null for all of it.
     *
     * <p>Held as it was picked rather than as a pair of times. A range is written as expressions -
     * {@code day()}, {@code now()-24h} - and turning those into times is done where the parser for
     * them lives, which is not the browser.
     */
    @JsonProperty
    private final TimeRange timeRange;
    /**
     * Which node the plot is counted over, or null for all of them.
     *
     * <p>The plot only. The rows come back whole and the table narrows itself, because narrowing
     * them here would mean reloading the table every time the filter was turned on - which takes
     * away whichever row was being looked at and shuts every trace the reader had opened.
     *
     * <p>Asked for here rather than counted in the browser because the plot covers every row the
     * range holds, and the rows are capped: a plot counted from the ones in hand would quietly say
     * less than the truth about a pathway with a long history.
     */
    @JsonProperty
    private final String plotNode;

    @JsonCreator
    public FindPathwayMutationCriteria(@JsonProperty("pageRequest") final PageRequest pageRequest,
                                       @JsonProperty("sortList") final List<CriteriaFieldSort> sortList,
                                       @JsonProperty("pathwaysDocRef") final DocRef pathwaysDocRef,
                                       @JsonProperty("pathwayName") final String pathwayName,
                                       @JsonProperty("timeRange") final TimeRange timeRange,
                                       @JsonProperty("plotNode") final String plotNode) {
        super(pageRequest, sortList);
        this.pathwaysDocRef = pathwaysDocRef;
        this.pathwayName = pathwayName;
        this.timeRange = timeRange;
        this.plotNode = plotNode;
    }

    /**
     * Rows for a span, with the plot over all of them. What everything asking for the table wants:
     * the table narrows itself, so only a request for the plot alone names a node.
     */
    public FindPathwayMutationCriteria(final PageRequest pageRequest,
                                       final List<CriteriaFieldSort> sortList,
                                       final DocRef pathwaysDocRef,
                                       final String pathwayName,
                                       final TimeRange timeRange) {
        this(pageRequest, sortList, pathwaysDocRef, pathwayName, timeRange, null);
    }

    public FindPathwayMutationCriteria(final PageRequest pageRequest,
                                       final List<CriteriaFieldSort> sortList,
                                       final DocRef pathwaysDocRef,
                                       final String pathwayName) {
        this(pageRequest, sortList, pathwaysDocRef, pathwayName, null, null);
    }

    public String getPlotNode() {
        return plotNode;
    }

    public DocRef getPathwaysDocRef() {
        return pathwaysDocRef;
    }

    public String getPathwayName() {
        return pathwayName;
    }

    public TimeRange getTimeRange() {
        return timeRange;
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass() || !super.equals(o)) {
            return false;
        }
        final FindPathwayMutationCriteria that = (FindPathwayMutationCriteria) o;
        return Objects.equals(pathwaysDocRef, that.pathwaysDocRef)
               && Objects.equals(pathwayName, that.pathwayName)
               && Objects.equals(timeRange, that.timeRange)
               && Objects.equals(plotNode, that.plotNode);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), pathwaysDocRef, pathwayName, timeRange, plotNode);
    }

    @Override
    public String toString() {
        return "FindPathwayMutationCriteria{pathwaysDocRef=" + pathwaysDocRef
               + ", pathwayName='" + pathwayName + "', timeRange=" + timeRange
               + ", plotNode='" + plotNode + "'}";
    }
}
