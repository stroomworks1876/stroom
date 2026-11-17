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

import stroom.bytebuffer.impl6.ByteBuffers;
import stroom.planb.impl.dao.LmdbWriter;
import stroom.planb.impl.dao.trace.PathwaysDb.SimpleDb;

import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.OptionalInt;
import java.util.function.Consumer;

/**
 * Which days one pathway has a day record for, as a bit apiece.
 *
 * <p>It says only that a day has something, never what. Answering that is what the record itself is
 * for, and this exists to say which records are worth going to look for.
 *
 * <p>It covers the days whose records have gone to a bucket, which are the ones that cannot be
 * found cheaply any other way. A record still in the live shard answers for itself: its key ends
 * with the day it is for, so the days of one pathway sort together and the newest is one seek.
 *
 * <p>A bucket holds a week compressed into one file, so finding out whether it has anything for a
 * pathway means copying it down and decompressing all of it. Walking back through a year of those
 * to find the last day a pathway was seen is the thing this replaces: two years of days is 92 bytes
 * and sits in a table already open.
 *
 * <p>Held as a first day and a run of bits rather than a set of days, because that is what makes it
 * small, and small is the whole point - it is read to decide whether to do something expensive, so
 * it must never be expensive itself.
 */
final class DayIndex {

    private static final DayIndex EMPTY = new DayIndex(0, new byte[0]);

    /** The day the first bit stands for. Meaningless while there are no bits. */
    private final int firstDay;
    /** A bit per day from {@link #firstDay} on, set where that day has a record. */
    private final byte[] bits;

    private DayIndex(final int firstDay, final byte[] bits) {
        this.firstDay = firstDay;
        this.bits = bits;
    }

    static DayIndex empty() {
        return EMPTY;
    }

    /**
     * Marks days against one pathway, and says whether that changed anything.
     *
     * <p>Both the places that mark days reduce to this - the nightly pass as records leave the
     * shard, and a load of old history whose records never reach it. Kept beside the shape it
     * writes so that how a row is read, changed and written back is described once.
     *
     * <p>False means every one of those days was marked already, which is a pass with nothing to
     * write. The caller needs that: a shard nothing changed is not pushed back, and a bulk load
     * offering the same days over and over must not rewrite this row on every hold.
     */
    static boolean mark(final LmdbWriter writer,
                        final SimpleDb index,
                        final ByteBuffers byteBuffers,
                        final byte[] pathwayKey,
                        final Collection<Integer> days) {
        final boolean[] marked = {false};
        byteBuffers.useBytes(pathwayKey, (Consumer<ByteBuffer>) keyByteBuffer -> {
            final DayIndex held = index.get(writer.getWriteTxn(), keyByteBuffer.duplicate(),
                    DayIndex::read);
            DayIndex wanted = held;
            for (final int day : days) {
                wanted = wanted.with(day);
            }
            if (wanted == held) {
                return;
            }
            byteBuffers.useBytes(wanted.write(), (Consumer<ByteBuffer>) valueByteBuffer ->
                    index.insert(writer, keyByteBuffer.duplicate(), valueByteBuffer));
            marked[0] = true;
        });
        return marked[0];
    }

    /**
     * Reads one back, or an empty one where the pathway has no row yet.
     *
     * <p>The buffer is read from its current position and left where it was, so a caller holding a
     * value straight out of the store can read it without copying.
     */
    static DayIndex read(final ByteBuffer value) {
        if (value == null || value.remaining() < Integer.BYTES) {
            return EMPTY;
        }
        final ByteBuffer from = value.duplicate();
        final int firstDay = from.getInt();
        final byte[] bits = new byte[from.remaining()];
        from.get(bits);
        return new DayIndex(firstDay, bits);
    }

    byte[] write() {
        final ByteBuffer buffer = ByteBuffer.allocate(Integer.BYTES + bits.length);
        buffer.putInt(firstDay).put(bits);
        return buffer.array();
    }

    boolean isEmpty() {
        return bits.length == 0;
    }

    boolean has(final int day) {
        final int offset = day - firstDay;
        return offset >= 0
               && (offset >> 3) < bits.length
               && (bits[offset >> 3] & (1 << (offset & 7))) != 0;
    }

    /**
     * The same index with one more day marked, or this one where that day was marked already.
     *
     * <p>Returning this one unchanged is what lets a caller tell a day it has already seen from one
     * it has not, without comparing anything. A pass that marks only days it has seen before has
     * changed nothing, and a shard that has not changed is not pushed back - so a bulk load of a
     * year's history flips a few hundred bits rather than rewriting this row on every hold.
     */
    DayIndex with(final int day) {
        if (has(day)) {
            return this;
        }
        if (isEmpty()) {
            final DayIndex started = new DayIndex(day, new byte[1]);
            started.bits[0] = 1;
            return started;
        }

        // Room for the day at whichever end it falls past. Days arrive in no order - a bulk load of
        // old history reaches back past the first day this has, and the nightly pass adds days on
        // the end - so both ends grow.
        final int newFirstDay = Math.min(firstDay, day);
        final int lastDay = Math.max(firstDay + (bits.length << 3) - 1, day);
        final byte[] grown = new byte[((lastDay - newFirstDay) >> 3) + 1];
        final int shift = firstDay - newFirstDay;
        for (int offset = 0; offset < (bits.length << 3); offset++) {
            if ((bits[offset >> 3] & (1 << (offset & 7))) != 0) {
                final int moved = offset + shift;
                grown[moved >> 3] |= (byte) (1 << (moved & 7));
            }
        }
        final int moved = day - newFirstDay;
        grown[moved >> 3] |= (byte) (1 << (moved & 7));
        return new DayIndex(newFirstDay, grown);
    }

    /**
     * The newest day at or before this one that has a record, or nothing where there is none.
     *
     * <p>What answers "this pathway has nothing in the window asked for - when was it last seen?".
     */
    OptionalInt lastOnOrBefore(final int day) {
        if (isEmpty() || day < firstDay) {
            return OptionalInt.empty();
        }
        final int from = Math.min(day - firstDay, (bits.length << 3) - 1);
        for (int offset = from; offset >= 0; offset--) {
            if ((bits[offset >> 3] & (1 << (offset & 7))) != 0) {
                return OptionalInt.of(firstDay + offset);
            }
        }
        return OptionalInt.empty();
    }
}
