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

import stroom.pathways.shared.pathway.HistoryBar;
import stroom.pathways.shared.pathway.HistoryRow;
import stroom.util.shared.PageResponse;
import stroom.util.shared.ResultPage;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public class PathwayMutationResultPage extends ResultPage<HistoryRow> {

    /**
     * The span these rows were asked for, resolved, or null where none was asked for.
     *
     * <p>Sent back because a range is picked as expressions - {@code day()}, {@code now()-24h} - and
     * only the server can turn those into times. The plot over these rows is drawn across this span,
     * so it covers what was asked for rather than running on to now.
     */
    @JsonProperty
    private final Long windowFrom;
    @JsonProperty
    private final Long windowTo;
    /**
     * The plot over these rows, oldest bar first.
     *
     * <p>Built here rather than in the browser for two reasons. Bars are not all the same width, and
     * only this end knows where the change history runs out. And the rows are capped, so a plot drawn
     * from the page would quietly under-count a pathway with more history than the cap.
     *
     * <p>Counted over every row the range covers, not over the page, and before any node filter the
     * browser puts on the table.
     */
    @JsonProperty
    private final List<HistoryBar> bars;

    @JsonCreator
    public PathwayMutationResultPage(@JsonProperty("values") final List<HistoryRow> values,
                                     @JsonProperty("pageResponse") final PageResponse pageResponse,
                                     @JsonProperty("windowFrom") final Long windowFrom,
                                     @JsonProperty("windowTo") final Long windowTo,
                                     @JsonProperty("bars") final List<HistoryBar> bars) {
        super(values, pageResponse);
        this.windowFrom = windowFrom;
        this.windowTo = windowTo;
        this.bars = bars;
    }

    public PathwayMutationResultPage(final List<HistoryRow> values,
                                     final PageResponse pageResponse) {
        this(values, pageResponse, null, null, List.of());
    }

    public Long getWindowFrom() {
        return windowFrom;
    }

    public Long getWindowTo() {
        return windowTo;
    }

    public List<HistoryBar> getBars() {
        return bars;
    }
}
