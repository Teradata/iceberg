/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.iceberg.parquet;

import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.Comparator;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.apache.iceberg.Schema;
import org.apache.iceberg.expressions.Binder;
import org.apache.iceberg.expressions.Bound;
import org.apache.iceberg.expressions.BoundReference;
import org.apache.iceberg.expressions.Expression;
import org.apache.iceberg.expressions.ExpressionVisitors;
import org.apache.iceberg.expressions.ExpressionVisitors.BoundExpressionVisitor;
import org.apache.iceberg.expressions.Expressions;
import org.apache.iceberg.expressions.Literal;
import org.apache.iceberg.relocated.com.google.common.collect.Maps;
import org.apache.iceberg.types.Comparators;
import org.apache.iceberg.types.Type;
import org.apache.iceberg.types.Types.StructType;
import org.apache.iceberg.util.BinaryUtil;
import org.apache.parquet.column.statistics.Statistics;
import org.apache.parquet.hadoop.metadata.BlockMetaData;
import org.apache.parquet.hadoop.metadata.ColumnChunkMetaData;
import org.apache.parquet.io.api.Binary;
import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.PrimitiveType;

public class ParquetMetricsRowGroupFilter {
  private static final int IN_PREDICATE_LIMIT = 200;

  private final Schema schema;
  private final Expression expr;

  public ParquetMetricsRowGroupFilter(Schema schema, Expression unbound) {
    this(schema, unbound, true);
  }

  public ParquetMetricsRowGroupFilter(Schema schema, Expression unbound, boolean caseSensitive) {
    this.schema = schema;
    StructType struct = schema.asStruct();
    this.expr = Binder.bind(struct, Expressions.rewriteNot(unbound), caseSensitive);
  }

  /**
   * Test whether the file may contain records that match the expression.
   *
   * @param fileSchema schema for the Parquet file
   * @param rowGroup metadata for a row group
   * @return false if the file cannot contain rows that match the expression, true otherwise.
   */
  public boolean shouldRead(MessageType fileSchema, BlockMetaData rowGroup) {
    return new MetricsEvalVisitor().eval(fileSchema, rowGroup);
  }

  private static final boolean ROWS_MIGHT_MATCH = true;
  private static final boolean ROWS_CANNOT_MATCH = false;

  private class MetricsEvalVisitor extends BoundExpressionVisitor<Boolean> {
    private Map<Integer, Statistics<?>> stats = null;
    private Map<Integer, Long> valueCounts = null;
    private Map<Integer, Function<Object, Object>> conversions = null;

    private boolean eval(MessageType fileSchema, BlockMetaData rowGroup) {
      if (rowGroup.getRowCount() <= 0) {
        return ROWS_CANNOT_MATCH;
      }

      this.stats = Maps.newHashMap();
      this.valueCounts = Maps.newHashMap();
      this.conversions = Maps.newHashMap();
      for (ColumnChunkMetaData col : rowGroup.getColumns()) {
        PrimitiveType colType = fileSchema.getType(col.getPath().toArray()).asPrimitiveType();
        if (colType.getId() != null) {
          int id = colType.getId().intValue();
          Type icebergType = schema.findType(id);
          stats.put(id, col.getStatistics());
          valueCounts.put(id, col.getValueCount());
          conversions.put(id, ParquetConversions.converterFromParquet(colType, icebergType));
        }
      }

      return ExpressionVisitors.visitEvaluator(expr, this);
    }

    @Override
    public Boolean alwaysTrue() {
      return ROWS_MIGHT_MATCH; // all rows match
    }

    @Override
    public Boolean alwaysFalse() {
      return ROWS_CANNOT_MATCH; // all rows fail
    }

    @Override
    public Boolean not(Boolean result) {
      return !result;
    }

    @Override
    public Boolean and(Boolean leftResult, Boolean rightResult) {
      return leftResult && rightResult;
    }

    @Override
    public Boolean or(Boolean leftResult, Boolean rightResult) {
      return leftResult || rightResult;
    }

