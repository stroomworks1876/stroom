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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    private final DayRecord folded;
    private final Map<List<String>, Node> nodes = new LinkedHashMap<>();
    private final List<String> rootPath;

    /**
     * Everything the model and the usage both need, worked out once.
     *
     * @param folded every day in the window, already merged into one by {@link DayRecord#merge}
     */
    static PathwayFold of(final DayRecord folded) {
        return new PathwayFold(folded);
    }

    private PathwayFold(final DayRecord folded) {
        this.folded = folded;

        // A node is named by the path of span names that reaches it, and that is the only stable
        // thing about it - a day record's own stand-in means nothing outside the record that wrote
        // it. The path cannot be the identity either: it carries spaces, dots, slashes and SQL text,
        // and an identity ends up in markup where a space ends the reference. So the identity is a
        // hash of the path, and the path itself travels beside it on the node.
        //
        // Held in the order the record holds them, because a drawing is laid out by walking the tree
        // and an order of someone else's choosing moves the picture about between readings.
        final Map<String, Node> byStandIn = new HashMap<>();
        folded.nodes().forEach((path, seen) -> {
            final Node node = new Node(identity(path), seen);
            nodes.put(path, node);
            if (seen.nodeUuid() != null) {
                byStandIn.put(seen.nodeUuid(), node);
            }
        });

        List<String> root = null;
        for (final List<String> path : nodes.keySet()) {
            if (path.size() == 1) {
                root = path;
            } else {
                // A node's parent is its path less the last step.
                final Node above = nodes.get(path.subList(0, path.size() - 1));
                if (above != null) {
                    above.children.add(path);
                }
            }
        }
        rootPath = root;

        // A day record counts the spans folded into each node but never says when any of them ran.
        // The paths do: each carries when it was last taken and names the nodes it goes through, so
        // a node was last used whenever the latest path through it was taken.
        for (final PathUse use : folded.paths().getPaths()) {
            walk(use.getRoot(), byStandIn, use.getLastUsedTime());
        }
    }

    /** The model those days add up to. */
    Pathway model(final PathKey pathKey, final String name) {
        // No create or update time, and no count of changes: this was built from evidence rather
        // than learnt, and a made-up instant would put an arrival time in a field that says when
        // work ran.
        return new Pathway(name, null, null, latestUse(), spansUsed(), 0L, pathKey,
                tree(rootPath), renamedPaths());
    }

    /**
     * How much each node was used over the window, by the identity {@link #model} gives it.
     *
     * <p>The drawing takes its link widths and its colours from this rather than from the nodes, so
     * the two have to name a node the same way.
     */
    Map<String, NodeUsage> usage() {
        final Map<String, NodeUsage> usage = new LinkedHashMap<>();
        nodes.values().forEach(node -> usage.put(node.identity,
                new NodeUsage(node.identity, node.seen.timesUsed(), node.lastUsed)));
        return usage;
    }

    private void walk(final int step, final Map<String, Node> byStandIn, final NanoTime takenAt) {
        final List<PathStep> steps = folded.paths().getSteps();
        if (step < 0 || step >= steps.size()) {
            return;
        }
        final PathStep here = steps.get(step);
        final Node node = byStandIn.get(nameAt(here.getNode()));
        if (node != null && Times.compare(node.lastUsed, takenAt) < 0) {
            node.lastUsed = takenAt;
        }
        for (final Integer child : here.getSteps()) {
            walk(child, byStandIn, takenAt);
        }
    }

    private String nameAt(final int index) {
        final List<String> named = folded.paths().getNodes();
        return index < 0 || index >= named.size()
                ? null
                : named.get(index);
    }

    private PathNode tree(final List<String> path) {
        if (path == null) {
            return null;
        }
        final Node node = nodes.get(path);
        final List<PathNode> below = new ArrayList<>(node.children.size());
        node.children.forEach(child -> below.add(tree(child)));
        // Nothing is retired, because retiring is what the narrowing does to a learnt model, and
        // nothing has changed until the differences between days exist to count.
        return new PathNode(node.identity, path.get(path.size() - 1), path, below,
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

    // The paths name their nodes by the stand-in the record chose. Rewriting that one list to hold
    // the identities instead leaves every index in the structure pointing where it did, so the steps
    // and the paths themselves carry over untouched.
    private Paths renamedPaths() {
        final Map<String, String> identities = new HashMap<>();
        nodes.values().forEach(node -> {
            if (node.seen.nodeUuid() != null) {
                identities.put(node.seen.nodeUuid(), node.identity);
            }
        });
        final Paths paths = folded.paths();
        final List<String> named = new ArrayList<>(paths.getNodes().size());
        paths.getNodes().forEach(standIn -> named.add(identities.getOrDefault(standIn, standIn)));
        return new Paths(named, paths.getSteps(), paths.getPaths(), paths.getSources());
    }

    private long spansUsed() {
        long total = 0L;
        for (final Node node : nodes.values()) {
            total += node.seen.timesUsed();
        }
        return total;
    }

    private NanoTime latestUse() {
        NanoTime latest = null;
        for (final Node node : nodes.values()) {
            if (Times.compare(latest, node.lastUsed) < 0) {
                latest = node.lastUsed;
            }
        }
        return latest;
    }

    private static String identity(final List<String> path) {
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (final String step : path) {
                digest.update(step.getBytes(StandardCharsets.UTF_8));
                // So that ["a", "bc"] and ["ab", "c"] do not hash alike.
                digest.update((byte) 0);
            }
            final byte[] hash = digest.digest();
            final StringBuilder sb = new StringBuilder(16);
            for (int i = 0; i < 8; i++) {
                sb.append(Character.forDigit((hash[i] >> 4) & 0xF, 16));
                sb.append(Character.forDigit(hash[i] & 0xF, 16));
            }
            return sb.toString();
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    // One node, with everything worked out about it in one place rather than in a map each.
    private static final class Node {

        private final String identity;
        private final DayRecord.ObservedNode seen;
        private final List<List<String>> children = new ArrayList<>();
        private NanoTime lastUsed;

        private Node(final String identity, final DayRecord.ObservedNode seen) {
            this.identity = identity;
            this.seen = seen;
        }
    }
}
