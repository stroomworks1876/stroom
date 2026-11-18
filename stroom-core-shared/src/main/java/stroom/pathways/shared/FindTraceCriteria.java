/*
 * Copyright 2023 Crown Copyright
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
import stroom.query.api.datasource.FieldFields;
import stroom.util.shared.BaseCriteria;
import stroom.util.shared.CriteriaFieldSort;
import stroom.util.shared.PageRequest;
import stroom.util.shared.filter.FilterFieldDefinition;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

@JsonInclude(Include.NON_NULL)
@JsonPropertyOrder(alphabetic = true)
public class FindTraceCriteria extends BaseCriteria {

    public static final CriteriaFieldSort DEFAULT_SORT =
            new CriteriaFieldSort(FieldFields.NAME, false, true);
    public static final List<CriteriaFieldSort> DEFAULT_SORT_LIST =
            Collections.singletonList(DEFAULT_SORT);

    public static final FilterFieldDefinition FIELD_DEF_OPERATION =
            FilterFieldDefinition.defaultField("Operation");
    public static final FilterFieldDefinition FIELD_DEF_TRACE_ID =
            FilterFieldDefinition.defaultField("Trace Id", "traceid");
    public static final FilterFieldDefinition FIELD_DEF_IS_ERROR =
            FilterFieldDefinition.qualifiedField("Is Error", "iserror");
    public static final List<FilterFieldDefinition> FIELD_DEFINITIONS =
            List.of(FIELD_DEF_OPERATION, FIELD_DEF_TRACE_ID, FIELD_DEF_IS_ERROR);

    @JsonProperty
    private final DocRef dataSourceRef;
    @JsonProperty
    private final String filter;
    @JsonProperty
    private final TimeRange timeRange;

    public FindTraceCriteria(final PageRequest pageRequest,
                             final List<CriteriaFieldSort> sortList,
                             final DocRef dataSourceRef) {
        this(pageRequest, sortList, dataSourceRef, null, null);
    }

    @JsonCreator
    public FindTraceCriteria(@JsonProperty("pageRequest") final PageRequest pageRequest,
                             @JsonProperty("sortList") final List<CriteriaFieldSort> sortList,
                             @JsonProperty("dataSourceRef") final DocRef dataSourceRef,
                             @JsonProperty("filter") final String filter,
                             @JsonProperty("timeRange") final TimeRange timeRange) {
        super(pageRequest, sortList);
        this.dataSourceRef = dataSourceRef;
        this.filter = filter;
        this.timeRange = timeRange;
    }

    public DocRef getDataSourceRef() {
        return dataSourceRef;
    }

    public String getFilter() {
        return filter;
    }

    public TimeRange getTimeRange() {
        return timeRange;
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        if (!super.equals(o)) {
            return false;
        }
        final FindTraceCriteria that = (FindTraceCriteria) o;
        return Objects.equals(dataSourceRef, that.dataSourceRef) &&
               Objects.equals(filter, that.filter) &&
               Objects.equals(timeRange, that.timeRange);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), dataSourceRef, filter, timeRange);
    }

    @Override
    public String toString() {
        return "FindTraceCriteria{" +
               "dataSourceRef=" + dataSourceRef +
               ", filter='" + filter + '\'' +
               ", timeRange=" + timeRange +
               '}';
    }
}
