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
import stroom.pathways.shared.pathway.PathStep;
import stroom.pathways.shared.pathway.PathUse;
import stroom.pathways.shared.pathway.Paths;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Folds one trace's walk into the paths a pathway has already seen.
 *
 * <p>A path is one shape holding the whole walk: every node the trace reached, the children it ran
 * at each, and the order it ran them in, with work that repeated said once. How many times a repeat
 * ran is not part of it — recording that makes a trace that called a routine eight times a different
 * path from one that called it seven, which is the workload rather than the code path.
 *
 * <p>Shapes are kept once each and named by position, so a subtree many paths reach costs one entry
 * however many of them reach it, and a path is one number.
 *
 * <p>Every trace produces a path, including a trace that reached no children at all, so the path
 * counts sum to the number of traces the pathway has been used for.
 */
final class PathRecorder {

    private PathRecorder() {
    }

    static Paths add(final Paths current,
                     final PathShape shape,
                     final NanoTime time,
                     final NanoTime traceTime,
                     final String traceId,
                     final String sourceUuid) {
        final Account account = new Account(current);
        // A trace always has a shape; -1 is here so a caller that has none records a path the screen
        // reads as nothing rather than failing.
        final int root = shape == null
                ? -1
                : account.intern(shape);
        account.took(root, 1L, time, time, traceId, traceTime, traceTime,
                account.sourceIndex(sourceUuid));
        return account.paths();
    }

    /**
     * Two accounts of the same work, folded into one.
     *
     * <p>Every number in a {@link Paths} means something only against its own lists, so nothing can
     * be compared number by number. Each of the second account's walks is interned into the first's
     * lists afresh, which is the same thing {@link #add} does for one trace's walk.
     */
    static Paths merge(final Paths first, final Paths second) {
        return merge(first, second, Map.of());
    }

    /**
     * As above, where the two accounts call the same node different things.
     *
     * <p>A day record gives each node a short stand-in so its paths do not repeat the node's name
     * and path over and over. The stand-in is that record's own, so two records for one day pick
     * different ones for the same node, and folding them without saying which is which leaves one
     * node in the list twice with half the paths pointing at the copy nothing describes.
     *
     * @param renamed what each of the second account's stand-ins is called in the first
     */
    static Paths merge(final Paths first, final Paths second, final Map<String, String> renamed) {
        final Account account = new Account(first, renamed);
        // One entry per step of the account being folded in, filled as each is reached, so a subtree
        // many walks reach is interned once however many of them reach it. Without it the shared
        // subtrees a Paths is made of are re-expanded into a tree.
        final Integer[] moved = new Integer[second.getSteps().size()];
        for (final PathUse row : second.getPaths()) {
            final int root = row.getRoot() < 0
                    ? -1
                    : account.reintern(row.getRoot(), second, moved);
            account.took(root, row.getTimesUsed(), row.getFirstUsedTime(), row.getLastUsedTime(),
                    row.getCreatedByTraceId(), row.getTraceTime(), row.getLastTraceTime(),
                    row.getSource() < 0
                            ? -1
                            : account.sourceIndex(second.getSources().get(row.getSource())));
        }
        return account.paths();
    }

    /**
     * The same walks, numbered the way any other account of the same walks would be numbered.
     *
     * <p>A day record is written, moved into a bucket and may be written again by a later load of the
     * same traces. Two accounts holding the same walks but numbering their nodes in the order each
     * happened to meet them are different bytes, and the rule that loading a day twice changes
     * nothing would not hold. So the walks are put in an order taken from what they hold rather than
     * from when they arrived, and everything is numbered again from there.
     */
    static Paths canonical(final Paths paths) {
        final List<PathUse> ordered = new ArrayList<>(paths.getPaths());
        ordered.sort(PathRecorder::firstTaken);
        return merge(Paths.empty(),
                new Paths(paths.getNodes(), paths.getSteps(), ordered, paths.getSources()));
    }

    /**
     * Which of two walks was taken first, as an order rather than as a yes or no.
     *
     * <p>A walk nothing dated comes first, then by when it was taken, and an exact tie goes to the
     * lower trace id - which decides nothing in itself and is only there so that two accounts of the
     * same walks order them the same way whatever order they were written in.
     */
    static int firstTaken(final PathUse first, final PathUse second) {
        final int byTime = Times.compare(first.getFirstUsedTime(), second.getFirstUsedTime());
        return byTime != 0
                ? byTime
                : Times.compare(first.getCreatedByTraceId(), second.getCreatedByTraceId());
    }

    /**
     * One {@link Paths} being built, with the lookups a build needs beside it.
     *
     * <p>Held together because every number in a Paths means something only against its own lists, so
     * the lists and the indexes over them are one thing. Built once per fold rather than per walk:
     * rebuilding them is proportional to the whole account, and a fold is one walk.
     */
    private static final class Account {

        private final List<String> nodes;
        private final Map<String, Integer> positions = new HashMap<>();
        private final List<PathStep> steps;
        private final Map<PathStep, Integer> shapes = new HashMap<>();
        // Held whether or not a trace goes on to create a walk, because the changes it made are
        // numbered against the same list and are recorded even when the walk it took is one the
        // pathway already had.
        private final List<String> sources;
        private final List<PathUse> rows;
        private final Map<Integer, Integer> rowByRoot = new HashMap<>();

        private final Map<String, String> renamed;

        private Account(final Paths current) {
            this(current, Map.of());
        }

