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
package org.neo4j.gds.core.loading;

import org.immutables.builder.Builder;
import org.immutables.value.Value;
import org.jetbrains.annotations.Nullable;
import org.neo4j.gds.core.Aggregation;
import org.neo4j.gds.core.compress.AdjacencyCompressor;
import org.neo4j.gds.core.compress.AdjacencyCompressorFactory;
import org.neo4j.gds.core.compress.LongArrayBuffer;
import org.neo4j.gds.core.utils.mem.AllocationTracker;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Optional;
import java.util.concurrent.atomic.LongAdder;

import static org.neo4j.gds.core.loading.AdjacencyPreAggregation.preAggregate;
import static org.neo4j.gds.mem.MemoryUsage.sizeOfLongArray;
import static org.neo4j.gds.mem.MemoryUsage.sizeOfObjectArray;
import static org.neo4j.kernel.api.StatementConstants.NO_SUCH_PROPERTY_KEY;

/**
 * Wraps a paged representation of {@link org.neo4j.gds.core.loading.CompressedLongArray}s
 * which store the target ids for each node during import.
 *
 * An instance of this class exists exactly once per relationship type and has the following
 * responsibilities:
 *
 * <ul>
 *     <li>Receives raw relationship records from relationship batch buffers via {@link org.neo4j.gds.core.loading.SingleTypeRelationshipImporter}</li>
 *     <li>Compresses raw records into compressed long arrays</li>
 *     <li>Creates tasks that write compressed long arrays into the final adjacency list using a specific compressor</li>
 * </ul>
 */
@Value.Style(typeBuilder = "AdjacencyBufferBuilder")
public final class AdjacencyBuffer {

    private final AdjacencyCompressorFactory adjacencyCompressorFactory;
    private final ThreadLocalRelationshipsBuilder[] localBuilders;
    private final CompressedLongArray[][] compressedAdjacencyLists;
    private final int pageShift;
    private final long pageMask;
    private final LongAdder relationshipCounter;
    private final int[] propertyKeyIds;
    private final double[] defaultValues;
    private final Aggregation[] aggregations;
    private final boolean atLeastOnePropertyToLoad;
    private final boolean preAggregate;

    @Builder.Factory
    public static AdjacencyBuffer of(
        SingleTypeRelationshipImporter.ImportMetaData importMetaData,
        AdjacencyCompressorFactory adjacencyCompressorFactory,
        ImportSizing importSizing,
        AllocationTracker allocationTracker
    ) {
        var numPages = importSizing.numberOfPages();
        var pageSize = importSizing.pageSize();

        var sizeOfLongPage = sizeOfLongArray(pageSize);
        var sizeOfObjectPage = sizeOfObjectArray(pageSize);

        allocationTracker.add(sizeOfObjectArray(numPages) << 2);
        ThreadLocalRelationshipsBuilder[] localBuilders = new ThreadLocalRelationshipsBuilder[numPages];
        CompressedLongArray[][] compressedAdjacencyLists = new CompressedLongArray[numPages][];

        for (int page = 0; page < numPages; page++) {
            allocationTracker.add(sizeOfObjectPage);
            allocationTracker.add(sizeOfObjectPage);
            allocationTracker.add(sizeOfLongPage);
            compressedAdjacencyLists[page] = new CompressedLongArray[pageSize];
            localBuilders[page] = new ThreadLocalRelationshipsBuilder(adjacencyCompressorFactory);
        }

        boolean atLeastOnePropertyToLoad = Arrays
            .stream(importMetaData.propertyKeyIds())
            .anyMatch(keyId -> keyId != NO_SUCH_PROPERTY_KEY);

        return new AdjacencyBuffer(
            importMetaData,
            adjacencyCompressorFactory, localBuilders,
            compressedAdjacencyLists,
            pageSize,
            atLeastOnePropertyToLoad
        );
    }

    private AdjacencyBuffer(
        SingleTypeRelationshipImporter.ImportMetaData importMetaData,
        AdjacencyCompressorFactory adjacencyCompressorFactory,
        ThreadLocalRelationshipsBuilder[] localBuilders,
        CompressedLongArray[][] compressedAdjacencyLists,
        int pageSize,
        boolean atLeastOnePropertyToLoad
    ) {
        this.adjacencyCompressorFactory = adjacencyCompressorFactory;
        this.localBuilders = localBuilders;
        this.compressedAdjacencyLists = compressedAdjacencyLists;
        this.pageShift = Integer.numberOfTrailingZeros(pageSize);
        this.pageMask = pageSize - 1;
        this.relationshipCounter = adjacencyCompressorFactory.relationshipCounter();
        this.propertyKeyIds = importMetaData.propertyKeyIds();
        this.defaultValues = importMetaData.defaultValues();
        this.aggregations = importMetaData.aggregations();
        this.atLeastOnePropertyToLoad = atLeastOnePropertyToLoad;
        this.preAggregate = importMetaData.preAggregate();
    }

