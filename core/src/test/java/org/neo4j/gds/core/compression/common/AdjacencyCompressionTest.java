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
package org.neo4j.gds.core.compression.common;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.neo4j.gds.api.compress.LongArrayBuffer;
import org.neo4j.gds.core.Aggregation;

import java.util.Arrays;
import java.util.stream.Stream;

import static java.lang.Double.doubleToLongBits;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class AdjacencyCompressionTest {

    @ParameterizedTest(name = "{4}")
    @MethodSource("aggregationsWithResults")
    void shouldCountRelationships(
        long[] targetNodeIds,
        long[][] unsortedProperties,
        Aggregation[] aggregations,
        double[][] expected,
        String aggregationType
    ) {
        var data = new LongArrayBuffer(targetNodeIds, targetNodeIds.length);

        // Calculate this before applying the delta because the target node ids array is updated in place
        long expectedDataLength = Arrays.stream(targetNodeIds).distinct().count();

        long[][] sortedProperties = new long[unsortedProperties.length][targetNodeIds.length];

        AdjacencyCompression.applyDeltaEncoding(
            data,
            new long[][]{
                unsortedProperties[0], unsortedProperties[1]
            },
            sortedProperties,
            aggregations,
            false
        );

        for (int i = 0; i < expected.length; i++) {
            for (int j = 0; j < expected[i].length; j++) {
                assertEquals(expected[i][j], Double.longBitsToDouble(sortedProperties[i][j]));
            }
        }

        // The length of the data should be the count of the distinct elements in the target node ids array
        assertEquals(expectedDataLength, data.length);

        // The target data.longs should be the same instance as the one it was created
        assertSame(targetNodeIds, data.buffer);

        // These contain the `deltas` computed during the compression
        assertEquals(1L, data.buffer[0]);
        assertEquals(4L, data.buffer[1]);
    }

    static Stream<Arguments> aggregationsWithResults() {
        return Stream.of(
            Arguments.of(
                values(),
                countWeights(),
                new Aggregation[]{
                    Aggregation.COUNT,
                    Aggregation.COUNT
                },
                new double[][]{
                    {
                        4d, 2d
                    }, {
                        2d, 1d
                    }
                },
                "COUNT"
            ),
            Arguments.of(
                values(),
                weights(),
                new Aggregation[]{
                    Aggregation.SUM,
                    Aggregation.SUM
                },
                new double[][]{
                    {
                        16d, 8d
                    }, {
                        27d, 14d
                    }
                },
                "SUM"
            ),
            Arguments.of(
                values(),
                weights(),
                new Aggregation[]{
                    Aggregation.MIN,
                    Aggregation.MIN
                },
                new double[][]{
                    {
                        2d, 3d
                    }, {
                        4d, 6d
                    }
                },
                "MIN"
            ),
            Arguments.of(
                values(),
                weights(),
                new Aggregation[]{
                    Aggregation.MAX,
                    Aggregation.MAX
                },
                new double[][]{
                    {
                        5d, 5d
                    }, {
                        8d, 8d
                    }
                },
                "MAX"
            )
        );
    }

    private static long[][] weights() {
        return new long[][]{
            {
                doubleToLongBits(2),
                doubleToLongBits(4),
                doubleToLongBits(3),
                doubleToLongBits(5),
                doubleToLongBits(5),
                doubleToLongBits(5)
            }, {
                doubleToLongBits(4),
                doubleToLongBits(7),
                doubleToLongBits(6),
                doubleToLongBits(8),
                doubleToLongBits(8),
                doubleToLongBits(8)
            }
        };
    }

    private static long[][] countWeights() {
        return new long[][]{
            {
                doubleToLongBits(1.0),
                doubleToLongBits(1.0),
                doubleToLongBits(1.0),
                doubleToLongBits(1.0),
                doubleToLongBits(1.0),
                doubleToLongBits(1.0)
            }, {
                doubleToLongBits(1.0),
                doubleToLongBits(0.0),
                doubleToLongBits(1.0),
                doubleToLongBits(0.0),
                doubleToLongBits(1.0),
                doubleToLongBits(0.0)
            }
        };
    }

    private static long[] values() {
        return new long[]{
            1, 1, 5, 5, 1, 1
        };
    }

    static Stream<Arguments> deltaArrays() {
        return Stream.of(
            Arguments.of(new long[0], 0, new long[0]),
            Arguments.of(new long[] {1, 1, 1}, 0, new long[] {1, 2, 3}),
            Arguments.of(new long[] {1, 1, 1}, 41, new long[] {42, 43, 44}),
            Arguments.of(new long[] {0, 0, 0}, 41, new long[] {41, 41, 41}),
            // more than 4 elements to trigger unrolled loop
            Arguments.of(new long[] {1, 1, 1, 1, 1, 1, 1}, 0, new long[] {1, 2, 3, 4, 5, 6, 7}),
            Arguments.of(new long[] {1, 1, 0, 1, 1, 1, 1}, 1, new long[] {2, 3, 3, 4, 5, 6, 7})
        );
    }

    @ParameterizedTest
    @MethodSource("deltaArrays")
    void deltaDecodeEmptyArray(long[] deltas, long first, long[] expected) {
        long last = AdjacencyCompression.deltaDecode(deltas, deltas.length, first);

        assertThat(deltas).isEqualTo(expected);

        if (expected.length > 0) {
            assertThat(last).isEqualTo(expected[expected.length - 1]);
        }
    }

}
