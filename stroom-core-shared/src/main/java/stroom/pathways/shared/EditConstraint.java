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

import java.util.Objects;

/**
 * Asks for one constraint of one node of a learnt pathway to be added to the model, or for its value
 * or optional flag to be changed.
 */
@JsonInclude(Include.NON_NULL)
public class EditConstraint {

    @JsonProperty
    private final DocRef docRef;
    @JsonProperty
    private final String name;
    @JsonProperty
    private final String nodeUuid;
    @JsonProperty
    private final Constraint previous;
    @JsonProperty
    private final Constraint constraint;

    @JsonCreator
    public EditConstraint(@JsonProperty("docRef") final DocRef docRef,
                          @JsonProperty("name") final String name,
                          @JsonProperty("nodeUuid") final String nodeUuid,
                          @JsonProperty("previous") final Constraint previous,
                          @JsonProperty("constraint") final Constraint constraint) {
        this.docRef = docRef;
        this.name = name;
        this.nodeUuid = nodeUuid;
        this.previous = previous;
        this.constraint = constraint;
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
     * The constraint as the editor first showed it, which the model must still hold for the edit to
     * be made. Null when adding a constraint.
     */
    public Constraint getPrevious() {
        return previous;
    }

    public Constraint getConstraint() {
        return constraint;
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final EditConstraint that = (EditConstraint) o;
        return Objects.equals(docRef, that.docRef) &&
               Objects.equals(name, that.name) &&
               Objects.equals(nodeUuid, that.nodeUuid) &&
               Objects.equals(previous, that.previous) &&
               Objects.equals(constraint, that.constraint);
    }

    @Override
    public int hashCode() {
        return Objects.hash(docRef, name, nodeUuid, previous, constraint);
    }

    @Override
    public String toString() {
        return "EditConstraint{" +
               "docRef=" + docRef +
               ", name=" + name +
               ", nodeUuid=" + nodeUuid +
               ", previous=" + previous +
               ", constraint=" + constraint +
               '}';
    }
}