    @Override
    public <T> Boolean isNull(BoundReference<T> ref) {
      // no need to check whether the field is required because binding evaluates that case
      // if the column has no null values, the expression cannot match
      int id = ref.fieldId();

      Long valueCount = valueCounts.get(id);
      if (valueCount == null) {
        // the column is not present and is all nulls
        return ROWS_MIGHT_MATCH;
      }

      Statistics<?> colStats = stats.get(id);
      if (colStats != null && !colStats.isEmpty() && colStats.getNumNulls() == 0) {
        // there are stats and no values are null => all values are non-null
        return ROWS_CANNOT_MATCH;
      }

      return ROWS_MIGHT_MATCH;
    }

    @Override
    public <T> Boolean notNull(BoundReference<T> ref) {
      // no need to check whether the field is required because binding evaluates that case
      // if the column has no non-null values, the expression cannot match
      int id = ref.fieldId();

      if (checkNestedTypes(id)) {
        return ROWS_MIGHT_MATCH;
      }

      Long valueCount = valueCounts.get(id);
      if (valueCount == null) {
        // the column is not present and is all nulls
        return ROWS_CANNOT_MATCH;
      }

      Statistics<?> colStats = stats.get(id);
      if (colStats != null && valueCount - colStats.getNumNulls() == 0) {
        // (num nulls == value count) => all values are null => no non-null values
        return ROWS_CANNOT_MATCH;
      }

      return ROWS_MIGHT_MATCH;
    }

    @Override
    public <T> Boolean isNaN(BoundReference<T> ref) {
      int id = ref.fieldId();

      Long valueCount = valueCounts.get(id);
      if (valueCount == null) {
        // the column is not present and is all nulls
        return ROWS_CANNOT_MATCH;
      }

      Statistics<?> colStats = stats.get(id);
      if (colStats != null && valueCount - colStats.getNumNulls() == 0) {
        // (num nulls == value count) => all values are null => no nan values
        return ROWS_CANNOT_MATCH;
      }

      return ROWS_MIGHT_MATCH;
    }

    @Override
    public <T> Boolean notNaN(BoundReference<T> ref) {
      return ROWS_MIGHT_MATCH;
    }

    @Override
    public <T> Boolean lt(BoundReference<T> ref, Literal<T> lit) {
      int id = ref.fieldId();

      Long valueCount = valueCounts.get(id);
      if (valueCount == null) {
        // the column is not present and is all nulls
        return ROWS_CANNOT_MATCH;
      }

      Statistics<?> colStats = stats.get(id);
      if (colStats != null && !colStats.isEmpty()) {
        if (allNulls(colStats, valueCount)) {
          return ROWS_CANNOT_MATCH;
        }

        if (minMaxUndefined(colStats)) {
          return ROWS_MIGHT_MATCH;
        }

        T lower = min(colStats, id);
        int cmp = lit.comparator().compare(lower, lit.value());
        if (cmp >= 0) {
          return ROWS_CANNOT_MATCH;
        }
      }

      return ROWS_MIGHT_MATCH;
    }

    @Override
    public <T> Boolean lt(BoundReference<T> ref, BoundReference<T> ref2) {
      int id = ref.fieldId();
      int id2 = ref2.fieldId();

      if (ref.type().typeId() != ref2.type().typeId()) {
        return ROWS_MIGHT_MATCH;
      }

      Long valueCount = valueCounts.get(id);
      Long valueCount2 = valueCounts.get(id2);
      if (valueCount == null || valueCount2 == null) {
        // the column is not present and is all nulls
        return ROWS_CANNOT_MATCH;
      }

      Statistics<?> colStats = stats.get(id);
      Statistics<?> colStats2 = stats.get(id2);
      if (colStatsExist(colStats, colStats2)) {
        if (allNulls(colStats, valueCount) || allNulls(colStats2, valueCount2)) {
          return ROWS_CANNOT_MATCH;
        }

        if (minMaxUndefined(colStats) || minMaxUndefined(colStats2)) {
          return ROWS_MIGHT_MATCH;
        }

        if (compareLowerToUpperStats(ref, id, id2, colStats, colStats2, cmp -> cmp >= 0)) {
          return ROWS_CANNOT_MATCH;
        }
      }

      return ROWS_MIGHT_MATCH;
    }

