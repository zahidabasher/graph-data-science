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
package org.neo4j.gds.model.catalog;

import org.neo4j.gds.core.model.Model;
import org.neo4j.gds.core.model.ModelCatalog;
import org.neo4j.procedure.Description;
import org.neo4j.procedure.Internal;
import org.neo4j.procedure.Name;
import org.neo4j.procedure.Procedure;

import java.util.Optional;
import java.util.stream.Stream;

import static org.neo4j.procedure.Mode.READ;

public class ModelExistsProc extends ModelCatalogProc {

    private static final String DESCRIPTION = "Checks if a given model exists in the model catalog.";

    @Procedure(name = "gds.model.exists", mode = READ)
    @Description(DESCRIPTION)
    public Stream<ModelExistsResult> exists(@Name(value = "modelName") String modelName) {
        validateModelName(modelName);

        ModelCatalog modelCatalog = executionContext().modelCatalog();

        return Stream.of(new ModelExistsResult(
            modelName,
            Optional.ofNullable(modelCatalog.getUntyped(username(), modelName)).map(Model::algoType).orElse("n/a"),
            modelCatalog.exists(username(), modelName)
        ));
    }

    @Procedure(name = "gds.beta.model.exists", mode = READ, deprecatedBy = "gds.model.exists")
    @Description(DESCRIPTION)
    @Deprecated(forRemoval = true)
    @Internal
    public Stream<ModelExistsResult> betaExists(@Name(value = "modelName") String modelName) {

        executionContext()
            .log()
            .warn("Procedure `gds.beta.model.exists` has been deprecated, please use `gds.model.exists`.");

        return exists(modelName);
    }

}
