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

package stroom.pathways.impl;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.OptionalInt;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which days a pathway has a record for, a bit apiece.
 *
 * <p>Read to decide whether to open a bucket, so what matters is that it never says no to a day it
 * has, and that it stays small.
 */
class TestDayIndex {

    @Test
    void anEmptyIndexKnowsNothing() {
        final DayIndex index = DayIndex.empty();

        assertThat(index.isEmpty()).isTrue();
        assertThat(index.has(100)).isFalse();
        assertThat(index.lastOnOrBefore(100)).isEmpty();
    }

    @Test
    void aDayMarkedIsADayFound() {
        final DayIndex index = DayIndex.empty().with(20_000);

        assertThat(index.has(20_000)).isTrue();
        assertThat(index.has(19_999)).isFalse();
        assertThat(index.has(20_001)).isFalse();
    }

    @Test
    void marksSurviveBeingWrittenAndReadBack() {
        final DayIndex written = DayIndex.empty().with(20_000).with(20_050).with(19_990);

        final DayIndex read = DayIndex.read(ByteBuffer.wrap(written.write()));

        assertThat(read.has(19_990)).isTrue();
        assertThat(read.has(20_000)).isTrue();
        assertThat(read.has(20_050)).isTrue();
        assertThat(read.has(20_049)).isFalse();
    }

    @Test
    void marksAreKeptWhenTheIndexGrowsBackwards() {
        // A bulk load of old history reaches back past the first day the index has, which is the one
        // case this exists for. Growing the wrong way silently drops the days already in it.
        final DayIndex index = DayIndex.empty().with(20_000).with(20_001).with(18_000);

        assertThat(index.has(18_000)).isTrue();
        assertThat(index.has(20_000)).isTrue();
        assertThat(index.has(20_001)).isTrue();
        assertThat(index.has(19_000)).isFalse();
    }

    @Test
    void markingADayAlreadyMarkedChangesNothing() {
        // What says a pass has nothing to write. A pass that changed nothing leaves the shard
        // unpushed, so a bulk load must not rewrite this row for every trace it reads.
        final DayIndex index = DayIndex.empty().with(20_000);

        assertThat(index.with(20_000)).isSameAs(index);
    }

    @Test
    void theLastDayOnOrBeforeIsTheNewestOneMarked() {
        final DayIndex index = DayIndex.empty().with(20_000).with(20_010).with(20_200);

        assertThat(index.lastOnOrBefore(20_200)).hasValue(20_200);
        assertThat(index.lastOnOrBefore(20_199))
                .as("the newest at or before, not the newest there is")
                .hasValue(20_010);
        assertThat(index.lastOnOrBefore(20_000)).hasValue(20_000);
        assertThat(index.lastOnOrBefore(19_999))
                .as("nothing that old, so nothing to go and look for")
                .isEmpty();
    }

    @Test
    void aDayPastTheEndFindsTheLastOneThereIs() {
        // The question this is asked: today holds nothing, so when was this pathway last seen?
        final DayIndex index = DayIndex.empty().with(19_000);

        assertThat(index.lastOnOrBefore(20_000)).hasValue(19_000);
    }

    @Test
    void twoYearsOfDaysStaysSmall() {
        // It is read to decide whether to do something expensive, so it must not be expensive. Every
        // day of two years marked is the worst it can be for a given span.
        DayIndex index = DayIndex.empty();
        for (int day = 19_000; day < 19_000 + 730; day++) {
            index = index.with(day);
        }

        assertThat(index.write().length)
                .as("a first day and a bit per day")
                .isEqualTo(Integer.BYTES + ((730 + 7) / 8));
    }

    @Test
    void everyDayMarkedIsFoundAgain() {
        // Walking the bits is easy to get wrong by one at a byte boundary, so every day of a span
        // crossing several is asked for rather than a chosen few.
        DayIndex index = DayIndex.empty();
        for (int day = 19_000; day < 19_040; day += 3) {
            index = index.with(day);
        }

        for (int day = 19_000; day < 19_040; day++) {
            assertThat(index.has(day))
                    .as("day " + day)
                    .isEqualTo((day - 19_000) % 3 == 0);
            assertThat(index.lastOnOrBefore(day))
                    .as("last on or before " + day)
                    .isEqualTo(OptionalInt.of(19_000 + (((day - 19_000) / 3) * 3)));
        }
    }
}