    @Override
    public <T> Boolean ltEq(BoundReference<T> ref, Literal<T> lit) {
      int id = ref.fieldId();

      Long valueCount = valueCounts.get(id);
      if (valueCount == null) {
        // the column is not present and is all nulls
        return ROWS_CANNOT_MATCH;
      }

      Statistics<?> colStats = stats.get(id);
      if (colStats != null && !colStats.isEmpty()) {
        if (allNulls(colStats, valueCount)) {
          return ROWS_CANNOT_MATCH;
        }

        if (minMaxUndefined(colStats)) {
          return ROWS_MIGHT_MATCH;
        }

        T lower = min(colStats, id);
        int cmp = lit.comparator().compare(lower, lit.value());
        if (cmp > 0) {
          return ROWS_CANNOT_MATCH;
        }
      }

      return ROWS_MIGHT_MATCH;
    }

    @Override
    public <T> Boolean ltEq(BoundReference<T> ref, BoundReference<T> ref2) {
      int id = ref.fieldId();
      int id2 = ref2.fieldId();

      if (ref.type().typeId() != ref2.type().typeId()) {
        return ROWS_MIGHT_MATCH;
      }

      Long valueCount = valueCounts.get(id);
      Long valueCount2 = valueCounts.get(id2);
      if (valueCount == null || valueCount2 == null) {
        // the column is not present and is all nulls
        return ROWS_CANNOT_MATCH;
      }

      Statistics<?> colStats = stats.get(id);
      Statistics<?> colStats2 = stats.get(id2);
      if (colStatsExist(colStats, colStats2)) {
        if (allNulls(colStats, valueCount) || allNulls(colStats2, valueCount2)) {
          return ROWS_CANNOT_MATCH;
        }

        if (minMaxUndefined(colStats) || minMaxUndefined(colStats2)) {
          return ROWS_MIGHT_MATCH;
        }

        if (compareLowerToUpperStats(ref, id, id2, colStats, colStats2, cmp -> cmp > 0)) {
          return ROWS_CANNOT_MATCH;
        }
      }

      return ROWS_MIGHT_MATCH;
    }

    @Override
    public <T> Boolean gt(BoundReference<T> ref, Literal<T> lit) {
      int id = ref.fieldId();

      Long valueCount = valueCounts.get(id);
      if (valueCount == null) {
        // the column is not present and is all nulls
        return ROWS_CANNOT_MATCH;
      }

      Statistics<?> colStats = stats.get(id);
      if (colStats != null && !colStats.isEmpty()) {
        if (allNulls(colStats, valueCount)) {
          return ROWS_CANNOT_MATCH;
        }

        if (minMaxUndefined(colStats)) {
          return ROWS_MIGHT_MATCH;
        }

        T upper = max(colStats, id);
        int cmp = lit.comparator().compare(upper, lit.value());
        if (cmp <= 0) {
          return ROWS_CANNOT_MATCH;
        }
      }

      return ROWS_MIGHT_MATCH;
    }

    @Override
    public <T> Boolean gt(BoundReference<T> ref, BoundReference<T> ref2) {
      int id = ref.fieldId();
      int id2 = ref2.fieldId();

      if (ref.type().typeId() != ref2.type().typeId()) {
        return ROWS_MIGHT_MATCH;
      }

      Long valueCount = valueCounts.get(id);
      Long valueCount2 = valueCounts.get(id2);
      if (valueCount == null || valueCount2 == null) {
        // the column is not present and is all nulls
        return ROWS_CANNOT_MATCH;
      }

      Statistics<?> colStats = stats.get(id);
      Statistics<?> colStats2 = stats.get(id2);
      if (colStatsExist(colStats, colStats2)) {
        if (allNulls(colStats, valueCount) || allNulls(colStats2, valueCount2)) {
          return ROWS_CANNOT_MATCH;
        }

        if (minMaxUndefined(colStats) || minMaxUndefined(colStats2)) {
          return ROWS_MIGHT_MATCH;
        }

        if (compareUpperStats(ref, id, id2, colStats, colStats2, cmp -> cmp <= 0)) {
          return ROWS_CANNOT_MATCH;
        }

        if (compareUpperToLowerStats(ref, id, id2, colStats, colStats2, cmp -> cmp <= 0)) {
          return ROWS_CANNOT_MATCH;
        }
      }

      return ROWS_MIGHT_MATCH;
    }