    /**
     * @param batch             two-tuple values sorted by source (source, target)
     * @param targets           slice of batch on second position; all targets in source-sorted order
     * @param propertyValues    index-synchronised with targets. the list for each index are the properties for that source-target combo. null if no props
     * @param offsets           offsets into targets; every offset position indicates a source node group
     * @param length            length of offsets array (how many source tuples to import)
     */
    void addAll(
        long[] batch,
        long[] targets,
        @Nullable long[][] propertyValues,
        int[] offsets,
        int length
    ) {
        int pageShift = this.pageShift;
        long pageMask = this.pageMask;

        ThreadLocalRelationshipsBuilder builder = null;
        int lastPageIndex = -1;
        int endOffset, startOffset = 0;
        try {
            for (int i = 0; i < length; ++i) {
                endOffset = offsets[i];

                // if there are no rels for this node, just go to next
                if (endOffset <= startOffset) {
                    continue;
                }

                long source = batch[startOffset << 1];
                int pageIndex = (int) (source >>> pageShift);

                if (pageIndex > lastPageIndex) {
                    // switch to the builder for this page
                    if (builder != null) {
                        builder.unlock();
                    }
                    builder = localBuilders[pageIndex];
                    builder.lock();
                    lastPageIndex = pageIndex;
                }

                int localId = (int) (source & pageMask);

                CompressedLongArray compressedTargets = this.compressedAdjacencyLists[pageIndex][localId];
                if (compressedTargets == null) {
                    compressedTargets = new CompressedLongArray(
                        propertyValues == null ? 0 : propertyValues.length
                    );
                    this.compressedAdjacencyLists[pageIndex][localId] = compressedTargets;
                }

                var targetsToImport = endOffset - startOffset;
                if (propertyValues == null) {
                    compressedTargets.add(targets, startOffset, endOffset, targetsToImport);
                } else {
                    if (preAggregate && aggregations[0] != Aggregation.NONE) {
                        targetsToImport = preAggregate(targets, propertyValues, startOffset, endOffset, aggregations);
                    }

                    compressedTargets.add(targets, propertyValues, startOffset, endOffset, targetsToImport);
                }

                startOffset = endOffset;
            }
        } finally {
            if (builder != null && builder.isLockedByCurrentThread()) {
                builder.unlock();
            }
        }
    }

    Collection<AdjacencyListBuilderTask> adjacencyListBuilderTasks(Optional<AdjacencyCompressor.ValueMapper> mapper) {
        adjacencyCompressorFactory.init();

        var tasks = new ArrayList<AdjacencyListBuilderTask>(localBuilders.length + 1);
        for (int page = 0; page < localBuilders.length; page++) {
            long baseNodeId = ((long) page) << pageShift;
            tasks.add(new AdjacencyListBuilderTask(
                baseNodeId,
                localBuilders[page],
                compressedAdjacencyLists[page],
                relationshipCounter,
                mapper.orElse(ZigZagLongDecoding.Identity.INSTANCE)
            ));
        }

        return tasks;
    }

    int[] getPropertyKeyIds() {
        return propertyKeyIds;
    }

    double[] getDefaultValues() {
        return defaultValues;
    }

    Aggregation[] getAggregations() {
        return aggregations;
    }

    boolean atLeastOnePropertyToLoad() {
        return atLeastOnePropertyToLoad;
    }

    /**
     * Responsible for writing a page of CompressedLongArrays into the adjacency list.
     */
    static final class AdjacencyListBuilderTask implements Runnable {

        private final long baseNodeId;
        private final ThreadLocalRelationshipsBuilder threadLocalRelationshipsBuilder;
        private final CompressedLongArray[] compressedLongArrays;
        // A long array that may or may not be used during the compression.
        private final LongArrayBuffer buffer;
        private final LongAdder relationshipCounter;
        private final AdjacencyCompressor.ValueMapper valueMapper;

        AdjacencyListBuilderTask(
            long baseNodeId,
            ThreadLocalRelationshipsBuilder threadLocalRelationshipsBuilder,
            CompressedLongArray[] compressedLongArrays,
            LongAdder relationshipCounter,
            AdjacencyCompressor.ValueMapper valueMapper
        ) {
            this.baseNodeId = baseNodeId;
            this.threadLocalRelationshipsBuilder = threadLocalRelationshipsBuilder;
            this.compressedLongArrays = compressedLongArrays;
            this.valueMapper = valueMapper;
            this.buffer = new LongArrayBuffer();
            this.relationshipCounter = relationshipCounter;
        }

        @Override
        public void run() {
            try (var compressor = threadLocalRelationshipsBuilder.intoCompressor()) {
                long importedRelationships = 0L;
                for (int localId = 0; localId < compressedLongArrays.length; ++localId) {
                    CompressedLongArray compressedAdjacencyList = compressedLongArrays[localId];
                    if (compressedAdjacencyList != null) {
                        var nodeId = valueMapper.map(baseNodeId + localId);
                        importedRelationships += compressor.applyVariableDeltaEncoding(
                            compressedAdjacencyList,
                            buffer,
                            nodeId,
                            valueMapper
                        );
                        compressedLongArrays[localId] = null;
                    }
                }
                relationshipCounter.add(importedRelationships);
            }
        }
    }
}