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
package org.neo4j.gds.similarity.euclidean;

import org.neo4j.gds.core.CypherMapWrapper;
import org.neo4j.gds.core.utils.mem.AllocationTracker;
import org.neo4j.gds.impl.similarity.EuclideanAlgorithm;
import org.neo4j.gds.impl.similarity.EuclideanConfig;
import org.neo4j.gds.impl.similarity.EuclideanConfigImpl;
import org.neo4j.gds.similarity.AlphaSimilarityProc;

public abstract class EuclideanProc<PROC_RESULT> extends AlphaSimilarityProc<EuclideanAlgorithm, EuclideanConfig, PROC_RESULT> {

    protected static final String DESCRIPTION = "Euclidean-similarity is an algorithm for finding similar nodes based on the euclidean distance.";

    @Override
    protected EuclideanConfig newConfig(String username, CypherMapWrapper userInput) {
        return new EuclideanConfigImpl(userInput);
    }

    @Override
    protected EuclideanAlgorithm newAlgo(EuclideanConfig config, AllocationTracker allocationTracker) {
        return new EuclideanAlgorithm(config, api);
    }

    @Override
    protected String taskName() {
        return "Euclidean-similarity";
    }
}