    @Override
    public <T> Boolean gtEq(BoundReference<T> ref, Literal<T> lit) {
      int id = ref.fieldId();

      Long valueCount = valueCounts.get(id);
      if (valueCount == null) {
        // the column is not present and is all nulls
        return ROWS_CANNOT_MATCH;
      }

      Statistics<?> colStats = stats.get(id);
      if (colStats != null && !colStats.isEmpty()) {
        if (allNulls(colStats, valueCount)) {
          return ROWS_CANNOT_MATCH;
        }

        if (minMaxUndefined(colStats)) {
          return ROWS_MIGHT_MATCH;
        }

        T upper = max(colStats, id);
        int cmp = lit.comparator().compare(upper, lit.value());
        if (cmp < 0) {
          return ROWS_CANNOT_MATCH;
        }
      }

      return ROWS_MIGHT_MATCH;
    }

    @Override
    public <T> Boolean gtEq(BoundReference<T> ref, BoundReference<T> ref2) {
      int id = ref.fieldId();
      int id2 = ref2.fieldId();

      if (ref.type().typeId() != ref2.type().typeId()) {
        return ROWS_MIGHT_MATCH;
      }

      Long valueCount = valueCounts.get(id);
      Long valueCount2 = valueCounts.get(id2);
      if (valueCount == null || valueCount2 == null) {
        // the column is not present and is all nulls
        return ROWS_CANNOT_MATCH;
      }

      Statistics<?> colStats = stats.get(id);
      Statistics<?> colStats2 = stats.get(id2);
      if (colStatsExist(colStats, colStats2)) {
        if (allNulls(colStats, valueCount) || allNulls(colStats2, valueCount2)) {
          return ROWS_CANNOT_MATCH;
        }

        if (minMaxUndefined(colStats) || minMaxUndefined(colStats2)) {
          return ROWS_MIGHT_MATCH;
        }

        if (compareUpperStats(ref, id, id2, colStats, colStats2, cmp -> cmp < 0)) {
          return ROWS_CANNOT_MATCH;
        }

        if (compareUpperToLowerStats(ref, id, id2, colStats, colStats2, cmp -> cmp < 0)) {
          return ROWS_CANNOT_MATCH;
        }
      }

      return ROWS_MIGHT_MATCH;
    }

    @Override
    public <T> Boolean eq(BoundReference<T> ref, Literal<T> lit) {
      int id = ref.fieldId();

      if (checkNestedTypes(id)) {
        return ROWS_MIGHT_MATCH;
      }

      Long valueCount = valueCounts.get(id);
      if (valueCount == null) {
        // the column is not present and is all nulls
        return ROWS_CANNOT_MATCH;
      }

      Statistics<?> colStats = stats.get(id);
      if (colStats != null && !colStats.isEmpty()) {
        if (allNulls(colStats, valueCount)) {
          return ROWS_CANNOT_MATCH;
        }

        if (minMaxUndefined(colStats)) {
          return ROWS_MIGHT_MATCH;
        }

        T lower = min(colStats, id);
        int cmp = lit.comparator().compare(lower, lit.value());
        if (cmp > 0) {
          return ROWS_CANNOT_MATCH;
        }

        T upper = max(colStats, id);
        cmp = lit.comparator().compare(upper, lit.value());
        if (cmp < 0) {
          return ROWS_CANNOT_MATCH;
        }
      }

      return ROWS_MIGHT_MATCH;
    }

