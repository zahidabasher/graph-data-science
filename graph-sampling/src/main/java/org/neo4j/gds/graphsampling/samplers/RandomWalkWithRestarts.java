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
package org.neo4j.gds.graphsampling.samplers;

import com.carrotsearch.hppc.LongDoubleHashMap;
import com.carrotsearch.hppc.LongDoubleMap;
import com.carrotsearch.hppc.cursors.LongDoubleCursor;
import org.neo4j.gds.api.Graph;
import org.neo4j.gds.core.concurrency.ParallelUtil;
import org.neo4j.gds.core.concurrency.RunWithConcurrency;
import org.neo4j.gds.core.utils.paged.HugeAtomicBitSet;
import org.neo4j.gds.graphsampling.config.RandomWalkWithRestartsConfig;

import java.util.SplittableRandom;

public class RandomWalkWithRestarts implements NodesSampler {
    private static final double QUALITY_MOMENTUM = 0.9;
    private static final double QUALITY_THRESHOLD_BASE = 0.05;
    private final RandomWalkWithRestartsConfig config;
    private final SplittableRandom rng;

    public RandomWalkWithRestarts(RandomWalkWithRestartsConfig config) {
        this.config = config;
        this.rng = new SplittableRandom(config.randomSeed().orElseGet(() -> new SplittableRandom().nextLong()));
    }

    @Override
    public HugeAtomicBitSet sampleNodes(Graph inputGraph) {
        long expectedNodes = Math.round(inputGraph.nodeCount() * config.samplingRatio());
        var walkQualitiesMap = initializeQualityMap(inputGraph);
        var seenNodes = HugeAtomicBitSet.create(inputGraph.nodeCount());
        var tasks = ParallelUtil.tasks(config.concurrency(), () ->
            {
                var threadRng = this.rng.split();
                return new Walker(
                    seenNodes,
                    expectedNodes,
                    QUALITY_THRESHOLD_BASE / (config.concurrency() * config.concurrency()),
                    new WalkQualities(new LongDoubleHashMap(walkQualitiesMap), threadRng),
                    threadRng,
                    inputGraph.concurrentCopy(),
                    config
                );
            }
        );
        RunWithConcurrency.builder()
            .concurrency(config.concurrency())
            .tasks(tasks)
            .run();

        return seenNodes;
    }

    private LongDoubleMap initializeQualityMap(Graph inputGraph) {
        var qualityMap = new LongDoubleHashMap();
        if (!config.startNodes().isEmpty()) {
            config.startNodes().forEach(nodeId -> {
                qualityMap.put(inputGraph.toMappedNodeId(nodeId), 1.0);
            });
        } else {
            qualityMap.put(rng.nextLong(inputGraph.nodeCount()), 1.0);
        }
        return qualityMap;
    }

    static class Walker implements Runnable {

        private final HugeAtomicBitSet seenNodes;
        private final long expectedNodes;
        private final double qualityThreshold;
        private final WalkQualities walkQualities;
        private final SplittableRandom rng;
        private final Graph inputGraph;
        private final RandomWalkWithRestartsConfig config;

        Walker(
            HugeAtomicBitSet seenNodes,
            long expectedNodes,
            double qualityThreshold,
            WalkQualities walkQualities,
            SplittableRandom rng,
            Graph inputGraph,
            RandomWalkWithRestartsConfig config
        ) {
            this.seenNodes = seenNodes;
            this.expectedNodes = expectedNodes;
            this.qualityThreshold = qualityThreshold;
            this.walkQualities = walkQualities;
            this.rng = rng;
            this.inputGraph = inputGraph;
            this.config = config;
        }

        @Override
        public void run() {
            long currentNode = walkQualities.nextStartNode();
            long currentStartNode = currentNode;
            int addedNodes = 0;
            int nodesConsidered = 1;

            while (seenNodes.cardinality() < expectedNodes) {
                if (!seenNodes.getAndSet(currentNode)) {
                    addedNodes++;
                }

                // walk a step
                int degree = inputGraph.degree(currentNode);
                if (degree == 0 || rng.nextDouble() < config.restartProbability()) {
                    // walk ended, so check if we need to add a new startNode
                    double walkQuality = ((double) addedNodes) / nodesConsidered;
                    walkQualities.updateNodeQuality(currentStartNode, walkQuality);

                    if (walkQualities.expectedQuality() < qualityThreshold) {
                        long newNode;
                        do {
                            newNode = rng.nextLong(inputGraph.nodeCount());
                        } while (!walkQualities.addNode(newNode));
                    }

                    currentStartNode = walkQualities.nextStartNode();
                    currentNode = currentStartNode;
                    addedNodes = 0;
                    nodesConsidered = 1;
                } else {
                    int targetOffset = rng.nextInt(degree);
                    currentNode = inputGraph.getNeighbor(currentNode, targetOffset);
                    nodesConsidered++;
                }
            }
        }
    }

    static class WalkQualities {
        private final LongDoubleMap qualities;
        private final SplittableRandom rng;
        private double sum;
        private double sumOfSquares;

        WalkQualities(LongDoubleMap qualities, SplittableRandom rng) {
            this.qualities = qualities;
            this.rng = rng;
            this.sum = qualities.size();
            this.sumOfSquares = qualities.size();
        }

        boolean addNode(long nodeId) {
            if (qualities.containsKey(nodeId)) {
                return false;
            }

            qualities.put(nodeId, 1.0);
            sum += 1.0;
            sumOfSquares += 1.0;

            return true;
        }

        void updateNodeQuality(long nodeId, double walkQuality) {
            double previousQuality = qualities.get(nodeId);
            double updatedQuality = QUALITY_MOMENTUM * previousQuality + (1 - QUALITY_MOMENTUM) * walkQuality;

            sum += updatedQuality - previousQuality;
            sumOfSquares += updatedQuality * updatedQuality - previousQuality * previousQuality;

            qualities.put(nodeId, updatedQuality);
        }

        double expectedQuality() {
            return sumOfSquares / sum;
        }

        long nextStartNode() {
            double sample = rng.nextDouble(sum);
            double traversedSum = 0.0;
            for (LongDoubleCursor cursor : qualities) {
                traversedSum += cursor.value;
                if (traversedSum >= sample) {
                    return cursor.key;
                }
            }
            throw new IllegalStateException("Something went wrong :(");
        }
    }
}