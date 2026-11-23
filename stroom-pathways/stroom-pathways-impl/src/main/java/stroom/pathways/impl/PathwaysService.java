/*
 * Copyright 2025 Crown Copyright
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
import stroom.cluster.lock.api.ClusterLockService;
import stroom.docref.DocRef;
import stroom.docstore.api.DocumentNotFoundException;
import stroom.pathways.shared.AddPathway;
import stroom.pathways.shared.DeletePathway;
import stroom.pathways.shared.FetchPathwayRequest;
import stroom.pathways.shared.FindPathwayCriteria;
import stroom.pathways.shared.FindPathwayMutationCriteria;
import stroom.pathways.shared.PathwayAsAt;
import stroom.pathways.shared.PathwayMutationResultPage;
import stroom.pathways.shared.PathwayResultPage;
import stroom.pathways.shared.PathwaysDoc;
import stroom.pathways.shared.RemoveNode;
import stroom.pathways.shared.UpdatePathway;
import stroom.pathways.shared.otel.trace.NanoTime;
import stroom.pathways.shared.pathway.MutationType;
import stroom.pathways.shared.pathway.PathNode;
import stroom.pathways.shared.pathway.PathStep;
import stroom.pathways.shared.pathway.PathUse;
import stroom.pathways.shared.pathway.Paths;
import stroom.pathways.shared.pathway.Pathway;
import stroom.pathways.shared.pathway.PathwayMutation;
import stroom.planb.impl.dao.LmdbWriter;
import stroom.planb.impl.dao.ShardKeyRouter;
import stroom.planb.impl.dao.trace.NanoTimeUtil;
import stroom.planb.impl.dao.trace.PathwaysDb;
import stroom.planb.shared.SharedFileStoreSettings;
import stroom.security.api.SecurityContext;
import stroom.security.shared.DocumentPermission;
import stroom.util.logging.LambdaLogger;
import stroom.util.logging.LambdaLoggerFactory;
import stroom.util.logging.LogUtil;
import stroom.util.shared.NullSafe;
import stroom.util.shared.PermissionException;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

@Singleton
public class PathwaysService {

    private static final LambdaLogger LOGGER = LambdaLoggerFactory.getLogger(PathwaysService.class);

    private final PathwaysStore pathwaysStore;
    private final ShardedPathwayReader shardedPathwayReader;
    private final PathwaysShardStore shardStore;
    private final ClusterLockService clusterLockService;
    private final ByteBuffers byteBuffers;
    private final MutationLog mutationLog;
    private final PathwaySerde pathwaySerde;
    private final SecurityContext securityContext;

    @Inject
    public PathwaysService(final ShardedPathwayReader shardedPathwayReader,
                           final PathwaysStore pathwaysStore,
                           final PathwaysShardStore shardStore,
                           final ClusterLockService clusterLockService,
                           final ByteBuffers byteBuffers,
                           final MutationLog mutationLog,
                           final PathwaySerde pathwaySerde,
                           final SecurityContext securityContext) {
        this.shardedPathwayReader = shardedPathwayReader;
        this.pathwaysStore = pathwaysStore;
        this.shardStore = shardStore;
        this.clusterLockService = clusterLockService;
        this.byteBuffers = byteBuffers;
        this.mutationLog = mutationLog;
        this.pathwaySerde = pathwaySerde;
        this.securityContext = securityContext;
    }

    public PathwayResultPage findPathways(final FindPathwayCriteria criteria) {
        final PathwaysDoc pathwaysDoc = pathwaysStore.readDocument(criteria.getDataSourceRef());
        if (pathwaysDoc == null) {
            throw new DocumentNotFoundException(criteria.getDataSourceRef());
        }

        // The model lives on the shared file store, split by operation name, so any node can answer
        // from it. Nothing has to work out which node holds it, and nothing has to be asked.
        return shardedPathwayReader.findPathways(pathwaysDoc, criteria);
    }

    public PathwayMutationResultPage findMutations(final FindPathwayMutationCriteria criteria) {
        final PathwaysDoc pathwaysDoc = pathwaysStore.readDocument(criteria.getPathwaysDocRef());
        if (pathwaysDoc == null) {
            throw new DocumentNotFoundException(criteria.getPathwaysDocRef());
        }
        return shardedPathwayReader.findMutations(pathwaysDoc, criteria);
    }

    /**
     * One learnt pathway in full, for the tree. The list is served summaries instead, because a
     * pathway carries the whole model it has learnt and a page of them will not fit in memory.
     */
    public PathwayAsAt fetchPathway(final FetchPathwayRequest request) {
        final PathwaysDoc pathwaysDoc = pathwaysStore.readDocument(request.getPathwaysDocRef());
        if (pathwaysDoc == null) {
            throw new DocumentNotFoundException(request.getPathwaysDocRef());
        }
        return shardedPathwayReader
                .fetchPathwayAsAt(pathwaysDoc, request.getName(), request.getTimeRange(),
                        request.getToSequence())
                .orElse(null);
    }

    public Boolean addPathway(final AddPathway addPathway) {
        throw new UnsupportedOperationException("Not implemented");
    }

    public Boolean updatePathway(final UpdatePathway updatePathway) {
        throw new UnsupportedOperationException("Not implemented");
    }

    /**
     * Forgets one learnt pathway, there and then.
     *
     * <p>Nothing writes to the model on the shared store directly: a shard is taken down, worked on
     * locally and put back, and only the holder of that shard's cluster lock may do it. So this takes
     * the same lock the job that applies traces takes. That job holds a shard for at most
     * {@link PathwaysProcessor#MAX_TIME_PER_HOLD} and asks for the lock without waiting, so it stands
     * aside for this rather than this queueing behind a whole cycle of it; waiting here is bounded by
     * the configured cluster lock timeout, after which the attempt fails and says who was holding it.
     *
     * <p>A pathway is keyed on its name and the name decides its shard, so this is one shard. Its
     * history is spread over that shard's live copy and every week bucket the narrowing has moved
     * older records into, and nothing says which buckets hold this pathway, so every bucket of the
     * shard is copied down and searched. Only the buckets that had something removed are published
     * back.
     *
     * <p>The buckets are cleared before the live shard. If one fails, the model is still in the live
     * shard, so the pathway is still listed and the delete can be asked for again; done the other way
     * round, it would vanish from the list with its archived history left where nothing can reach it.
     *
     * <p>What is forgotten can be learnt again. The next trace down this path builds the pathway
     * afresh, which is the point: this is for a model that has learnt something wrong, not for
     * keeping a pathway out of the model.
     */
    public Boolean deletePathway(final DeletePathway deletePathway) {
        final PathwaysDoc doc = pathwaysStore.readDocument(deletePathway.getDocRef());
        if (doc == null) {
            throw new DocumentNotFoundException(deletePathway.getDocRef());
        }
        checkEdit(doc);
        final String name = deletePathway.getName();
        final SharedFileStoreSettings settings = doc.getSharedFileStore();
        if (settings == null
            || NullSafe.isBlankString(settings.getSharedPath())
            || NullSafe.isBlankString(name)) {
            return Boolean.FALSE;
        }

        final int shard = ShardKeyRouter.computeShardIndex(name, Math.max(1, settings.getShardCount()));
        final byte[] pathwayKey = name.getBytes(StandardCharsets.UTF_8);
        final AtomicBoolean removed = new AtomicBoolean();
        clusterLockService.lock(PathwaysProcessor.lockName(doc.getUuid(), shard), () -> {
            try {
                for (final String week : shardStore.archivedWeeks(doc, shard)) {
                    shardStore.withBucket(doc, shard, week, db -> removeFromBucket(db, name, week, pathwayKey));
                }
                shardStore.withShard(doc, shard,
                        localDir -> removeFromShard(localDir, name, pathwayKey, removed));
            } catch (final IOException e) {
                throw new UncheckedIOException(e);
            }
        });
        return removed.get();
    }

    private void checkEdit(final PathwaysDoc doc) {
        final DocRef docRef = doc.asDocRef();
        if (!securityContext.hasDocumentPermission(docRef, DocumentPermission.EDIT)) {
            throw new PermissionException(
                    securityContext.getUserRef(),
                    LogUtil.message("You are not authorised to change {}", docRef));
        }
    }

    // Everything the live shard holds about this pathway: what it learnt and all of its history. Said
    // to have changed the shard only where something was actually taken out, so a pathway that was
    // not there does not have the model written back for nothing.
    private boolean removeFromShard(final Path localDir,
                                    final String name,
                                    final byte[] pathwayKey,
                                    final AtomicBoolean removed) {
        try (final PathwaysDb db = PathwaysDb.create(localDir, byteBuffers, false)) {
            try (final LmdbWriter writer = db.createWriter()) {
                final boolean[] went = {false};
                withKey(pathwayKey, key -> went[0] = db.getPathways().delete(writer, key));
                final int history = removeHistory(writer, db, pathwayKey);
                writer.commit();
                removed.set(went[0]);
                LOGGER.info(() -> LogUtil.message(
                        "Removed pathway {}: {} model, {} history row(s)",
                        name, went[0] ? "1" : "0", history));
                return went[0] || history > 0;
            }
        }
    }

    private boolean removeFromBucket(final PathwaysDb db,
                                     final String name,
                                     final String week,
                                     final byte[] pathwayKey) {
        try (final LmdbWriter writer = db.createWriter()) {
            final int history = removeHistory(writer, db, pathwayKey);
            writer.commit();
            if (history > 0) {
                LOGGER.info(() -> LogUtil.message(
                        "Removed pathway {} from the {} bucket: {} history row(s)", name, week, history));
            }
            return history > 0;
        }
    }

    /**
     * Removes one node of a learnt pathway, and everything under it, until a trace carries it again.
     *
     * <p>Each node is retired and its constraints are wiped, with a change row for each, so that the
     * model shown for an earlier moment still has the node as it was. The next trace to carry a node
     * revives it and learns its constraints again from what that trace shows. Every path that runs
     * through a removed node is dropped.
     *
     * <p>Takes the same shard lock as {@link #deletePathway}. Only the live shard is changed: the
     * week buckets hold history, which this adds to rather than rewrites.
     *
     * @return whether anything was removed. Never the root, because removing that is deleting the
     * pathway, and never a node already retired, whose constraints may have been learnt since the
     * caller last saw it.
     */
    public Boolean removeNode(final RemoveNode removeNode) {
        final PathwaysDoc doc = pathwaysStore.readDocument(removeNode.getDocRef());
        if (doc == null) {
            throw new DocumentNotFoundException(removeNode.getDocRef());
        }
        checkEdit(doc);
        final String name = removeNode.getName();
        final String uuid = removeNode.getNodeUuid();
        final SharedFileStoreSettings settings = doc.getSharedFileStore();
        if (settings == null
            || NullSafe.isBlankString(settings.getSharedPath())
            || NullSafe.isBlankString(name)
            || NullSafe.isBlankString(uuid)) {
            return Boolean.FALSE;
        }

        final int shard = ShardKeyRouter.computeShardIndex(name, Math.max(1, settings.getShardCount()));
        final byte[] pathwayKey = name.getBytes(StandardCharsets.UTF_8);
        final AtomicBoolean removed = new AtomicBoolean();
        clusterLockService.lock(PathwaysProcessor.lockName(doc.getUuid(), shard), () -> {
            try {
                shardStore.withShard(doc, shard, localDir -> {
                    removed.set(removeFromModel(localDir, name, pathwayKey, uuid));
                    return removed.get();
                });
            } catch (final IOException e) {
                throw new UncheckedIOException(e);
            }
        });
        return removed.get();
    }

    // Not private so that what it does to a shard can be tested without a cluster lock and a document
    // store standing behind it.
    boolean removeFromModel(final Path localDir,
                            final String name,
                            final byte[] pathwayKey,
                            final String uuid) {
        try (final PathwaysDb db = PathwaysDb.create(localDir, byteBuffers, false)) {
            try (final LmdbWriter writer = db.createWriter()) {
                final Pathway[] found = {null};
                withKey(pathwayKey, key -> found[0] = db.getPathways().get(writer.getWriteTxn(), key,
                        value -> value == null
                                ? null
                                : pathwaySerde.readPathway(value)));
                final PathNode root = NullSafe.get(found[0], Pathway::getRoot);
                if (root == null || uuid.equals(root.getUuid())) {
                    return false;
                }
                // Already removed or retired, perhaps since the caller's drawing was made. Removing it
                // again would wipe constraints it has learnt since, which the caller has not seen.
                final PathNode target = find(root, uuid);
                if (target == null || target.isRetired()) {
                    return false;
                }

                final NanoTime now = NanoTimeUtil.fromInstant(Instant.now());
                final List<PathwayMutation> mutations = new ArrayList<>();
                final Set<String> gone = new HashSet<>();
                PathNode newRoot = without(root, uuid, false, now, mutations, gone);
                if (gone.isEmpty()) {
                    return false;
                }
                final Paths paths = withoutPathsThrough(found[0].getPaths(), gone, root, now, mutations);
                // A dropped path is counted against the root by the change rows, so the root says when.
                if (paths != found[0].getPaths()) {
                    newRoot = newRoot.copy().lastChangedTime(now).build();
                }

                final Pathway updated = found[0].copy().root(newRoot).paths(paths).build();
                byteBuffers.useBytes(pathwayKey, (Consumer<ByteBuffer>) keyByteBuffer ->
                        pathwaySerde.writePathway(updated, 0, valueByteBuffer ->
                                db.getPathways().insert(writer, keyByteBuffer, valueByteBuffer)));
                final PathwayHistory history = mutationLog.history(writer, db, pathwayKey);
                mutationLog.append(writer, db, pathwayKey, mutations, newRoot, -1, history);
                mutationLog.writeCounts(writer, db, pathwayKey, history);
                writer.commit();
                LOGGER.info(() -> LogUtil.message("Removed {} node(s) from pathway {} at {}",
                        gone.size(), name, uuid));
                return true;
            }
        }
    }

    private static PathNode find(final PathNode node, final String uuid) {
        if (uuid.equals(node.getUuid())) {
            return node;
        }
        for (final PathNode child : NullSafe.list(node.getChildren())) {
            final PathNode found = find(child, uuid);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    // The tree with the named node, and everything under it, retired and its constraints wiped. A node
    // the narrowing had already retired keeps the time it was retired at and gets no removal row, so
    // winding back past the removal leaves it retired as it was.
    private static PathNode without(final PathNode node,
                                    final String uuid,
                                    final boolean inside,
                                    final NanoTime now,
                                    final List<PathwayMutation> mutations,
                                    final Set<String> gone) {
        final boolean here = inside || uuid.equals(node.getUuid());
        final PathNode.Builder builder = node.copy();
        if (here) {
            gone.add(node.getUuid());
            // The removal ahead of the constraints it wiped, so going back to that row puts them back.
            if (!node.isRetired()) {
                mutations.add(new PathwayMutation(0L, now, now, -1, null, null, node.getNodePath(),
                        node.getUuid(), null, MutationType.NODE_REMOVED, false, null, null));
                builder.retiredTime(now);
            }
            NullSafe.map(node.getConstraints()).forEach((name, constraint) ->
                    mutations.add(new PathwayMutation(0L, now, now, -1, null, null, node.getNodePath(),
                            node.getUuid(), name, MutationType.CONSTRAINT_CLEARED, constraint.isOptional(),
                            constraint.getValue(), null)));
            builder.constraints(new HashMap<>()).lastChangedTime(now);
        }
        final List<PathNode> children = new ArrayList<>();
        NullSafe.list(node.getChildren()).forEach(child ->
                children.add(without(child, uuid, here, now, mutations, gone)));
        return builder.children(children).build();
    }

    // The paths with every one that runs through a removed node left out. The shapes they were built
    // from stay where they are, as they do when the narrowing drops a path, because the paths that
    // remain name shapes by position.
    private static Paths withoutPathsThrough(final Paths paths,
                                             final Set<String> gone,
                                             final PathNode root,
                                             final NanoTime now,
                                             final List<PathwayMutation> mutations) {
        if (paths == null) {
            return null;
        }
        final Map<Integer, Boolean> known = new HashMap<>();
        final List<PathUse> kept = new ArrayList<>();
        for (final PathUse path : NullSafe.list(paths.getPaths())) {
            if (reaches(path.getRoot(), paths, gone, known)) {
                mutations.add(new PathwayMutation(0L, now, now, -1, null, null, root.getNodePath(),
                        null, null, MutationType.PATH_DROPPED, false, null, null));
            } else {
                kept.add(path);
            }
        }
        return kept.size() == NullSafe.list(paths.getPaths()).size()
                ? paths
                : new Paths(paths.getNodes(), paths.getSteps(), kept, paths.getSources());
    }

    // Whether a shape, or any shape under it, is a visit to a removed node. Answers are kept by shape,
    // because paths share shapes and a pathway can hold many of them.
    private static boolean reaches(final int shape,
                                   final Paths paths,
                                   final Set<String> gone,
                                   final Map<Integer, Boolean> known) {
        final List<PathStep> steps = NullSafe.list(paths.getSteps());
        if (shape < 0 || shape >= steps.size()) {
            return false;
        }
        final Boolean answer = known.get(shape);
        if (answer != null) {
            return answer;
        }
        final PathStep step = steps.get(shape);
        final List<String> nodes = NullSafe.list(paths.getNodes());
        boolean result = step.getNode() >= 0
                         && step.getNode() < nodes.size()
                         && gone.contains(nodes.get(step.getNode()));
        for (final Integer child : step.getSteps()) {
            if (result) {
                break;
            }
            result = reaches(child, paths, gone, known);
        }
        known.put(shape, result);
        return result;
    }

    // Every row about one pathway other than its model: each change and usage reading, each day's
    // record of what went through it, and the index of which of those days are in a bucket. A bucket
    // has no index rows of its own, so there that delete finds nothing.
    private int removeHistory(final LmdbWriter writer, final PathwaysDb db, final byte[] pathwayKey) {
        int rows = mutationLog.removeAll(writer, db.getMutations(), pathwayKey);
        rows += db.getObservedValues().deletePrefixed(writer, TraceProcessor.observedValuesPrefix(pathwayKey));
        final boolean[] indexed = {false};
        withKey(pathwayKey, key -> indexed[0] = db.getDayIndex().delete(writer, key));
        return indexed[0]
                ? rows + 1
                : rows;
    }

    private static void withKey(final byte[] keyBytes, final Consumer<ByteBuffer> consumer) {
        final ByteBuffer keyBuffer = ByteBuffer.allocateDirect(keyBytes.length);
        keyBuffer.put(keyBytes).flip();
        consumer.accept(keyBuffer);
    }
}
