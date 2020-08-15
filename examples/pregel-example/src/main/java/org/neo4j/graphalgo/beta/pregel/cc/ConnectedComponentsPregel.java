/*
 * Copyright (c) 2017-2020 "Neo4j,"
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
package org.neo4j.graphalgo.beta.pregel.cc;

import org.neo4j.graphalgo.api.nodeproperties.ValueType;
import org.neo4j.graphalgo.beta.pregel.NodeSchemaBuilder;
import org.neo4j.graphalgo.beta.pregel.Pregel;
import org.neo4j.graphalgo.beta.pregel.PregelComputation;
import org.neo4j.graphalgo.beta.pregel.PregelContext;
import org.neo4j.graphalgo.beta.pregel.annotation.PregelProcedure;

import java.util.Queue;

import static org.neo4j.graphalgo.beta.pregel.annotation.GDSMode.MUTATE;
import static org.neo4j.graphalgo.beta.pregel.annotation.GDSMode.STATS;
import static org.neo4j.graphalgo.beta.pregel.annotation.GDSMode.STREAM;
import static org.neo4j.graphalgo.beta.pregel.annotation.GDSMode.WRITE;

@PregelProcedure(
    name = "example.pregel.cc",
    description = "Connected Components",
    modes = {STREAM, WRITE, MUTATE, STATS}
)
public class ConnectedComponentsPregel implements PregelComputation<ConnectedComponentsConfig> {

    public static final String COMPONENT = "component";

    @Override
    public Pregel.NodeSchema nodeSchema() {
        return new NodeSchemaBuilder()
            .putElement(COMPONENT, ValueType.LONG)
            .build();
    }

    @Override
    public void init(PregelContext.InitContext<ConnectedComponentsConfig> context, long nodeId) {
        var initialValue = context.getConfig().seedProperty() != null
            ? context.nodeProperties(context.getConfig().seedProperty()).longValue(nodeId)
            : nodeId;
        context.setNodeValue(COMPONENT, nodeId, initialValue);
    }

    @Override
    public void compute(PregelContext.ComputeContext<ConnectedComponentsConfig> context, long nodeId, Pregel.Messages messages) {
        long oldComponentId = context.longNodeValue(COMPONENT, nodeId);
        long newComponentId = oldComponentId;

        for (var nextComponentId : messages) {
            if (nextComponentId.longValue() < newComponentId) {
                newComponentId = nextComponentId.longValue();
            }
        }

        if (context.isInitialSuperstep() || newComponentId != oldComponentId) {
            context.setNodeValue(COMPONENT, nodeId, newComponentId);
            context.sendMessages(nodeId, newComponentId);
        }
    }
}
