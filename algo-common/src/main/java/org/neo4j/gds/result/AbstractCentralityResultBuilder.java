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
package org.neo4j.gds.result;

import org.HdrHistogram.DoubleHistogram;
import org.jetbrains.annotations.NotNull;
import org.neo4j.gds.compat.MapUtil;
import org.neo4j.gds.core.concurrency.Pools;
import org.neo4j.gds.core.utils.ProgressTimer;
import org.neo4j.gds.core.utils.statistics.CentralityStatistics;
import org.neo4j.gds.scaling.ScalarScaler;
import org.neo4j.internal.kernel.api.procs.ProcedureCallContext;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.LongToDoubleFunction;

public abstract class AbstractCentralityResultBuilder<WRITE_RESULT> extends AbstractResultBuilder<WRITE_RESULT> {

    private static final String HISTOGRAM_ERROR_KEY = "Error";

    private final int concurrency;
    private final boolean buildHistogram;
    private final Map<String, Object> histogramError;

    private LongToDoubleFunction centralityFunction;
    private ScalarScaler.Variant scaler;

    protected long postProcessingMillis = -1L;
    protected Map<String, Object> centralityHistogram;

    protected AbstractCentralityResultBuilder(
        ProcedureCallContext callContext,
        int concurrency
    ) {
        this.buildHistogram = callContext
            .outputFields()
            .anyMatch(s -> s.equalsIgnoreCase("centralityDistribution"));
        this.concurrency = concurrency;
        this.histogramError = new HashMap<>();
    }

    protected abstract WRITE_RESULT buildResult();

    public AbstractCentralityResultBuilder<WRITE_RESULT> withCentralityFunction(LongToDoubleFunction centralityFunction) {
        this.centralityFunction = centralityFunction;
        return this;
    }

    public AbstractCentralityResultBuilder<WRITE_RESULT> withScalerVariant(ScalarScaler.Variant scaler) {
        this.scaler = scaler;
        return this;
    }

    @Override
    public WRITE_RESULT build() {
        var timer = ProgressTimer.start();
        var maybeCentralityHistogram = computeCentralityHistogram();
        this.centralityHistogram = centralityHistogramResult(maybeCentralityHistogram);

        timer.stop();
        this.postProcessingMillis = timer.getDuration();

        return buildResult();
    }

    @NotNull
    private Optional<DoubleHistogram> computeCentralityHistogram() {
        var logScaler = scaler == ScalarScaler.Variant.LOG;
        if (buildHistogram && centralityFunction != null) {
            if (logScaler) {
                logScalerHistogramError();
            } else {
                return Optional.of(CentralityStatistics.histogram(
                    nodeCount,
                    centralityFunction,
                    Pools.DEFAULT,
                    concurrency
                ));
            }
        }
        return Optional.empty();
    }

    private Map<String, Object> centralityHistogramResult(Optional<DoubleHistogram> maybeHistogram) {
        return maybeHistogram.map(histogram -> MapUtil.map(
            "min", histogram.getMinValue(),
            "mean", histogram.getMean(),
            "max", histogram.getMaxValue(),
            "p50", histogram.getValueAtPercentile(50),
            "p75", histogram.getValueAtPercentile(75),
            "p90", histogram.getValueAtPercentile(90),
            "p95", histogram.getValueAtPercentile(95),
            "p99", histogram.getValueAtPercentile(99),
            "p999", histogram.getValueAtPercentile(99.9)
        )).orElse(histogramError);
    }

    private void logScalerHistogramError() {
        this.histogramError.put(HISTOGRAM_ERROR_KEY, "Unable to create histogram when using scaler of type " + ScalarScaler.Variant.LOG);
    }

}