    @Override
    public <T> Boolean eq(BoundReference<T> ref, BoundReference<T> ref2) {
      int id = ref.fieldId();
      int id2 = ref2.fieldId();

      if (ref.type().typeId() != ref2.type().typeId()) {
        return ROWS_MIGHT_MATCH;
      }

      if (checkNestedTypes(id) || checkNestedTypes(id2)) {
        return ROWS_MIGHT_MATCH;
      }

      Long valueCount = valueCounts.get(id);
      Long valueCount2 = valueCounts.get(id2);
      if (valueCount == null || valueCount2 == null) {
        // the column is not present and is all nulls
        return ROWS_CANNOT_MATCH;
      }

      Boolean rowsCannotMatch = compareStats(ref, id, valueCount, id2, valueCount2);
      if (rowsCannotMatch != null) {
        return rowsCannotMatch;
      }

      return ROWS_MIGHT_MATCH;
    }

    @Override
    public <T> Boolean notEq(BoundReference<T> ref, Literal<T> lit) {
      // because the bounds are not necessarily a min or max value, this cannot be answered using
      // them. notEq(col, X) with (X, Y) doesn't guarantee that X is a value in col.
      return ROWS_MIGHT_MATCH;
    }

    @Override
    public <T> Boolean notEq(BoundReference<T> ref, BoundReference<T> ref2) {
      // because the bounds are not necessarily a min or max value, this cannot be answered using
      // them. notEq(col, X) with (X, Y) doesn't guarantee that X is a value in col.
      return ROWS_MIGHT_MATCH;
    }

    private <T> Boolean compareStats(
        BoundReference<T> ref, int id, Long valueCount, int id2, Long valueCount2) {
      Statistics<?> colStats = stats.get(id);
      if (colStats != null && !colStats.isEmpty()) {
        if (allNulls(colStats, valueCount)) {
          return ROWS_CANNOT_MATCH;
        }

        Statistics<?> colStats2 = stats.get(id2);
        if (colStats2 != null && !colStats2.isEmpty()) {
          if (allNulls(colStats2, valueCount2)) {
            return ROWS_CANNOT_MATCH;
          }

          if (minMaxUndefined(colStats) || minMaxUndefined(colStats2)) {
            return ROWS_MIGHT_MATCH;
          }

          if (compareLowerStats(ref, id, id2, colStats, colStats2, cmp -> cmp > 0)) {
            return ROWS_CANNOT_MATCH;
          }
          if (compareUpperStats(ref, id, id2, colStats, colStats2, cmp -> cmp < 0)) {
            return ROWS_CANNOT_MATCH;
          }
        }
      }
      return ROWS_MIGHT_MATCH;
    }

    private <T> boolean compareUpperStats(
        BoundReference<T> ref,
        int id,
        int id2,
        Statistics<?> colStats,
        Statistics<?> colStats2,
        java.util.function.Predicate<Integer> compare) {
      int cmp;

      T upper = max(colStats, id);
      T upper2 = max(colStats2, id2);
      cmp = ref.comparator().compare(upper, upper2);
      if (compare.test(cmp)) {
        return true;
      }
      return false;
    }

    private <T> boolean compareLowerStats(
        BoundReference<T> ref,
        int id,
        int id2,
        Statistics<?> colStats,
        Statistics<?> colStats2,
        java.util.function.Predicate<Integer> compare) {
      T lower = min(colStats, id);
      T lower2 = min(colStats2, id2);
      int cmp = ref.comparator().compare(lower, lower2);
      if (compare.test(cmp)) {
        return true;
      }
      return false;
    }

    private <T> boolean compareLowerToUpperStats(
        BoundReference<T> ref,
        int id,
        int id2,
        Statistics<?> colStats,
        Statistics<?> colStats2,
        java.util.function.Predicate<Integer> compare) {
      T lower = min(colStats, id);
      T upper = max(colStats2, id2);
      int cmp = ref.comparator().compare(lower, upper);
      if (compare.test(cmp)) {
        return true;
      }
      return false;
    }

    private <T> boolean compareUpperToLowerStats(
        BoundReference<T> ref,
        int id,
        int id2,
        Statistics<?> colStats,
        Statistics<?> colStats2,
        java.util.function.Predicate<Integer> compare) {
      T upper = max(colStats, id);
      T lower = min(colStats2, id2);
      int cmp = ref.comparator().compare(upper, lower);
      if (compare.test(cmp)) {
        return true;
      }
      return false;
    }

