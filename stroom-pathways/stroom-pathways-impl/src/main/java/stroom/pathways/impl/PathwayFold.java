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
import stroom.pathways.shared.pathway.Constraint;
import stroom.pathways.shared.pathway.NodeUsage;
import stroom.pathways.shared.pathway.PathKey;
import stroom.pathways.shared.pathway.PathNode;
import stroom.pathways.shared.pathway.PathStep;
import stroom.pathways.shared.pathway.PathUse;
import stroom.pathways.shared.pathway.Paths;
import stroom.pathways.shared.pathway.Pathway;
import stroom.planb.impl.serde.trace.HexStringUtil;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A model built from what a window of days was given, for a date the change history does not reach.
 *
 * <p>This is the second of the two answers for a date. Where there is a change row at or before it
 * the learnt model is wound back, which is exact and carries what no fold can - constraints the
 * window has not exercised since, and the figures on each node. Where there is not, there is nothing
 * to wind back through and this is the only answer.
 *
 * <p>What comes out holds what was <b>seen</b> over the window. The learnt model holds what was seen
 * plus everything never contradicted, so over a gap longer than the window the two part company. It
 * is not the learnt model and does not claim to be.
 *
 * <p>Nothing here reads a store. It takes the days already merged into one, so it can be tested
 * against a wound-back model without any of them.
 */
final class PathwayFold {

    private final Paths paths;
    private final List<Node> all = new ArrayList<>();
    private final Node[] byPosition;
    private final Node root;

    /**
     * Everything the model and the usage both need, worked out once.
     *
     * @param folded every day in the window, already merged into one by {@link DayRecord#merge}
     */
    PathwayFold(final DayRecord folded) {
        this.paths = folded.paths();

        // A node is named by the path of span names that reaches it, and that is the only stable
        // thing about it - a day record's own stand-in means nothing outside the record that wrote
        // it. The path cannot be the identity either: it carries spaces, dots, slashes and SQL text,
        // and an identity ends up in markup where a space ends the reference. So the identity is a
        // hash of the path, and the path itself travels beside it on the node.
        //
        // Held in path order, because a drawing is laid out by walking the tree and an order of
        // someone else's choosing moves the picture about between readings. Not the order the record
        // hands them over in: a day record is written in path order but DayRecord.merge appends the
        // nodes a later day saw first to the end, so a window would lay its siblings out by which
        // day each of them first appeared, and two overlapping windows would disagree.
        final Map<List<String>, Node> byPath = new TreeMap<>(PathwaySerde::byPath);
        folded.nodes().forEach((path, seen) -> byPath.put(path, new Node(path, identity(path), seen)));
        all.addAll(byPath.values());

        Node top = null;
        for (final Node node : all) {
            if (node.path.size() == 1) {
                top = node;
            } else {
                // A node's parent is its path less the last step.
                final Node above = byPath.get(node.path.subList(0, node.path.size() - 1));
                if (above != null) {
                    above.children.add(node);
                }
            }
        }
        root = top;

        // The paths refer to their nodes by position in one list, so holding the nodes the same way
        // is all it takes to read one: no name to look up, and the same array answers what a step
        // points at and what each position should be called once the model is built.
        final List<String> standIns = paths.getNodes();
        byPosition = new Node[standIns.size()];
        final Map<String, Node> byStandIn = new LinkedHashMap<>();
        all.forEach(node -> {
            if (node.seen.nodeUuid() != null) {
                byStandIn.put(node.seen.nodeUuid(), node);
            }
        });
        for (int i = 0; i < standIns.size(); i++) {
            byPosition[i] = byStandIn.get(standIns.get(i));
        }

        stampLastUsed();
    }

    /** The model those days add up to. */
    Pathway model(final PathKey pathKey, final String name) {
        // No create or update time, and no count of changes: this was built from evidence rather
        // than learnt, and a made-up instant would put an arrival time in a field that says when
        // work ran.
        return new Pathway(name, null, null, latestUse(), spansUsed(), 0L, pathKey,
                tree(root), renamedPaths());
    }

    /**
     * How much each node was used over the window, by the identity {@link #model} gives it.
     *
     * <p>The drawing takes its link widths and its colours from this rather than from the nodes, so
     * the two have to name a node the same way.
     */
    Map<String, NodeUsage> usage() {
        final Map<String, NodeUsage> usage = new LinkedHashMap<>();
        all.forEach(node -> usage.put(node.identity,
                new NodeUsage(node.identity, node.seen.timesUsed(), node.lastUsed)));
        return usage;
    }

