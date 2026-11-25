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

import stroom.docref.DocRef;
import stroom.event.logging.api.DocumentEventLog;
import stroom.event.logging.rs.api.AutoLogged;
import stroom.event.logging.rs.api.AutoLogged.OperationType;
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
import stroom.pathways.shared.PathwaysResource;
import stroom.pathways.shared.RemoveConstraints;
import stroom.pathways.shared.RemoveNode;
import stroom.util.shared.EntityServiceException;

import jakarta.inject.Inject;
import jakarta.inject.Provider;


@AutoLogged
class PathwaysResourceImpl implements PathwaysResource {

    private static final String REMOVE_NODE_EVENT = "RemovePathwayNode";
    private static final String REMOVE_NODE_DESCRIPTION =
            "Removing a node and everything under it from learnt pathway";
    private static final String REMOVE_CONSTRAINTS_EVENT = "RemovePathwayConstraints";
    private static final String REMOVE_CONSTRAINTS_DESCRIPTION =
            "Removing constraints from a node of learnt pathway";
    private static final String EDIT_CONSTRAINT_EVENT = "EditPathwayConstraint";
    private static final String EDIT_CONSTRAINT_DESCRIPTION =
            "Adding or changing a constraint of a node of learnt pathway";
    private static final String DELETE_PATHWAY_EVENT = "DeletePathway";
    private static final String DELETE_PATHWAY_DESCRIPTION = "Deleting learnt pathway and all its history";

    private final Provider<PathwaysStore> pathwaysStoreProvider;
    private final Provider<PathwaysService> pathwaysServiceProvider;
    private final Provider<DocumentEventLog> documentEventLogProvider;

    @Inject
    PathwaysResourceImpl(final Provider<PathwaysStore> pathwaysStoreProvider,
                         final Provider<PathwaysService> pathwaysServiceProvider,
                         final Provider<DocumentEventLog> documentEventLogProvider) {
        this.pathwaysStoreProvider = pathwaysStoreProvider;
        this.pathwaysServiceProvider = pathwaysServiceProvider;
        this.documentEventLogProvider = documentEventLogProvider;
    }

    @Override
    public PathwaysDoc fetch(final String uuid) {
        final PathwaysDoc doc = pathwaysStoreProvider.get().readDocument(getDocRef(uuid));
        if (doc == null) {
            return null;
        }
        // Stamp what the document does not hold itself: the flag that locks the shared path and shard
        // count once pathways have been written under them. Worked out once, when the document is
        // opened, and carried wherever it goes.
        return doc.copy()
                .hasSharedFileStoreData(pathwaysStoreProvider.get().hasSharedFileStoreData(uuid))
                .build();
    }

    @Override
    public PathwaysDoc update(final String uuid, final PathwaysDoc doc) {
        if (doc.getUuid() == null || !doc.getUuid().equals(uuid)) {
            throw new EntityServiceException("The document UUID must match the update UUID");
        }
        // Strip what was stamped on before persisting — it is worked out server-side on every read
        // and must never be written to the document store.
        final PathwaysDoc updated = pathwaysStoreProvider.get().writeDocument(doc.copy().build());
        if (updated == null) {
            return null;
        }
        return updated.copy()
                .hasSharedFileStoreData(pathwaysStoreProvider.get().hasSharedFileStoreData(uuid))
                .build();
    }

    private DocRef getDocRef(final String uuid) {
        return DocRef.builder()
                .uuid(uuid)
                .type(PathwaysDoc.TYPE)
                .build();
    }

    @AutoLogged(OperationType.UNLOGGED)
    @Override
    public PathwayResultPage findPathways(final FindPathwayCriteria criteria) {
        return pathwaysServiceProvider.get().findPathways(criteria);
    }

    @Override
    @AutoLogged(OperationType.UNLOGGED)
    public PathwayMutationResultPage findMutations(final FindPathwayMutationCriteria criteria) {
        return pathwaysServiceProvider.get().findMutations(criteria);
    }

    @AutoLogged(OperationType.UNLOGGED)
    @Override
    public PathwayAsAt fetchPathway(final FetchPathwayRequest request) {
        return pathwaysServiceProvider.get().fetchPathway(request);
    }

    @AutoLogged(OperationType.UNLOGGED)
    @Override
    public Boolean addPathway(final AddPathway addPathway) {
        return pathwaysServiceProvider.get().addPathway(addPathway);
    }

    @AutoLogged(OperationType.MANUALLY_LOGGED)
    @Override
    public Boolean deletePathway(final DeletePathway deletePathway) {
        try {
            final Boolean deleted = pathwaysServiceProvider.get().deletePathway(deletePathway);
            documentEventLogProvider.get()
                    .delete(deletePathway, DELETE_PATHWAY_EVENT, DELETE_PATHWAY_DESCRIPTION, null);
            return deleted;
        } catch (final RuntimeException e) {
            documentEventLogProvider.get()
                    .delete(deletePathway, DELETE_PATHWAY_EVENT, DELETE_PATHWAY_DESCRIPTION, e);
            throw e;
        }
    }

    @AutoLogged(OperationType.MANUALLY_LOGGED)
    @Override
    public Boolean removeNode(final RemoveNode removeNode) {
        try {
            final Boolean removed = pathwaysServiceProvider.get().removeNode(removeNode);
            documentEventLogProvider.get()
                    .delete(removeNode, REMOVE_NODE_EVENT, REMOVE_NODE_DESCRIPTION, null);
            return removed;
        } catch (final RuntimeException e) {
            documentEventLogProvider.get()
                    .delete(removeNode, REMOVE_NODE_EVENT, REMOVE_NODE_DESCRIPTION, e);
            throw e;
        }
    }

    @AutoLogged(OperationType.MANUALLY_LOGGED)
    @Override
    public Boolean removeConstraints(final RemoveConstraints removeConstraints) {
        try {
            final Boolean removed = pathwaysServiceProvider.get().removeConstraints(removeConstraints);
            documentEventLogProvider.get()
                    .delete(removeConstraints, REMOVE_CONSTRAINTS_EVENT, REMOVE_CONSTRAINTS_DESCRIPTION, null);
            return removed;
        } catch (final RuntimeException e) {
            documentEventLogProvider.get()
                    .delete(removeConstraints, REMOVE_CONSTRAINTS_EVENT, REMOVE_CONSTRAINTS_DESCRIPTION, e);
            throw e;
        }
    }

    @AutoLogged(OperationType.MANUALLY_LOGGED)
    @Override
    public Boolean editConstraint(final EditConstraint editConstraint) {
        try {
            final Boolean changed = pathwaysServiceProvider.get().editConstraint(editConstraint);
            documentEventLogProvider.get().update(editConstraint.getPrevious(), editConstraint,
                    EDIT_CONSTRAINT_EVENT, EDIT_CONSTRAINT_DESCRIPTION, null);
            return changed;
        } catch (final RuntimeException e) {
            documentEventLogProvider.get().update(editConstraint.getPrevious(), editConstraint,
                    EDIT_CONSTRAINT_EVENT, EDIT_CONSTRAINT_DESCRIPTION, e);
            throw e;
        }
    }
}
