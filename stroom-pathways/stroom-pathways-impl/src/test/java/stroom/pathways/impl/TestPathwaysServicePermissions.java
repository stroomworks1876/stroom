/*
 * Copyright 2016-2026 Crown Copyright
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

import stroom.cluster.lock.api.ClusterLockService;
import stroom.docref.DocRef;
import stroom.pathways.shared.DeletePathway;
import stroom.pathways.shared.EditConstraint;
import stroom.pathways.shared.PathwaysDoc;
import stroom.pathways.shared.RemoveConstraints;
import stroom.pathways.shared.RemoveNode;
import stroom.pathways.shared.pathway.Constraint;
import stroom.pathways.shared.pathway.Regex;
import stroom.pathways.shared.pathway.StringValue;
import stroom.planb.shared.SharedFileStoreSettings;
import stroom.security.api.SecurityContext;
import stroom.security.shared.DocumentPermission;
import stroom.util.shared.EntityServiceException;
import stroom.util.shared.PermissionException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TestPathwaysServicePermissions {

    private static final String DOC_UUID = "pathways-doc-uuid";
    private static final String PATHWAY = "my-pathway";

    @Mock
    private PathwaysStore pathwaysStore;
    @Mock
    private PathwaysShardStore shardStore;
    @Mock
    private ClusterLockService clusterLockService;
    @Mock
    private SecurityContext securityContext;

    private PathwaysService service;
    private PathwaysDoc doc;
    private DocRef docRef;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        // Shared path and shard count are set so that, without the permission check, the call would
        // go on to take the cluster lock.
        doc = PathwaysDoc.builder()
                .uuid(DOC_UUID)
                .name("My Pathways")
                .sharedFileStore(new SharedFileStoreSettings(2, "/shared/path"))
                .build();
        docRef = doc.asDocRef();
        when(pathwaysStore.readDocument(any())).thenReturn(doc);
        when(securityContext.hasDocumentPermission(any(), any())).thenReturn(false);
        service = new PathwaysService(
                null,
                pathwaysStore,
                shardStore,
                clusterLockService,
                null,
                null,
                null,
                securityContext);
    }

    @Test
    void removeNodeRefusedWithoutEdit() {
        assertThatThrownBy(() -> service.removeNode(new RemoveNode(docRef, PATHWAY, "node-uuid")))
                .isInstanceOf(PermissionException.class);

        verify(securityContext).hasDocumentPermission(docRef, DocumentPermission.EDIT);
        verifyNoInteractions(clusterLockService, shardStore);
    }

    @Test
    void removeConstraintsRefusedWithoutEdit() {
        assertThatThrownBy(() -> service.removeConstraints(
                new RemoveConstraints(docRef, PATHWAY, "node-uuid", List.of("duration"))))
                .isInstanceOf(PermissionException.class);

        verify(securityContext).hasDocumentPermission(docRef, DocumentPermission.EDIT);
        verifyNoInteractions(clusterLockService, shardStore);
    }

    @Test
    void editConstraintRefusedWithoutEdit() {
        assertThatThrownBy(() -> service.editConstraint(new EditConstraint(docRef, PATHWAY, "node-uuid", null,
                new Constraint("added", new StringValue("x"), false))))
                .isInstanceOf(PermissionException.class);

        verify(securityContext).hasDocumentPermission(docRef, DocumentPermission.EDIT);
        verifyNoInteractions(clusterLockService, shardStore);
    }

    @Test
    void deletePathwayRefusedWithoutEdit() {
        assertThatThrownBy(() -> service.deletePathway(new DeletePathway(docRef, PATHWAY)))
                .isInstanceOf(PermissionException.class);

        verify(securityContext).hasDocumentPermission(docRef, DocumentPermission.EDIT);
        verifyNoInteractions(clusterLockService, shardStore);
    }

    @Test
    void removeNodeAllowedWithEdit() {
        grantEditOnly();

        assertThatCode(() -> service.removeNode(new RemoveNode(docRef, PATHWAY, "node-uuid")))
                .doesNotThrowAnyException();

        verify(clusterLockService).lock(anyString(), any());
    }

    @Test
    void removeConstraintsAllowedWithEdit() {
        grantEditOnly();

        assertThatCode(() -> service.removeConstraints(
                new RemoveConstraints(docRef, PATHWAY, "node-uuid", List.of("duration"))))
                .doesNotThrowAnyException();

        verify(clusterLockService).lock(anyString(), any());
    }

    @Test
    void editConstraintAllowedWithEdit() {
        grantEditOnly();

        assertThatCode(() -> service.editConstraint(new EditConstraint(docRef, PATHWAY, "node-uuid", null,
                new Constraint("added", new StringValue("x"), false))))
                .doesNotThrowAnyException();

        verify(clusterLockService).lock(anyString(), any());
    }

    @Test
    void editConstraintWithInvalidValueRefusedBeforeLock() {
        grantEditOnly();

        assertThatThrownBy(() -> service.editConstraint(new EditConstraint(docRef, PATHWAY, "node-uuid", null,
                new Constraint("added", new Regex("[unclosed"), false))))
                .isInstanceOf(EntityServiceException.class)
                .hasMessageStartingWith("Constraint 'added' is not a valid regular expression");

        verifyNoInteractions(clusterLockService, shardStore);
    }

    @Test
    void deletePathwayAllowedWithEdit() {
        grantEditOnly();

        assertThatCode(() -> service.deletePathway(new DeletePathway(docRef, PATHWAY)))
                .doesNotThrowAnyException();

        verify(clusterLockService).lock(anyString(), any());
    }

    private void grantEditOnly() {
        // Stubbed after the blanket refusal in setUp, so this specific stub takes precedence.
        when(securityContext.hasDocumentPermission(docRef, DocumentPermission.EDIT)).thenReturn(true);
    }
}
