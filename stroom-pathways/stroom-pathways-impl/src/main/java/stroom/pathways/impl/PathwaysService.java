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
import stroom.pathways.shared.EditConstraint;
import stroom.pathways.shared.FetchPathwayRequest;
import stroom.pathways.shared.FindPathwayCriteria;
import stroom.pathways.shared.FindPathwayMutationCriteria;
import stroom.pathways.shared.PathwayAsAt;
import stroom.pathways.shared.PathwayMutationResultPage;
import stroom.pathways.shared.PathwayResultPage;
import stroom.pathways.shared.PathwaysDoc;
import stroom.pathways.shared.RemoveConstraints;
import stroom.pathways.shared.RemoveNode;
import stroom.pathways.shared.RollbackNode;
import stroom.pathways.shared.otel.trace.NanoTime;
import stroom.pathways.shared.pathway.AbstractRange;
import stroom.pathways.shared.pathway.AbstractSet;
import stroom.pathways.shared.pathway.AbstractValue;
import stroom.pathways.shared.pathway.Constraint;
import stroom.pathways.shared.pathway.ConstraintValue;
import stroom.pathways.shared.pathway.MutationType;
import stroom.pathways.shared.pathway.NanoTimeRange;
import stroom.pathways.shared.pathway.PathNode;
import stroom.pathways.shared.pathway.PathStep;
import stroom.pathways.shared.pathway.PathUse;
import stroom.pathways.shared.pathway.Paths;
import stroom.pathways.shared.pathway.Pathway;
import stroom.pathways.shared.pathway.PathwayMutation;
import stroom.pathways.shared.pathway.Regex;
import stroom.planb.impl.dao.LmdbWriter;
import stroom.planb.impl.dao.ShardKeyRouter;
import stroom.planb.impl.dao.trace.NanoTimeUtil;
import stroom.planb.impl.dao.trace.PathwaysDb;
import stroom.planb.shared.SharedFileStoreSettings;
import stroom.security.api.SecurityContext;
import stroom.security.shared.DocumentPermission;
import stroom.util.exception.ThrowingConsumer;
import stroom.util.logging.LambdaLogger;
import stroom.util.logging.LambdaLoggerFactory;
import stroom.util.logging.LogUtil;
import stroom.util.shared.EntityServiceException;
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
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

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
        final PathwaysDoc doc = editable(deletePathway.getDocRef());
        final String name = deletePathway.getName();
        final SharedFileStoreSettings settings = doc.getSharedFileStore();
        if (settings == null
            || NullSafe.isBlankString(settings.getSharedPath())
            || NullSafe.isBlankString(name)) {
            return Boolean.FALSE;
        }

        final byte[] pathwayKey = name.getBytes(StandardCharsets.UTF_8);
        final AtomicBoolean removed = new AtomicBoolean();
        locked(doc, name, shard -> {
            for (final String week : shardStore.archivedWeeks(doc, shard)) {
                shardStore.withBucket(doc, shard, week,
                        db -> removeFromBucket(db, doc.getUuid(), name, week, pathwayKey));
            }
            shardStore.withShard(doc, shard,
                    localDir -> removeFromShard(localDir, doc.getUuid(), name, pathwayKey, removed));
        });
        return removed.get();
    }

    // The document, once it is known to exist and the user may change it.
    private PathwaysDoc editable(final DocRef docRef) {
        final PathwaysDoc doc = pathwaysStore.readDocument(docRef);
        if (doc == null) {
            throw new DocumentNotFoundException(docRef);
        }
        final DocRef ref = doc.asDocRef();
        if (!securityContext.hasDocumentPermission(ref, DocumentPermission.EDIT)) {
            throw new PermissionException(
                    securityContext.getUserRef(),
                    LogUtil.message("You are not authorised to change {}", ref));
        }
        return doc;
    }

    // Runs the work, given the shard the pathway's name routes to, while holding that shard's cluster
    // lock. The document must name a shared file store.
    private void locked(final PathwaysDoc doc,
                        final String name,
                        final ThrowingConsumer<Integer, IOException> work) {
        final int shard = ShardKeyRouter.computeShardIndex(name,
                Math.max(1, doc.getSharedFileStore().getShardCount()));
        clusterLockService.lock(PathwaysProcessor.lockName(doc.getUuid(), shard), () -> {
            try {
                work.accept(shard);
            } catch (final IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    // Everything the live shard holds about this pathway: what it learnt and all of its history. Said
    // to have changed the shard only where something was actually taken out, so a pathway that was
    // not there does not have the model written back for nothing.
    private boolean removeFromShard(final Path localDir,
                                    final String docUuid,
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
                        "Removed pathway {} in document {}: {} model, {} history row(s)",
                        name, docUuid, went[0] ? "1" : "0", history));
                return went[0] || history > 0;
            }
        }
    }

    private boolean removeFromBucket(final PathwaysDb db,
                                     final String docUuid,
                                     final String name,
                                     final String week,
                                     final byte[] pathwayKey) {
        try (final LmdbWriter writer = db.createWriter()) {
            final int history = removeHistory(writer, db, pathwayKey);
            writer.commit();
            if (history > 0) {
                LOGGER.info(() -> LogUtil.message(
                        "Removed pathway {} in document {} from the {} bucket: {} history row(s)",
                        name, docUuid, week, history));
            }
            return history > 0;
        }
    }

    /**
     * Removes one node of a learnt pathway, and everything under it, until a trace carries it again.
     *
     * <p>Each node is retired, with a change row for each, and keeps its constraints. The next trace to
     * carry a node revives it and learns its constraints again from what that trace shows. Every path
     * that runs through a removed node is dropped.
     *
     * <p>Takes the same shard lock as {@link #deletePathway}. Only the live shard is changed: the
     * week buckets hold history, which this adds to rather than rewrites.
     *
     * @return whether anything was removed. Never the root, because removing that is deleting the
     * pathway, and never a node already retired, which the caller may not have seen retired.
     */
    public Boolean removeNode(final RemoveNode removeNode) {
        final PathwaysDoc doc = editable(removeNode.getDocRef());
        final String name = removeNode.getName();
        final String uuid = removeNode.getNodeUuid();
        final SharedFileStoreSettings settings = doc.getSharedFileStore();
        if (settings == null
            || NullSafe.isBlankString(settings.getSharedPath())
            || NullSafe.isBlankString(name)
            || NullSafe.isBlankString(uuid)) {
            return Boolean.FALSE;
        }

        final byte[] pathwayKey = name.getBytes(StandardCharsets.UTF_8);
        final AtomicBoolean removed = new AtomicBoolean();
        locked(doc, name, shard -> shardStore.withShard(doc, shard, localDir -> {
            removed.set(removeFromModel(localDir, doc.getUuid(), name, pathwayKey, uuid));
            return removed.get();
        }));
        return removed.get();
    }

    // Not private so that what it does to a shard can be tested without a cluster lock and a document
    // store standing behind it.
    boolean removeFromModel(final Path localDir,
                            final String docUuid,
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
                save(writer, db, pathwayKey, updated, mutations);
                LOGGER.info(() -> LogUtil.message("Removed {} node(s) from pathway {} in document {} at node {}",
                        gone.size(), name, docUuid, uuid));
                return true;
            }
        }
    }

    private void save(final LmdbWriter writer,
                      final PathwaysDb db,
                      final byte[] pathwayKey,
                      final Pathway updated,
                      final List<PathwayMutation> mutations) {
        byteBuffers.useBytes(pathwayKey, (Consumer<ByteBuffer>) keyByteBuffer ->
                pathwaySerde.writePathway(updated, 0, valueByteBuffer ->
                        db.getPathways().insert(writer, keyByteBuffer, valueByteBuffer)));
        final PathwayHistory history = mutationLog.history(writer, db, pathwayKey);
        mutationLog.append(writer, db, pathwayKey, mutations, updated.getRoot(), -1, history);
        mutationLog.writeCounts(writer, db, pathwayKey, history);
        writer.commit();
    }

    /**
     * Removes the named constraints from one node of a learnt pathway, with a change row for each.
     * The next trace that carries one of them learns it again.
     *
     * <p>Takes the same shard lock as {@link #removeNode}.
     *
     * @return whether anything was removed. False when the node has been removed or retired, or holds
     * none of the named constraints, since the caller last saw it.
     */
    public Boolean removeConstraints(final RemoveConstraints removeConstraints) {
        final PathwaysDoc doc = editable(removeConstraints.getDocRef());
        final String name = removeConstraints.getName();
        final String uuid = removeConstraints.getNodeUuid();
        final SharedFileStoreSettings settings = doc.getSharedFileStore();
        if (settings == null
            || NullSafe.isBlankString(settings.getSharedPath())
            || NullSafe.isBlankString(name)
            || NullSafe.isBlankString(uuid)) {
            return Boolean.FALSE;
        }

        final byte[] pathwayKey = name.getBytes(StandardCharsets.UTF_8);
        final AtomicBoolean removed = new AtomicBoolean();
        locked(doc, name, shard -> shardStore.withShard(doc, shard, localDir -> {
            removed.set(removeConstraintsFromModel(localDir, doc.getUuid(), name, pathwayKey, uuid,
                    removeConstraints.getConstraints()));
            return removed.get();
        }));
        return removed.get();
    }

    // Not private so that what it does to a shard can be tested without a cluster lock and a document
    // store standing behind it.
    boolean removeConstraintsFromModel(final Path localDir,
                                       final String docUuid,
                                       final String name,
                                       final byte[] pathwayKey,
                                       final String uuid,
                                       final List<String> names) {
        try (final PathwaysDb db = PathwaysDb.create(localDir, byteBuffers, false)) {
            try (final LmdbWriter writer = db.createWriter()) {
                final Pathway[] found = {null};
                withKey(pathwayKey, key -> found[0] = db.getPathways().get(writer.getWriteTxn(), key,
                        value -> value == null
                                ? null
                                : pathwaySerde.readPathway(value)));
                final PathNode root = NullSafe.get(found[0], Pathway::getRoot);
                final PathNode target = root == null
                        ? null
                        : find(root, uuid);
                if (target == null || target.isRetired()) {
                    return false;
                }

                final NanoTime now = NanoTimeUtil.fromInstant(Instant.now());
                final List<PathwayMutation> mutations = new ArrayList<>();
                final Map<String, Constraint> kept = new HashMap<>(NullSafe.map(target.getConstraints()));
                for (final String constraintName : NullSafe.list(names)) {
                    final Constraint constraint = kept.remove(constraintName);
                    if (constraint != null) {
                        mutations.add(removal(target, constraint, now));
                    }
                }
                if (mutations.isEmpty()) {
                    return false;
                }

                final PathNode changed = target.copy().constraints(kept).lastChangedTime(now).build();
                final PathNode newRoot = replace(root, uuid, changed);
                save(writer, db, pathwayKey, found[0].copy().root(newRoot).build(), mutations);
                LOGGER.info(() -> LogUtil.message(
                        "Removed {} constraint(s) from pathway {} in document {} at node {}",
                        mutations.size(), name, docUuid, uuid));
                return true;
            }
        }
    }

    /**
     * Adds one constraint to a node of a learnt pathway, or changes the value or optional flag of one
     * the node has, with a change row for each thing changed.
     *
     * <p>Takes the same shard lock as {@link #removeNode}, after checking the submitted constraint, so a
     * constraint with no name, a new name or an invalid value is refused without taking the lock.
     *
     * @return whether anything changed. False when the constraint asked for is the one the node holds.
     * @throws EntityServiceException when the edit is refused, with the reason as its message.
     */
    public Boolean editConstraint(final EditConstraint editConstraint) {
        final PathwaysDoc doc = editable(editConstraint.getDocRef());
        final String name = editConstraint.getName();
        final String uuid = editConstraint.getNodeUuid();
        final SharedFileStoreSettings settings = doc.getSharedFileStore();
        if (settings == null || NullSafe.isBlankString(settings.getSharedPath())) {
            throw new EntityServiceException("'" + doc.getName() + "' names no shared file store");
        }
        if (NullSafe.isBlankString(name) || NullSafe.isBlankString(uuid)) {
            throw new EntityServiceException("No pathway or node was named");
        }
        check(editConstraint.getPrevious(), editConstraint.getConstraint());

        final byte[] pathwayKey = name.getBytes(StandardCharsets.UTF_8);
        final AtomicBoolean changed = new AtomicBoolean();
        locked(doc, name, shard -> shardStore.withShard(doc, shard, localDir -> {
            changed.set(editConstraintInModel(localDir, doc.getUuid(), name, pathwayKey, uuid,
                    editConstraint.getPrevious(), editConstraint.getConstraint()));
            return changed.get();
        }));
        return changed.get();
    }

    // Not private so that what it does to a shard can be tested without a cluster lock and a document
    // store standing behind it.
    boolean editConstraintInModel(final Path localDir,
                                  final String docUuid,
                                  final String name,
                                  final byte[] pathwayKey,
                                  final String uuid,
                                  final Constraint previous,
                                  final Constraint constraint) {
        check(previous, constraint);
        try (final PathwaysDb db = PathwaysDb.create(localDir, byteBuffers, false)) {
            try (final LmdbWriter writer = db.createWriter()) {
                final Pathway[] found = {null};
                withKey(pathwayKey, key -> found[0] = db.getPathways().get(writer.getWriteTxn(), key,
                        value -> value == null
                                ? null
                                : pathwaySerde.readPathway(value)));
                final PathNode root = NullSafe.get(found[0], Pathway::getRoot);
                final PathNode target = root == null
                        ? null
                        : find(root, uuid);
                if (target == null) {
                    throw new EntityServiceException("The node is no longer in pathway '" + name + "'");
                }
                if (target.isRetired()) {
                    throw new EntityServiceException("The node '" + target.getName()
                                                     + "' has been removed or retired");
                }

                final String constraintName = constraint.getName();
                final Map<String, Constraint> constraints = new HashMap<>(NullSafe.map(target.getConstraints()));
                final Constraint held = constraints.get(constraintName);
                if (previous == null && held != null) {
                    throw new EntityServiceException("The node '" + target.getName()
                                                     + "' already has a constraint named '" + constraintName + "'");
                }
                if (previous != null && held == null) {
                    throw new EntityServiceException("The node '" + target.getName()
                                                     + "' no longer has a constraint named '" + constraintName + "'");
                }
                if (previous != null && !previous.equals(held)) {
                    throw new EntityServiceException("Constraint '" + constraintName + "' has changed since it "
                                                     + "was opened. It is now " + held.getValue()
                                                     + (held.isOptional() ? ", optional" : ", required"));
                }
                if (constraint.equals(held)) {
                    return false;
                }

                final NanoTime now = NanoTimeUtil.fromInstant(Instant.now());
                final List<PathwayMutation> mutations = new ArrayList<>();
                put(target, constraints, constraint, now, mutations);
                final PathNode edited = target.copy().constraints(constraints).lastChangedTime(now).build();
                final PathNode newRoot = replace(root, uuid, edited);
                save(writer, db, pathwayKey, found[0].copy().root(newRoot).build(), mutations);
                LOGGER.info(() -> LogUtil.message("Edited constraint {} of pathway {} in document {} at node {}",
                        constraintName, name, docUuid, uuid));
                return true;
            }
        }
    }

    /**
     * Sets every constraint of one node of a learnt pathway to the ones given, removing any the node
     * holds that are not given, and revives the node and every retired node above it, which keep their
     * constraints. Nodes under it are left as they are, and paths dropped when it was removed are not
     * put back.
     *
     * <p>Takes the same shard lock as {@link #removeNode}, after checking the submitted constraints, so
     * one with no name or an invalid value is refused without taking the lock.
     *
     * @return whether anything changed. False when the node and every node above it are live and the
     * node already holds the constraints given.
     * @throws EntityServiceException when the rollback is refused, with the reason as its message.
     */
    public Boolean rollbackNode(final RollbackNode rollbackNode) {
        final PathwaysDoc doc = editable(rollbackNode.getDocRef());
        final String name = rollbackNode.getName();
        final String uuid = rollbackNode.getNodeUuid();
        final SharedFileStoreSettings settings = doc.getSharedFileStore();
        if (settings == null || NullSafe.isBlankString(settings.getSharedPath())) {
            throw new EntityServiceException("'" + doc.getName() + "' names no shared file store");
        }
        if (NullSafe.isBlankString(name) || NullSafe.isBlankString(uuid)) {
            throw new EntityServiceException("No pathway or node was named");
        }
        check(NullSafe.map(rollbackNode.getConstraints()));

        final byte[] pathwayKey = name.getBytes(StandardCharsets.UTF_8);
        final AtomicBoolean changed = new AtomicBoolean();
        locked(doc, name, shard -> shardStore.withShard(doc, shard, localDir -> {
            changed.set(rollbackNodeInModel(localDir, doc.getUuid(), name, pathwayKey, uuid,
                    rollbackNode.getPrevious(), rollbackNode.isRetired(), rollbackNode.getConstraints()));
            return changed.get();
        }));
        return changed.get();
    }

    // Not private so that what it does to a shard can be tested without a cluster lock and a document
    // store standing behind it. The revival rows of the nodes above come first, root first, then the
    // node's own revival row and then its constraint rows, so a replay taking them off newest first puts
    // the constraints back before retiring the node and then the nodes above it again.
    boolean rollbackNodeInModel(final Path localDir,
                                final String docUuid,
                                final String name,
                                final byte[] pathwayKey,
                                final String uuid,
                                final Map<String, Constraint> previous,
                                final boolean retired,
                                final Map<String, Constraint> target) {
        final Map<String, Constraint> wanted = NullSafe.map(target);
        check(wanted);
        try (final PathwaysDb db = PathwaysDb.create(localDir, byteBuffers, false)) {
            try (final LmdbWriter writer = db.createWriter()) {
                final Pathway[] found = {null};
                withKey(pathwayKey, key -> found[0] = db.getPathways().get(writer.getWriteTxn(), key,
                        value -> value == null
                                ? null
                                : pathwaySerde.readPathway(value)));
                final PathNode root = NullSafe.get(found[0], Pathway::getRoot);
                final PathNode node = root == null
                        ? null
                        : find(root, uuid);
                if (node == null) {
                    throw new EntityServiceException("The node is no longer in pathway '" + name + "'");
                }
                final Map<String, Constraint> constraints = new HashMap<>(NullSafe.map(node.getConstraints()));
                if (!constraints.equals(NullSafe.map(previous)) || node.isRetired() != retired) {
                    throw new EntityServiceException("The node '" + node.getName() + "' has changed since it "
                                                     + "was drawn");
                }

                final NanoTime now = NanoTimeUtil.fromInstant(Instant.now());
                final List<PathwayMutation> mutations = new ArrayList<>();
                final PathNode.Builder builder = node.copy();
                if (node.isRetired()) {
                    mutations.add(revival(node, now));
                    builder.retiredTime(null);
                }
                NullSafe.map(node.getConstraints()).forEach((constraintName, held) -> {
                    if (!wanted.containsKey(constraintName)) {
                        constraints.remove(constraintName);
                        mutations.add(removal(node, held, now));
                    }
                });
                wanted.values().forEach(constraint -> put(node, constraints, constraint, now, mutations));

                final PathNode rolledBack = builder.constraints(constraints).lastChangedTime(now).build();
                final PathNode newRoot = revived(root, uuid, rolledBack, now, mutations);
                if (mutations.isEmpty()) {
                    return false;
                }
                save(writer, db, pathwayKey, found[0].copy().root(newRoot).build(), mutations);
                LOGGER.info(() -> LogUtil.message("Rolled back node {} of pathway {} in document {}",
                        uuid, name, docUuid));
                return true;
            }
        }
    }

    // Sets one constraint on a node's constraints, with a change row for its value and one for its
    // optional flag where each differs from the one held. A constraint already held keeps its use counts.
    private static void put(final PathNode node,
                            final Map<String, Constraint> constraints,
                            final Constraint constraint,
                            final NanoTime now,
                            final List<PathwayMutation> mutations) {
        final String constraintName = constraint.getName();
        final Constraint held = constraints.get(constraintName);
        final ConstraintValue was = NullSafe.get(held, Constraint::getValue);
        if (!constraint.getValue().equals(was)) {
            mutations.add(new PathwayMutation(0L, now, now, -1, null, null, node.getNodePath(),
                    node.getUuid(), constraintName, MutationType.CONSTRAINT_EDITED,
                    constraint.isOptional(), was, constraint.getValue()));
        }
        if (held != null && held.isOptional() != constraint.isOptional()) {
            mutations.add(new PathwayMutation(0L, now, now, -1, null, null, node.getNodePath(),
                    node.getUuid(), constraintName, MutationType.CONSTRAINT_OPTIONAL_EDITED,
                    constraint.isOptional(), constraint.getValue(), constraint.getValue()));
        }
        constraints.put(constraintName, held == null
                ? new Constraint(constraintName, constraint.getValue(), constraint.isOptional())
                : held.copy().value(constraint.getValue()).optional(constraint.isOptional()).build());
    }

    private static void check(final Constraint previous, final Constraint constraint) {
        if (constraint == null || NullSafe.isBlankString(constraint.getName())) {
            throw new EntityServiceException("A constraint must have a name");
        }
        if (previous != null && !constraint.getName().equals(previous.getName())) {
            throw new EntityServiceException("A constraint cannot be renamed");
        }
        final String problem = problem(constraint.getValue());
        if (problem != null) {
            throw new EntityServiceException("Constraint '" + constraint.getName() + "' " + problem);
        }
    }

    // Each constraint checked as an added one would be, and refused where it is filed under a name other
    // than its own.
    private static void check(final Map<String, Constraint> constraints) {
        constraints.forEach((constraintName, constraint) -> {
            check(null, constraint);
            if (!constraint.getName().equals(constraintName)) {
                throw new EntityServiceException("Constraint '" + constraint.getName() + "' is given under the "
                                                 + "name '" + constraintName + "'");
            }
        });
    }

    // What is wrong with a value, or null where it can be stored as it is.
    private static String problem(final ConstraintValue value) {
        if (value == null) {
            return "has no value";
        }
        if (value instanceof final AbstractValue<?> single) {
            if (single.getValue() == null) {
                return "has no value";
            }
            if (value instanceof final Regex regex) {
                try {
                    Pattern.compile(regex.getValue());
                } catch (final PatternSyntaxException e) {
                    return "is not a valid regular expression: " + e.getDescription();
                }
            }
        } else if (value instanceof final AbstractSet<?> set) {
            if (NullSafe.isEmptyCollection(set.getSet())) {
                return "has an empty set";
            }
            if (set.getSet().stream().anyMatch(Objects::isNull)) {
                return "has an empty value in its set";
            }
        } else if (value instanceof final NanoTimeRange range) {
            if (range.getMin() == null && range.getMax() == null) {
                return "has neither a minimum nor a maximum";
            }
            if (range.getMin() != null && range.getMax() != null && range.getMin().isGreaterThan(range.getMax())) {
                return "has a minimum greater than its maximum";
            }
        } else if (value instanceof final AbstractRange<?> range) {
            if (range.getMin() == null || range.getMax() == null) {
                return "needs both a minimum and a maximum";
            }
            if (greater(range.getMin(), range.getMax())) {
                return "has a minimum greater than its maximum";
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static boolean greater(final Object min, final Object max) {
        return ((Comparable<Object>) min).compareTo(max) > 0;
    }

    private static PathNode replace(final PathNode node, final String uuid, final PathNode with) {
        if (uuid.equals(node.getUuid())) {
            return with;
        }
        final List<PathNode> children = new ArrayList<>();
        NullSafe.list(node.getChildren()).forEach(child -> children.add(replace(child, uuid, with)));
        return node.copy().children(children).build();
    }

    // Revival rows go in front so a replay, newest first, restores the node before retiring those above.
    private static PathNode revived(final PathNode node,
                                    final String uuid,
                                    final PathNode with,
                                    final NanoTime now,
                                    final List<PathwayMutation> mutations) {
        if (uuid.equals(node.getUuid())) {
            return with;
        }
        final List<PathNode> children = new ArrayList<>(NullSafe.list(node.getChildren()));
        for (int i = 0; i < children.size(); i++) {
            final PathNode child = children.get(i);
            final PathNode built = revived(child, uuid, with, now, mutations);
            if (built != child) {
                children.set(i, built);
                final PathNode.Builder builder = node.copy().children(children);
                if (node.isRetired()) {
                    mutations.addFirst(revival(node, now));
                    builder.retiredTime(null).lastChangedTime(now);
                }
                return builder.build();
            }
        }
        return node;
    }

    private static PathwayMutation revival(final PathNode node, final NanoTime now) {
        return new PathwayMutation(0L, now, now, -1, null, null, node.getNodePath(), node.getUuid(), null,
                MutationType.NODE_REVIVED, false, null, null);
    }

    private static PathwayMutation removal(final PathNode node, final Constraint held, final NanoTime now) {
        return new PathwayMutation(0L, now, now, -1, null, null, node.getNodePath(), node.getUuid(),
                held.getName(), MutationType.CONSTRAINT_REMOVED, held.isOptional(), held.getValue(), null);
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

    // The tree with the named node, and everything under it, retired with its constraints kept. A node
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
            if (!node.isRetired()) {
                mutations.add(new PathwayMutation(0L, now, now, -1, null, null, node.getNodePath(),
                        node.getUuid(), null, MutationType.NODE_REMOVED, false, null, null));
                builder.retiredTime(now).lastChangedTime(now);
            }
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