    @Override
    public <T> Boolean in(BoundReference<T> ref, Set<T> literalSet) {
      int id = ref.fieldId();

      if (checkNestedTypes(id)) {
        return ROWS_MIGHT_MATCH;
      }

      Long valueCount = valueCounts.get(id);
      if (valueCount == null) {
        // the column is not present and is all nulls
        return ROWS_CANNOT_MATCH;
      }

      Statistics<?> colStats = stats.get(id);
      if (colStats != null && !colStats.isEmpty()) {
        if (allNulls(colStats, valueCount)) {
          return ROWS_CANNOT_MATCH;
        }

        if (minMaxUndefined(colStats)) {
          return ROWS_MIGHT_MATCH;
        }

        Collection<T> literals = literalSet;

        if (literals.size() > IN_PREDICATE_LIMIT) {
          // skip evaluating the predicate if the number of values is too big
          return ROWS_MIGHT_MATCH;
        }

        T lower = min(colStats, id);
        literals =
            literals.stream()
                .filter(v -> ref.comparator().compare(lower, v) <= 0)
                .collect(Collectors.toList());
        if (literals.isEmpty()) { // if all values are less than lower bound, rows cannot match.
          return ROWS_CANNOT_MATCH;
        }

        T upper = max(colStats, id);
        literals =
            literals.stream()
                .filter(v -> ref.comparator().compare(upper, v) >= 0)
                .collect(Collectors.toList());
        if (literals
            .isEmpty()) { // if all remaining values are greater than upper bound, rows cannot
          // match.
          return ROWS_CANNOT_MATCH;
        }
      }

      return ROWS_MIGHT_MATCH;
    }

    private boolean checkNestedTypes(int id) {
      // When filtering nested types notNull() is implicit filter passed even though complex
      // filters aren't pushed down in Parquet. Leave all nested column type filters to be
      // evaluated post scan.
      if (schema.findType(id) instanceof Type.NestedType) {
        return true;
      }
      return false;
    }