    // A day record counts the spans folded into each node but never says when any of them ran. The
    // paths do: each carries when it was last taken and names the nodes it goes through, so a node
    // was last used whenever the latest path through it was taken.
    //
    // Taken latest path first, so the first walk to reach a step already carries the latest time of
    // anything through it and the rest can stop there. The steps are a shared structure rather than
    // a tree - one subtree stands for every path that takes it - so without that a path is expanded
    // once per path that reaches it, which is what PathRecorder.merge guards against for the same
    // reason.
    private void stampLastUsed() {
        final List<PathUse> latestFirst = new ArrayList<>(paths.getPaths());
        latestFirst.sort((first, second) ->
                Times.compare(second.getLastUsedTime(), first.getLastUsedTime()));
        final boolean[] stamped = new boolean[paths.getSteps().size()];
        latestFirst.forEach(use -> walk(use.getRoot(), use.getLastUsedTime(), stamped));
    }

    private void walk(final int step, final NanoTime takenAt, final boolean[] stamped) {
        if (step < 0 || step >= stamped.length || stamped[step]) {
            return;
        }
        stamped[step] = true;
        final PathStep here = paths.getSteps().get(step);
        final int position = here.getNode();
        if (position >= 0 && position < byPosition.length) {
            final Node node = byPosition[position];
            if (node != null && Times.compare(node.lastUsed, takenAt) < 0) {
                node.lastUsed = takenAt;
            }
        }
        here.getSteps().forEach(child -> walk(child, takenAt, stamped));
    }

    private PathNode tree(final Node node) {
        if (node == null) {
            return null;
        }
        final List<PathNode> below = new ArrayList<>(node.children.size());
        node.children.forEach(child -> below.add(tree(child)));
        // Nothing is retired, because retiring is what the narrowing does to a learnt model, and
        // nothing has changed until the differences between days exist to count.
        return new PathNode(node.identity, node.path.get(node.path.size() - 1), node.path, below,
                constraints(node), node.seen.timesUsed(), 0L, node.lastUsed, null);
    }

    // The counts and the time are left off each constraint: a day record says what a constraint was
    // given and what justifies it, not how often it was checked, and a made-up figure reads as real.
    private Map<String, Constraint> constraints(final Node node) {
        final Map<String, Constraint> constraints = new LinkedHashMap<>();
        node.seen.constraints().forEach((name, observed) -> constraints.put(name,
                new Constraint(name, observed.value(), observed.optional(), 0L, null)));
        return constraints;
    }

    // The paths name their nodes by position in one list of stand-ins. Writing the identities into
    // that list in the same order leaves every index in the structure pointing where it did, so the
    // steps and the paths themselves carry over untouched.
    private Paths renamedPaths() {
        final List<String> named = new ArrayList<>(byPosition.length);
        for (int i = 0; i < byPosition.length; i++) {
            named.add(byPosition[i] == null
                    ? paths.getNodes().get(i)
                    : byPosition[i].identity);
        }
        return new Paths(named, paths.getSteps(), paths.getPaths(), paths.getSources());
    }

    private long spansUsed() {
        long total = 0L;
        for (final Node node : all) {
            total += node.seen.timesUsed();
        }
        return total;
    }

    private NanoTime latestUse() {
        NanoTime latest = null;
        for (final Node node : all) {
            if (Times.compare(latest, node.lastUsed) < 0) {
                latest = node.lastUsed;
            }
        }
        return latest;
    }

    /**
     * What a model built from day records calls a node.
     *
     * <p>Shared rather than private because the differences between two days name their nodes the
     * same way. A row that named a node differently from the drawing beside it could not pick it
     * out, which is the whole reason this is worked out from the path rather than taken from a day
     * record's own stand-in - that means nothing outside the record that wrote it.
     */
    static String identity(final List<String> path) {
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (final String step : path) {
                digest.update(step.getBytes(StandardCharsets.UTF_8));
                // So that ["a", "bc"] and ["ab", "c"] do not hash alike.
                digest.update((byte) 0);
            }
            // Eight bytes is far more than a model's hundred-odd nodes need to stay apart, and short
            // enough to read in a drawing's markup.
            return HexStringUtil.encode(Arrays.copyOf(digest.digest(), 8));
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    // One node, with everything worked out about it in one place rather than in a map each.
    private static final class Node {

        private final List<String> path;
        private final String identity;
        private final DayRecord.ObservedNode seen;
        private final List<Node> children = new ArrayList<>();
        private NanoTime lastUsed;

        private Node(final List<String> path, final String identity,
                     final DayRecord.ObservedNode seen) {
            this.path = path;
            this.identity = identity;
            this.seen = seen;
        }
    }
}
