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
package org.apache.iceberg.arrow.vectorized;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.apache.iceberg.types.Types;
import org.apache.parquet.column.ColumnDescriptor;
import org.apache.parquet.schema.PrimitiveType;
import org.apache.parquet.schema.Type.Repetition;
import org.junit.jupiter.api.Test;

public class TestGenericArrowVectorAccessorFactory {

  @Test
  public void testGetVectorAccessorThrowsForNullPlainVector() {
    GenericArrowVectorAccessorFactory<?, ?, ?, ?> factory =
        new GenericArrowVectorAccessorFactory<Object, String, Object, AutoCloseable>(
            () -> {
              throw new UnsupportedOperationException("Decimal factory is not expected");
            },
            () -> {
              throw new UnsupportedOperationException("String factory is not expected");
            },
            () -> {
              throw new UnsupportedOperationException("Struct factory is not expected");
            },
            () -> {
              throw new UnsupportedOperationException("Array factory is not expected");
            }) {};
    Types.NestedField nestedField = Types.NestedField.optional(1, "a1", Types.IntegerType.get());
    ColumnDescriptor descriptor =
        new ColumnDescriptor(
            new String[] {"a1"},
            new PrimitiveType(Repetition.OPTIONAL, PrimitiveType.PrimitiveTypeName.INT32, "a1"),
            0,
            1);
    VectorHolder holder = VectorHolder.constantHolder(nestedField, 1, 123);

    assertThatThrownBy(() -> factory.getVectorAccessor(holder))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessage("Unsupported vector: null");
  }
}
