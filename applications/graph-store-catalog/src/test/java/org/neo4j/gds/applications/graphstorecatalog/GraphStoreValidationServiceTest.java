/*
 * Copyright (c) "Neo4j"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.gds.applications.graphstorecatalog;

import org.junit.jupiter.api.Test;
import org.neo4j.gds.NodeLabel;
import org.neo4j.gds.RelationshipType;
import org.neo4j.gds.api.GraphName;
import org.neo4j.gds.api.GraphStore;
import org.neo4j.gds.core.CypherMapWrapper;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GraphStoreValidationServiceTest {
    @Test
    void shouldEnsureNodePropertiesExist() {
        var service = new GraphStoreValidationService();

        var graphStore = mock(GraphStore.class);
        when(graphStore.hasNodeProperty("foo")).thenReturn(true);
        when(graphStore.hasNodeProperty("bar")).thenReturn(true);
        service.ensureNodePropertiesExist(graphStore, List.of("foo", "bar"));

        // yep it didn't blow up :shrug:
    }

    @Test
    void shouldCatchNodePropertiesThatDoNotExist() {
        var service = new GraphStoreValidationService();

        var graphStore = mock(GraphStore.class);
        when(graphStore.hasNodeProperty("foo")).thenReturn(true);
        when(graphStore.nodePropertyKeys()).thenReturn(Set.of("foo"));
        assertThatIllegalArgumentException().isThrownBy(() -> {
            service.ensureNodePropertiesExist(graphStore, List.of("foo", "bar"));
        }).withMessage("Could not find property key(s) ['bar']. Defined keys: ['foo'].");
    }

    @Test
    void shouldEnsureRelationshipsDeletable() {
        var service = new GraphStoreValidationService();

        var graphStore = mock(GraphStore.class);

        when(graphStore.relationshipTypes()).thenReturn(Set.of(RelationshipType.of("foo"), RelationshipType.of("bar")));
        when(graphStore.hasNodeProperty("bar")).thenReturn(true);
        service.ensureRelationshipsMayBeDeleted(graphStore, "foo", GraphName.parse("some graph"));

        // yep it didn't blow up :shrug:
    }

    @Test
    void shouldDisallowDeletingLastRelationships() {
        var service = new GraphStoreValidationService();

        var graphStore = mock(GraphStore.class);
        when(graphStore.relationshipTypes()).thenReturn(Set.of(RelationshipType.of("foo")));
        assertThatIllegalArgumentException().isThrownBy(() -> service.ensureRelationshipsMayBeDeleted(
            graphStore,
            "foo",
            GraphName.parse("some graph")
        )).withMessage(
            "Deleting the last relationship type ('foo') from a graph ('some graph') is not supported. " +
                "Use `gds.graph.drop()` to drop the entire graph instead."
        );
    }

    @Test
    void shouldDisallowDeletingUnknownRelationships() {
        var service = new GraphStoreValidationService();

        var graphStore = mock(GraphStore.class);
        when(graphStore.relationshipTypes()).thenReturn(Set.of(RelationshipType.of("bar"), RelationshipType.of("baz")));
        assertThatIllegalArgumentException().isThrownBy(() -> service.ensureRelationshipsMayBeDeleted(
            graphStore,
            "foo",
            GraphName.parse("some graph")
        )).withMessage(
            "No relationship type 'foo' found in graph 'some graph'."
        );
    }

    @Test
    void shouldEnsureGraphPropertyExists() {
        var service = new GraphStoreValidationService();

        var graphStore = mock(GraphStore.class);
        when(graphStore.hasGraphProperty("foo")).thenReturn(true);
        service.ensureGraphPropertyExists(graphStore, "foo");

        // yay didn't blow up
    }

    @Test
    void shouldFlagUpWhenGraphPropertyDoesNotExists() {
        var service = new GraphStoreValidationService();

        var graphStore = mock(GraphStore.class);
        when(graphStore.hasGraphProperty("foo")).thenReturn(false);
        when(graphStore.graphPropertyKeys()).thenReturn(Set.of("bar", "baz"));
        assertThatIllegalArgumentException().isThrownBy(() -> service.ensureGraphPropertyExists(
            graphStore,
            "foo"
        )).withMessage("" +
            "The specified graph property 'foo' does not exist. " +
            "The following properties exist in the graph ['bar', 'baz'].");
    }

    @Test
    void shouldSuggestAlternativesWhenGraphPropertyDoesNotExist() {
        var service = new GraphStoreValidationService();

        var graphStore = mock(GraphStore.class);
        when(graphStore.hasGraphProperty("bar")).thenReturn(false);
        when(graphStore.graphPropertyKeys()).thenReturn(Set.of("foo", "baz"));
        assertThatIllegalArgumentException().isThrownBy(() -> service.ensureGraphPropertyExists(
            graphStore,
            "bar"
        )).withMessage("" +
            "The specified graph property 'bar' does not exist. " +
            "Did you mean: ['baz'].");
    }

    @Test
    void shouldValidateNodeProjectionHasSpecifiedPropertiesWhenNotSpecifyingLabel() {
        var service = new GraphStoreValidationService();

        var configuration = GraphStreamNodePropertiesConfig.of(
            "some graph",
            List.of("p1", "p2", "p3"),
            "*",
            CypherMapWrapper.empty()
        );
        var graphStore = mock(GraphStore.class);
        when(graphStore.nodeLabels()).thenReturn(Set.of(NodeLabel.of("l1"), NodeLabel.of("l2"), NodeLabel.of("l3")));
        when(graphStore.nodePropertyKeys(List.of(NodeLabel.of("l1")))).thenReturn(List.of("p1", "p2"));
        when(graphStore.nodePropertyKeys(List.of(NodeLabel.of("l2")))).thenReturn(List.of(
            "p1",
            "p2",
            "p3"
        )); // exact match
        when(graphStore.nodePropertyKeys(List.of(NodeLabel.of("l3")))).thenReturn(List.of("p2", "p3"));
        service.ensureNodePropertiesMatchNodeLabels(graphStore, configuration);
    }

    @Test
    void shouldValidateNodeProjectionHasAtLeastSpecifiedPropertiesWhenNotSpecifyingLabel() {
        var service = new GraphStoreValidationService();

        var configuration = GraphStreamNodePropertiesConfig.of(
            "some graph",
            List.of("p1", "p2", "p3"),
            "*",
            CypherMapWrapper.empty()
        );
        var graphStore = mock(GraphStore.class);
        when(graphStore.nodeLabels()).thenReturn(Set.of(NodeLabel.of("l1"), NodeLabel.of("l2"), NodeLabel.of("l3")));
        when(graphStore.nodePropertyKeys(List.of(NodeLabel.of("l1")))).thenReturn(List.of("p1", "p2"));
        when(graphStore.nodePropertyKeys(List.of(NodeLabel.of("l2")))).thenReturn(List.of(
            "p1",
            "p2",
            "p3",
            "p4"
        )); // subset match
        when(graphStore.nodePropertyKeys(List.of(NodeLabel.of("l3")))).thenReturn(List.of("p2", "p3"));
        service.ensureNodePropertiesMatchNodeLabels(graphStore, configuration);
    }

    @Test
    void shouldRejectWhenNodeProjectionDoesNotHaveSpecifiedPropertiesWhenNotSpecifyingLabel() {
        var service = new GraphStoreValidationService();

        var configuration = GraphStreamNodePropertiesConfig.of(
            "some graph",
            List.of("p1", "p2", "p3"),
            "*",
            CypherMapWrapper.empty()
        );
        var graphStore = mock(GraphStore.class);
        when(graphStore.nodeLabels()).thenReturn(Set.of(NodeLabel.of("l1"), NodeLabel.of("l2"), NodeLabel.of("l3")));
        when(graphStore.nodePropertyKeys(List.of(NodeLabel.of("l1")))).thenReturn(List.of("p1", "p2"));
        when(graphStore.nodePropertyKeys(List.of(NodeLabel.of("l2")))).thenReturn(List.of("p1", "p3"));
        when(graphStore.nodePropertyKeys(List.of(NodeLabel.of("l3")))).thenReturn(List.of("p2", "p3"));

        assertThatIllegalArgumentException().isThrownBy(() -> {
            service.ensureNodePropertiesMatchNodeLabels(graphStore, configuration);
        }).withMessage("Expecting at least one node projection to contain property key(s) ['p1', 'p2', 'p3'].");
    }

    @Test
    void shouldValidateNodeProjectionHasSpecifiedPropertiesWhenSpecifyingLabel() {
        var service = new GraphStoreValidationService();

        var configuration = GraphStreamNodePropertiesConfig.of(
            "some graph",
            List.of("p1", "p2", "p3"),
            "A",
            CypherMapWrapper.empty()
        );
        GraphStore graphStore = mock(GraphStore.class);
        when(graphStore.hasNodeProperty(NodeLabel.of("A"), "p1")).thenReturn(true);
        when(graphStore.hasNodeProperty(NodeLabel.of("A"), "p2")).thenReturn(true);
        when(graphStore.hasNodeProperty(NodeLabel.of("A"), "p3")).thenReturn(true);
        service.ensureNodePropertiesMatchNodeLabels(graphStore, configuration);
    }

    @Test
    void shouldRejectNodeProjectionMissingPropertyWhenSpecifyingLabel() {
        var service = new GraphStoreValidationService();

        var configuration = GraphStreamNodePropertiesConfig.of(
            "some graph",
            List.of("p1", "p2", "p3"),
            "A",
            CypherMapWrapper.empty()
        );
        GraphStore graphStore = mock(GraphStore.class);
        when(graphStore.hasNodeProperty(NodeLabel.of("A"), "p1")).thenReturn(true);
        when(graphStore.hasNodeProperty(NodeLabel.of("A"), "p2")).thenReturn(false);
        when(graphStore.hasNodeProperty(NodeLabel.of("A"), "p3")).thenReturn(true);
        when(graphStore.nodePropertyKeys(NodeLabel.of("A"))).thenReturn(Set.of("p1", "p3"));

        assertThatIllegalArgumentException().isThrownBy(() -> {
            service.ensureNodePropertiesMatchNodeLabels(graphStore, configuration);
        }).withMessage(
            "Expecting all specified node projections to have all given properties defined. Could not find property key(s) ['p2'] for label A. Defined keys: ['p1', 'p3'].");
    }
}
