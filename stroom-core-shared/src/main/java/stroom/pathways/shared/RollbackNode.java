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

package stroom.pathways.shared;

import stroom.docref.DocRef;
import stroom.pathways.shared.pathway.Constraint;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;
import java.util.Objects;

/**
 * Asks for every constraint of one node of a learnt pathway to be set to the ones given, restoring the
 * node where it is retired.
 */
@JsonInclude(Include.NON_NULL)
public class RollbackNode {

    @JsonProperty
    private final DocRef docRef;
    @JsonProperty
    private final String name;
    @JsonProperty
    private final String nodeUuid;
    @JsonProperty
    private final Map<String, Constraint> previous;
    @JsonProperty
    private final boolean retired;
    @JsonProperty
    private final Map<String, Constraint> constraints;

    @JsonCreator
    public RollbackNode(@JsonProperty("docRef") final DocRef docRef,
                        @JsonProperty("name") final String name,
                        @JsonProperty("nodeUuid") final String nodeUuid,
                        @JsonProperty("previous") final Map<String, Constraint> previous,
                        @JsonProperty("retired") final boolean retired,
                        @JsonProperty("constraints") final Map<String, Constraint> constraints) {
        this.docRef = docRef;
        this.name = name;
        this.nodeUuid = nodeUuid;
        this.previous = previous;
        this.retired = retired;
        this.constraints = constraints;
    }

    public DocRef getDocRef() {
        return docRef;
    }

    /**
     * The pathway the node belongs to.
     */
    public String getName() {
        return name;
    }

    public String getNodeUuid() {
        return nodeUuid;
    }

    /**
     * The node's constraints as the client last saw them, which the model must still hold for the
     * rollback to be made.
     */
    public Map<String, Constraint> getPrevious() {
        return previous;
    }

    /**
     * Whether the client last saw the node retired, which the model must still agree with for the
     * rollback to be made.
     */
    public boolean isRetired() {
        return retired;
    }

    /**
     * The constraints the node is to hold.
     */
    public Map<String, Constraint> getConstraints() {
        return constraints;
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final RollbackNode that = (RollbackNode) o;
        return retired == that.retired &&
               Objects.equals(docRef, that.docRef) &&
               Objects.equals(name, that.name) &&
               Objects.equals(nodeUuid, that.nodeUuid) &&
               Objects.equals(previous, that.previous) &&
               Objects.equals(constraints, that.constraints);
    }

    @Override
    public int hashCode() {
        return Objects.hash(docRef, name, nodeUuid, previous, retired, constraints);
    }

    @Override
    public String toString() {
        return "RollbackNode{" +
               "docRef=" + docRef +
               ", name=" + name +
               ", nodeUuid=" + nodeUuid +
               ", previous=" + previous +
               ", retired=" + retired +
               ", constraints=" + constraints +
               '}';
    }
}
