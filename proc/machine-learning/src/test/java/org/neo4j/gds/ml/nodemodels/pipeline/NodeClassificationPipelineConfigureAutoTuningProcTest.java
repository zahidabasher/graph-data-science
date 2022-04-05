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
package org.neo4j.gds.ml.nodemodels.pipeline;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.neo4j.gds.BaseProcTest;
import org.neo4j.gds.ml.pipeline.PipelineCatalog;
import org.neo4j.gds.ml.pipeline.nodePipeline.NodeClassificationSplitConfig;

import java.util.List;
import java.util.Map;

import static org.neo4j.gds.ml.nodemodels.pipeline.NodeClassificationPipelineCompanion.DEFAULT_PARAM_CONFIG;
import static org.neo4j.gds.ml.pipeline.AutoTuningConfig.MAX_TRIALS;

class NodeClassificationPipelineConfigureAutoTuningProcTest extends BaseProcTest {

    @BeforeEach
    void setUp() throws Exception {
        registerProcedures(NodeClassificationPipelineConfigureAutoTuningProc.class, NodeClassificationPipelineCreateProc.class);

        runQuery("CALL gds.beta.pipeline.nodeClassification.create('myPipeline')");
    }

    @AfterEach
    void tearDown() {
        PipelineCatalog.removeAll();
    }

    @Test
    void shouldApplyDefaultMaxTrials() {
        assertCypherResult(
            "CALL gds.beta.pipeline.nodeClassification.create('confetti')",
            List.of(Map.of(
                "name", "confetti",
                "splitConfig", NodeClassificationSplitConfig.DEFAULT_CONFIG.toMap(),
                "autoTuningConfig", Map.of("maxTrials", MAX_TRIALS),
                "nodePropertySteps", List.of(),
                "featureProperties", List.of(),
                "parameterSpace", DEFAULT_PARAM_CONFIG
            ))
        );
    }

    @Test
    void shouldOverrideSingleSplitField() {
        assertCypherResult(
            "CALL gds.alpha.pipeline.nodeClassification.configureAutoTuning('myPipeline', {maxTrials: 42})",
            List.of(Map.of(
                "name", "myPipeline",
                "splitConfig", NodeClassificationSplitConfig.DEFAULT_CONFIG.toMap(),
                "autoTuningConfig", Map.of("maxTrials", 42),
                "nodePropertySteps", List.of(),
                "featureProperties", List.of(),
                "parameterSpace", DEFAULT_PARAM_CONFIG
            ))
        );
    }

    @Test
    void shouldOnlyKeepLastOverride() {
        runQuery("CALL gds.alpha.pipeline.nodeClassification.configureAutoTuning('myPipeline', {maxTrials: 1337})");
        assertCypherResult(
            "CALL gds.alpha.pipeline.nodeClassification.configureAutoTuning('myPipeline', {maxTrials: 42})",
            List.of(Map.of(
                "name", "myPipeline",
                "splitConfig", NodeClassificationSplitConfig.DEFAULT_CONFIG.toMap(),
                "autoTuningConfig", Map.of("maxTrials", 42),
                "nodePropertySteps", List.of(),
                "featureProperties", List.of(),
                "parameterSpace", DEFAULT_PARAM_CONFIG
            ))
        );
    }

    @Test
    void failOnInvalidKeys() {
        assertError(
            "CALL gds.alpha.pipeline.nodeClassification.configureAutoTuning('myPipeline', {invalidKey: 42, maxTr1als: -0.51})",
            "Unexpected configuration keys: invalidKey, maxTr1als (Did you mean [maxTrials]?"
        );
    }
}
