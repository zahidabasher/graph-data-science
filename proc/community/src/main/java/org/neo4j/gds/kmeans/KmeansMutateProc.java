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
package org.neo4j.gds.kmeans;

import org.neo4j.gds.BaseProc;
import org.neo4j.gds.executor.MemoryEstimationExecutor;
import org.neo4j.gds.executor.ProcedureExecutor;
import org.neo4j.gds.results.MemoryEstimateResult;
import org.neo4j.procedure.Description;
import org.neo4j.procedure.Internal;
import org.neo4j.procedure.Name;
import org.neo4j.procedure.Procedure;

import java.util.Map;
import java.util.stream.Stream;

import static org.neo4j.gds.kmeans.Kmeans.KMEANS_DESCRIPTION;
import static org.neo4j.procedure.Mode.READ;

public class KmeansMutateProc extends BaseProc {

    @Procedure(value = "gds.kmeans.mutate", mode = READ)
    @Description(KMEANS_DESCRIPTION)
    public Stream<MutateResult> mutate(
        @Name(value = "graphName") String graphName,
        @Name(value = "configuration", defaultValue = "{}") Map<String, Object> configuration
    ) {
        var mutateSpec = new KmeansMutateSpec();

        return new ProcedureExecutor<>(
            mutateSpec,
            executionContext()
        ).compute(graphName, configuration);
    }

    @Deprecated(forRemoval = true)
    @Internal
    @Procedure(value = "gds.beta.kmeans.mutate", mode = READ, deprecatedBy = "gds.kmeans.mutate")
    @Description(KMEANS_DESCRIPTION)
    public Stream<MutateResult> betaMutate(
        @Name(value = "graphName") String graphName,
        @Name(value = "configuration", defaultValue = "{}") Map<String, Object> configuration
    ) {
        executionContext().log()
            .warn("Procedure `gds.beta.kmeans.mutate` has been deprecated, please use `gds.kmeans.mutate`.");
        return mutate(graphName, configuration);
    }

    @Procedure(value = "gds.kmeans.mutate.estimate", mode = READ)
    @Description(ESTIMATE_DESCRIPTION)
    public Stream<MemoryEstimateResult> estimate(
        @Name(value = "graphNameOrConfiguration") Object graphName,
        @Name(value = "algoConfiguration") Map<String, Object> configuration
    ) {
        var spec = new KmeansMutateSpec();

        return new MemoryEstimationExecutor<>(
            spec,
            executionContext(),
            transactionContext()
        ).computeEstimate(graphName, configuration);
    }

    @Deprecated(forRemoval = true)
    @Internal
    @Procedure(value = "gds.beta.kmeans.mutate.estimate", mode = READ, deprecatedBy = "gds.kmeans.mutate.estimate")
    @Description(ESTIMATE_DESCRIPTION)
    public Stream<MemoryEstimateResult> betaEstimate(
        @Name(value = "graphNameOrConfiguration") Object graphName,
        @Name(value = "algoConfiguration") Map<String, Object> configuration
    ) {
        executionContext().log()
            .warn("Procedure `gds.beta.kmeans.mutate.estimate` has been deprecated, please use `gds.kmeans.mutate.estimate`.");
        return estimate(graphName, configuration);
    }
}
