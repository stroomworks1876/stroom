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

import stroom.pathways.shared.pathway.PathwayMutation;
import stroom.util.shared.PageResponse;
import stroom.util.shared.ResultPage;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public class PathwayMutationResultPage extends ResultPage<PathwayMutation> {

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

    @JsonCreator
    public PathwayMutationResultPage(@JsonProperty("values") final List<PathwayMutation> values,
                                     @JsonProperty("pageResponse") final PageResponse pageResponse,
                                     @JsonProperty("windowFrom") final Long windowFrom,
                                     @JsonProperty("windowTo") final Long windowTo) {
        super(values, pageResponse);
        this.windowFrom = windowFrom;
        this.windowTo = windowTo;
    }

    public PathwayMutationResultPage(final List<PathwayMutation> values,
                                     final PageResponse pageResponse) {
        this(values, pageResponse, null, null);
    }

    public Long getWindowFrom() {
        return windowFrom;
    }

    public Long getWindowTo() {
        return windowTo;
    }
}
