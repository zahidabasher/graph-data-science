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
package org.neo4j.gds;

import org.junit.jupiter.api.Test;
import org.neo4j.gds.config.NodeConfig;
import org.neo4j.kernel.impl.core.NodeEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NodeConfigTest {

    @Test
    void shouldParseNumber() {
        assertThat(NodeConfig.parseNodeId(1337L, "sampleProperty")).isEqualTo(1337L);
    }

    @Test
    void shouldParseNode() {
        var sampleNode = new NodeEntity(null, 1337L);
        assertThat(NodeConfig.parseNodeId(sampleNode, "sampleProperty")).isEqualTo(1337L);

    }

    @Test
    void shouldNotParseAnythingElse() {
        assertThatThrownBy(() -> NodeConfig.parseNodeId(Boolean.TRUE, "sampleProperty"))
            .hasMessageContaining("Expected a node or a node id for `sampleProperty`. Got Boolean");
    }


}
