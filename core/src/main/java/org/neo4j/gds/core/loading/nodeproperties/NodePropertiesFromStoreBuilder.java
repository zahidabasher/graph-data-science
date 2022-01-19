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
package org.neo4j.gds.core.loading.nodeproperties;

import org.neo4j.gds.api.DefaultValue;
import org.neo4j.gds.api.IdMapping;
import org.neo4j.gds.api.NodeProperties;
import org.neo4j.gds.collections.HugeSparseArrays;
import org.neo4j.gds.core.utils.mem.AllocationTracker;
import org.neo4j.gds.core.utils.mem.MemoryEstimation;
import org.neo4j.gds.core.utils.mem.MemoryEstimations;
import org.neo4j.values.storable.DoubleArray;
import org.neo4j.values.storable.FloatArray;
import org.neo4j.values.storable.FloatingPointValue;
import org.neo4j.values.storable.IntegralValue;
import org.neo4j.values.storable.LongArray;
import org.neo4j.values.storable.Value;
import org.neo4j.values.storable.Values;

import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;

import static org.neo4j.gds.utils.StringFormatting.formatWithLocale;
import static org.neo4j.values.storable.Values.NO_VALUE;

public final class NodePropertiesFromStoreBuilder {

    private static final MemoryEstimation MEMORY_ESTIMATION = MemoryEstimations
        .builder(NodePropertiesFromStoreBuilder.class)
        .rangePerGraphDimension(
            "property values",
            (dimensions, concurrency) -> HugeSparseArrays.estimateLong(
                dimensions.nodeCount(),
                dimensions.nodeCount()
            )
        )
        .build();

    public static MemoryEstimation memoryEstimation() {
        return MEMORY_ESTIMATION;
    }

    public static NodePropertiesFromStoreBuilder of(
        AllocationTracker allocationTracker,
        DefaultValue defaultValue,
        int concurrency
    ) {
        return new NodePropertiesFromStoreBuilder(defaultValue, allocationTracker, concurrency);
    }

    private final DefaultValue defaultValue;
    private final AllocationTracker allocationTracker;
    private final int concurrency;
    private final AtomicReference<InnerNodePropertiesBuilder> innerBuilder;
    private final LongAdder size;

    private NodePropertiesFromStoreBuilder(
        DefaultValue defaultValue,
        AllocationTracker allocationTracker,
        int concurrency
    ) {
        this.defaultValue = defaultValue;
        this.allocationTracker = allocationTracker;
        this.concurrency = concurrency;
        this.innerBuilder = new AtomicReference<>();
        this.size = new LongAdder();
    }

    public void set(long neoNodeId, Value value) {
        if (value != null && value != NO_VALUE) {
            if (innerBuilder.get() == null) {
                initializeWithType(value);
            }
            innerBuilder.get().setValue(neoNodeId, value);
            size.increment();
        }
    }

    public NodeProperties build(IdMapping nodeMapping) {
        if (innerBuilder.get() == null) {
            if (defaultValue.getObject() != null) {
                initializeWithType(Values.of(defaultValue.getObject()));
            } else {
                throw new IllegalStateException("Cannot infer type of property");
            }
        }

        return innerBuilder.get().build(this.size.sum(), nodeMapping);
    }

    // This is synchronized as we want to prevent the creation of multiple InnerNodePropertiesBuilders of which only once survives.
    private synchronized void initializeWithType(Value value) {
        if (innerBuilder.get() == null) {
            InnerNodePropertiesBuilder newBuilder;
            if (value instanceof IntegralValue) {
                newBuilder = LongNodePropertiesBuilder.of(defaultValue, allocationTracker, concurrency);
            } else if (value instanceof FloatingPointValue) {
                newBuilder = new DoubleNodePropertiesBuilder(defaultValue, allocationTracker, concurrency);
            } else if (value instanceof LongArray) {
                newBuilder = new LongArrayNodePropertiesBuilder(defaultValue, allocationTracker, concurrency);
            } else if (value instanceof DoubleArray) {
                newBuilder = new DoubleArrayNodePropertiesBuilder(defaultValue, allocationTracker, concurrency);
            } else if (value instanceof FloatArray) {
                newBuilder = new FloatArrayNodePropertiesBuilder(defaultValue, allocationTracker, concurrency);
            } else {
                throw new UnsupportedOperationException(formatWithLocale(
                    "Loading of values of type %s is currently not supported",
                    value.getTypeName()
                ));
            }

            innerBuilder.compareAndSet(null, newBuilder);
        }
    }

}