        private Account(final Paths current, final Map<String, String> renamed) {
            this.renamed = renamed;
            nodes = new ArrayList<>(current.getNodes());
            for (int i = 0; i < nodes.size(); i++) {
                positions.put(nodes.get(i), i);
            }
            steps = new ArrayList<>(current.getSteps());
            for (int i = 0; i < steps.size(); i++) {
                shapes.putIfAbsent(steps.get(i), i);
            }
            sources = new ArrayList<>(current.getSources());
            rows = new ArrayList<>(current.getPaths());
            for (int i = 0; i < rows.size(); i++) {
                rowByRoot.putIfAbsent(rows.get(i).getRoot(), i);
            }
        }

        private Paths paths() {
            return new Paths(nodes, steps, rows, sources);
        }

        // A walk taken, whether for the first time or again. Found by where it begins rather than by
        // walking the rows, so folding a whole account in does not cost the square of what it holds.
        private void took(final int root,
                          final long timesUsed,
                          final NanoTime firstUsed,
                          final NanoTime lastUsed,
                          final String traceId,
                          final NanoTime traceTime,
                          final NanoTime lastTraceTime,
                          final int source) {
            final Integer held = rowByRoot.get(root);
            if (held != null) {
                rows.set(held, taken(rows.get(held), timesUsed, firstUsed, lastUsed, traceId,
                        traceTime, lastTraceTime, source));
                return;
            }
            // Kept in the order first taken, so the oldest walk stays at the top of the table however
            // the counts move.
            rowByRoot.put(root, rows.size());
            rows.add(new PathUse(root, timesUsed, firstUsed, lastUsed, traceId, traceTime,
                    lastTraceTime, source));
        }

        private int sourceIndex(final String sourceUuid) {
            if (sourceUuid == null) {
                return -1;
            }
            final int found = sources.indexOf(sourceUuid);
            if (found >= 0) {
                return found;
            }
            sources.add(sourceUuid);
            return sources.size() - 1;
        }

        // Puts a shape and everything under it in the shape list, deepest first, and says where it
        // went. A shape the list already holds is not added again, so a subtree many walks reach costs
        // one entry however many of them reach it.
        private int intern(final PathShape shape) {
            // Numbered on the way down, so nodes are numbered in the order the trace reached them and
            // the root takes the first position. Where the shape goes in the list has to wait for the
            // children, because it is made of where they went.
            final int node = shape.kind() == PathShape.Kind.NODE
                    ? nodeIndex(shape.nodeUuid())
                    : marker(shape.kind());
            final List<Integer> children = new ArrayList<>(shape.steps().size());
            for (final PathShape child : shape.steps()) {
                children.add(intern(child));
            }
            return stepIndex(node, children);
        }

        // The same, for a shape held in another account's lists. The shape itself cannot be carried
        // over, because its numbers point into the lists it came from.
        private int reintern(final int stepIndex, final Paths from, final Integer[] moved) {
            if (moved[stepIndex] != null) {
                return moved[stepIndex];
            }
            final PathStep source = from.getSteps().get(stepIndex);
            final int node = source.getNode() < 0
                    ? source.getNode()
                    : nodeIndex(called(from.getNodes().get(source.getNode())));
            final List<Integer> children = new ArrayList<>(source.getSteps().size());
            for (final Integer child : source.getSteps()) {
                children.add(reintern(child, from, moved));
            }
            final int here = stepIndex(node, children);
            moved[stepIndex] = here;
            return here;
        }

        // What this account calls a node the other one named, where the two disagree.
        private String called(final String theirs) {
            return renamed.getOrDefault(theirs, theirs);
        }

        private int nodeIndex(final String nodeUuid) {
            Integer position = positions.get(nodeUuid);
            if (position == null) {
                position = nodes.size();
                nodes.add(nodeUuid);
                positions.put(nodeUuid, position);
            }
            return position;
        }

        private int stepIndex(final int node, final List<Integer> children) {
            final PathStep step = new PathStep(node, children);
            final Integer existing = shapes.get(step);
            if (existing != null) {
                return existing;
            }
            steps.add(step);
            shapes.put(step, steps.size() - 1);
            return steps.size() - 1;
        }
    }

    // Not a node at all but a marker for how the work under it ran.
    private static int marker(final PathShape.Kind kind) {
        return switch (kind) {
            case UNFINISHED -> PathStep.UNFINISHED;
            case CONCURRENT -> PathStep.CONCURRENT;
            case RUN -> PathStep.RUN;
            case NODE -> throw new IllegalStateException("handled above");
        };
    }

    // One row's worth of two accounts of the same walk. The counts add, the span covered widens both
    // ways, and the trace credited with taking it first is whichever really was first.
    private static PathUse taken(final PathUse held,
                                 final long timesUsed,
                                 final NanoTime firstUsed,
                                 final NanoTime lastUsed,
                                 final String traceId,
                                 final NanoTime traceTime,
                                 final NanoTime lastTraceTime,
                                 final int source) {
        final PathUse arrived = new PathUse(held.getRoot(), timesUsed, firstUsed, lastUsed, traceId,
                traceTime, lastTraceTime, source);
        final PathUse first = firstTaken(held, arrived) <= 0
                ? held
                : arrived;
        return new PathUse(held.getRoot(),
                held.getTimesUsed() + timesUsed,
                first.getFirstUsedTime(),
                PathUse.newest(held.getLastUsedTime(), lastUsed),
                first.getCreatedByTraceId(),
                first.getTraceTime(),
                PathUse.newest(held.getLastTraceTime(), lastTraceTime),
                first.getSource());
    }
}
