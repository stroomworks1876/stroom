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
import stroom.pathways.shared.UpdatePathway;
import stroom.planb.impl.dao.LmdbWriter;
import stroom.planb.impl.dao.ShardKeyRouter;
import stroom.planb.impl.dao.trace.PathwaysDb;
import stroom.planb.shared.SharedFileStoreSettings;
import stroom.util.logging.LambdaLogger;
import stroom.util.logging.LambdaLoggerFactory;
import stroom.util.logging.LogUtil;
import stroom.util.shared.NullSafe;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
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

    @Inject
    public PathwaysService(final ShardedPathwayReader shardedPathwayReader,
                           final PathwaysStore pathwaysStore,
                           final PathwaysShardStore shardStore,
                           final ClusterLockService clusterLockService,
                           final ByteBuffers byteBuffers,
                           final MutationLog mutationLog) {
        this.shardedPathwayReader = shardedPathwayReader;
        this.pathwaysStore = pathwaysStore;
        this.shardStore = shardStore;
        this.clusterLockService = clusterLockService;
        this.byteBuffers = byteBuffers;
        this.mutationLog = mutationLog;
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
