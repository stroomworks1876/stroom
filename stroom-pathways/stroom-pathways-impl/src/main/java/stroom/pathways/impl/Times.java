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

import stroom.pathways.shared.otel.trace.NanoTime;

/**
 * Which of two things happened first.
 *
 * <p>Here rather than written out wherever it is needed because two accounts of one day have to fold
 * to the same answer whichever was read first, and that only holds if everything folding them asks
 * the question the same way. Written twice it drifts: an earlier pair of these disagreed about where
 * a missing time sorts.
 */
final class Times {

    private Times() {
    }

    /**
     * Nothing dated comes before anything dated, so a fold with no times to go on still has a
     * settled order rather than whichever arrived first.
     */
    static int compare(final NanoTime first, final NanoTime second) {
        if (first == null || second == null) {
            return first == null
                    ? (second == null
                            ? 0
                            : -1)
                    : 1;
        }
        if (first.equals(second)) {
            return 0;
        }
        return second.isGreaterThan(first)
                ? -1
                : 1;
    }

    /**
     * The same, for the trace ids that settle an exact tie. An id decides nothing in itself - it is a
     * random string - and is only here so the answer does not depend on reading order.
     */
    static int compare(final String first, final String second) {
        if (first == null || second == null) {
            return first == null
                    ? (second == null
                            ? 0
                            : -1)
                    : 1;
        }
        return first.compareTo(second);
    }
}
