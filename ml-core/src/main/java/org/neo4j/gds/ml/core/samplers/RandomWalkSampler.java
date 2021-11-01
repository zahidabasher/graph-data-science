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
package org.neo4j.gds.ml.core.samplers;

import org.apache.commons.lang3.mutable.MutableDouble;
import org.apache.commons.lang3.mutable.MutableLong;
import org.neo4j.gds.api.Graph;

import java.util.Random;

public class RandomWalkSampler {

    private static final long NO_MORE_NODES = -1;
    // The number of tries we will make to draw a random neighbour according to p and q
    private static final int MAX_TRIES = 100;

    private final Graph graph;
    private final int walkLength;
    private final Random random;
    private final MutableDouble currentWeight;
    private final MutableLong randomNeighbour;
    private final double normalizedReturnProbability;
    private final double normalizedSameDistanceProbability;
    private final double normalizedInOutProbability;
    private final CumulativeWeightSupplier cumulativeWeightSupplier;

    public RandomWalkSampler(
        CumulativeWeightSupplier cumulativeWeightSupplier,
        int walkLength,
        double normalizedReturnProbability,
        double normalizedSameDistanceProbability,
        double normalizedInOutProbability,
        Graph graph,
        Random random
    ) {
        this.random = random;
        this.cumulativeWeightSupplier = cumulativeWeightSupplier;
        this.graph = graph;
        this.walkLength = walkLength;
        this.normalizedReturnProbability = normalizedReturnProbability;
        this.normalizedSameDistanceProbability = normalizedSameDistanceProbability;
        this.normalizedInOutProbability = normalizedInOutProbability;

        this.currentWeight = new MutableDouble(0);
        this.randomNeighbour = new MutableLong(-1);
    }

    public long[] walk(long startNode) {
            var walk = new long[walkLength];
            walk[0] = startNode;
            walk[1] = randomNeighbour(startNode);

        for (int i = 2; i < walkLength; i++) {
            var nextNode = walkOneStep(walk[i - 2], walk[i - 1]);
            if (nextNode == NO_MORE_NODES) {
                var shortenedWalk = new long[i];
                System.arraycopy(walk, 0, shortenedWalk, 0, shortenedWalk.length);
                walk = shortenedWalk;
                break;
            } else {
                walk[i] = nextNode;
            }
        }
        return walk;
    }

    private long walkOneStep(long previousNode, long currentNode) {
        var currentNodeDegree = graph.degree(currentNode);

        if (currentNodeDegree == 0) {
            // We have arrived at a node with no outgoing neighbors, we can stop walking
            return NO_MORE_NODES;
        } else if (currentNodeDegree == 1) {
            // This node only has one neighbour, no need to test
            return randomNeighbour(currentNode);
        } else {
            var tries = 0;
            while (tries < MAX_TRIES) {
                var newNode = randomNeighbour(currentNode);
                var r = random.nextDouble();

                if (newNode == previousNode) {
                    if (r < normalizedReturnProbability) {
                        return newNode;
                    }
                } else if (isNeighbour(previousNode, newNode)) {
                    if (r < normalizedSameDistanceProbability) {
                        return newNode;
                    }
                } else if (r < normalizedInOutProbability) {
                    return newNode;
                }
                tries++;
            }

            // We did not find a valid neighbour in `MAX_TRIES` tries, so we just pick a random one.
            return randomNeighbour(currentNode);
        }
    }

    private long randomNeighbour(long node) {
        var cumulativeWeight = cumulativeWeightSupplier.forNode(node);
        var randomWeight = cumulativeWeight * random.nextDouble();

        currentWeight.setValue(0.0);
        randomNeighbour.setValue(NO_MORE_NODES);

        graph.forEachRelationship(node, 1.0D, (source, target, weight) -> {
            if (randomWeight <= currentWeight.addAndGet(weight)) {
                randomNeighbour.setValue(target);
                return false;
            }
            return true;
        });

        return randomNeighbour.getValue();
    }

    private boolean isNeighbour(long source, long target) {
        return graph.exists(source, target);
    }

    @FunctionalInterface
    public interface CumulativeWeightSupplier {
        double forNode(long nodeId);
    }
}