    @Override
    public <T> Boolean notIn(BoundReference<T> ref, Set<T> literalSet) {
      // because the bounds are not necessarily a min or max value, this cannot be answered using
      // them. notIn(col, {X, ...}) with (X, Y) doesn't guarantee that X is a value in col.
      return ROWS_MIGHT_MATCH;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> Boolean startsWith(BoundReference<T> ref, Literal<T> lit) {
      int id = ref.fieldId();

      Long valueCount = valueCounts.get(id);
      if (valueCount == null) {
        // the column is not present and is all nulls
        return ROWS_CANNOT_MATCH;
      }

      Statistics<Binary> colStats = (Statistics<Binary>) stats.get(id);
      if (colStats != null && !colStats.isEmpty()) {
        if (allNulls(colStats, valueCount)) {
          return ROWS_CANNOT_MATCH;
        }

        if (minMaxUndefined(colStats)) {
          return ROWS_MIGHT_MATCH;
        }

        ByteBuffer prefixAsBytes = lit.toByteBuffer();

        Comparator<ByteBuffer> comparator = Comparators.unsignedBytes();

        Binary lower = colStats.genericGetMin();
        // truncate lower bound so that its length in bytes is not greater than the length of prefix
        int lowerLength = Math.min(prefixAsBytes.remaining(), lower.length());
        int lowerCmp =
            comparator.compare(
                BinaryUtil.truncateBinary(lower.toByteBuffer(), lowerLength), prefixAsBytes);
        if (lowerCmp > 0) {
          return ROWS_CANNOT_MATCH;
        }

        Binary upper = colStats.genericGetMax();
        // truncate upper bound so that its length in bytes is not greater than the length of prefix
        int upperLength = Math.min(prefixAsBytes.remaining(), upper.length());
        int upperCmp =
            comparator.compare(
                BinaryUtil.truncateBinary(upper.toByteBuffer(), upperLength), prefixAsBytes);
        if (upperCmp < 0) {
          return ROWS_CANNOT_MATCH;
        }
      }

      return ROWS_MIGHT_MATCH;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> Boolean notStartsWith(BoundReference<T> ref, Literal<T> lit) {
      int id = ref.fieldId();
      Long valueCount = valueCounts.get(id);

      if (valueCount == null) {
        // the column is not present and is all nulls
        return ROWS_MIGHT_MATCH;
      }

      Statistics<Binary> colStats = (Statistics<Binary>) stats.get(id);
      if (colStats != null && !colStats.isEmpty()) {
        if (mayContainNull(colStats)) {
          return ROWS_MIGHT_MATCH;
        }

        if (minMaxUndefined(colStats)) {
          return ROWS_MIGHT_MATCH;
        }

        Binary lower = colStats.genericGetMin();
        Binary upper = colStats.genericGetMax();

        // notStartsWith will match unless all values must start with the prefix. this happens when
        // the lower and upper
        // bounds both start with the prefix.
        if (lower != null && upper != null) {
          ByteBuffer prefix = lit.toByteBuffer();
          Comparator<ByteBuffer> comparator = Comparators.unsignedBytes();

          // if lower is shorter than the prefix, it can't start with the prefix
          if (lower.length() < prefix.remaining()) {
            return ROWS_MIGHT_MATCH;
          }

          // truncate lower bound to the prefix and check for equality
          int cmp =
              comparator.compare(
                  BinaryUtil.truncateBinary(lower.toByteBuffer(), prefix.remaining()), prefix);
          if (cmp == 0) {
            // the lower bound starts with the prefix; check the upper bound
            // if upper is shorter than the prefix, it can't start with the prefix
            if (upper.length() < prefix.remaining()) {
              return ROWS_MIGHT_MATCH;
            }

            // truncate upper bound so that its length in bytes is not greater than the length of
            // prefix
            cmp =
                comparator.compare(
                    BinaryUtil.truncateBinary(upper.toByteBuffer(), prefix.remaining()), prefix);
            if (cmp == 0) {
              // both bounds match the prefix, so all rows must match the prefix and none do not
              // match
              return ROWS_CANNOT_MATCH;
            }
          }
        }
      }

      return ROWS_MIGHT_MATCH;
    }

    @SuppressWarnings("unchecked")
    private <T> T min(Statistics<?> statistics, int id) {
      return (T) conversions.get(id).apply(statistics.genericGetMin());
    }

    @SuppressWarnings("unchecked")
    private <T> T max(Statistics<?> statistics, int id) {
      return (T) conversions.get(id).apply(statistics.genericGetMax());
    }

    @Override
    public <T> Boolean handleNonReference(Bound<T> term) {
      return ROWS_MIGHT_MATCH;
    }
  }

  private static boolean colStatsExist(Statistics<?> colStats, Statistics<?> colStats2) {
    return colStats != null && !colStats.isEmpty() && colStats2 != null && !colStats2.isEmpty();
  }

  /**
   * Older versions of Parquet statistics which may have a null count but undefined min and max
   * statistics. This is similar to the current behavior when NaN values are present.
   *
   * <p>This is specifically for 1.5.0-CDH Parquet builds and later which contain the different
   * unusual hasNonNull behavior. OSS Parquet builds are not effected because PARQUET-251 prohibits
   * the reading of these statistics from versions of Parquet earlier than 1.8.0.
   *
   * @param statistics Statistics to check
   * @return true if min and max statistics are null
   */
  static boolean nullMinMax(Statistics statistics) {
    return statistics.getMaxBytes() == null || statistics.getMinBytes() == null;
  }

  /**
   * The internal logic of Parquet-MR says that if numNulls is set but hasNonNull value is false,
   * then the min/max of the column are undefined.
   */
  static boolean minMaxUndefined(Statistics statistics) {
    return (statistics.isNumNullsSet() && !statistics.hasNonNullValue()) || nullMinMax(statistics);
  }

  static boolean allNulls(Statistics statistics, long valueCount) {
    return statistics.isNumNullsSet() && valueCount == statistics.getNumNulls();
  }

  private static boolean mayContainNull(Statistics statistics) {
    return !statistics.isNumNullsSet() || statistics.getNumNulls() > 0;
  }
}
