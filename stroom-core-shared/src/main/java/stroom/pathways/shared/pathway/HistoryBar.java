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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

/**
 * One bar of the plot over the Changes table.
 *
 * <p>Bars are not all the same width. Where the change history reaches, a bar covers minutes or
 * hours and counts what taught the model in them. Where it does not, a bar covers a whole day and
 * counts what that day was given, worked out by comparing it with the day before. Only the server
 * knows where that boundary falls, so it sends the bars rather than a span to cut up.
 *
 * <p>The two sides do not count the same thing. A change bar counts distinct traces, one counting
 * once however much it taught. A day has no trace to count by, because its record keeps one trace
 * per bound whatever ran, so a day bar counts what moved between it and the day before instead -
 * the number of rows opening that day shows. The plot therefore scales each side against its own
 * tallest bar rather than against one shared number, and {@link #isWholeDay()} is what tells it
 * which side a bar is on.
 */
@JsonInclude(Include.NON_NULL)
public class HistoryBar {

    @JsonProperty
    private final long fromMs;
    /** The last moment the bar covers, so a bar is from its start to this inclusive. */
    @JsonProperty
    private final long toMs;
    @JsonProperty
    private final long count;
    @JsonProperty
    private final boolean wholeDay;

    @JsonCreator
    public HistoryBar(@JsonProperty("fromMs") final long fromMs,
                      @JsonProperty("toMs") final long toMs,
                      @JsonProperty("count") final long count,
                      @JsonProperty("wholeDay") final boolean wholeDay) {
        this.fromMs = fromMs;
        this.toMs = toMs;
        this.count = count;
        this.wholeDay = wholeDay;
    }

    public long getFromMs() {
        return fromMs;
    }

    public long getToMs() {
        return toMs;
    }

    public long getCount() {
        return count;
    }

    /**
     * Whether this bar covers a whole day the change history does not reach.
     *
     * <p>The plot hatches these and the scrubber steps across them a day at a time, because there is
     * no change within one to stop at.
     */
    public boolean isWholeDay() {
        return wholeDay;
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final HistoryBar other = (HistoryBar) o;
        return fromMs == other.fromMs
               && toMs == other.toMs
               && count == other.count
               && wholeDay == other.wholeDay;
    }

    @Override
    public int hashCode() {
        return Objects.hash(fromMs, toMs, count, wholeDay);
    }

    @Override
    public String toString() {
        return "HistoryBar{fromMs=" + fromMs + ", toMs=" + toMs + ", count=" + count
               + ", wholeDay=" + wholeDay + "}";
    }
}
