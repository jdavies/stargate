/*
 * Copyright The Stargate Authors
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package io.stargate.web.docsapi.service.query.filter.operation.impl;

import io.stargate.db.query.Predicate;
import io.stargate.web.docsapi.exception.DocumentAPIRequestException;
import io.stargate.web.docsapi.service.query.filter.operation.CombinedFilterOperation;
import io.stargate.web.docsapi.service.query.filter.operation.FilterOperationCode;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.immutables.value.Value;

/** In list filter operation. */
@Value.Style(visibility = Value.Style.ImplementationVisibility.PACKAGE)
@Value.Immutable(singleton = true)
public abstract class InFilterOperation implements CombinedFilterOperation<List<?>> {

  /** @return Singleton instance */
  public static InFilterOperation of() {
    return ImmutableInFilterOperation.of();
  }

  /** {@inheritDoc} */
  @Override
  public FilterOperationCode getOpCode() {
    return FilterOperationCode.IN;
  }

  /** {@inheritDoc} */
  @Override
  public Optional<Predicate> getQueryPredicate() {
    return Optional.empty();
  }

  @Override
  public boolean test(List<?> filterValue, Object dbValue) {
    // if null, check any is null, avoid .contains(null) as some impl could throw NPE
    if (null == dbValue) {
      return filterValue.stream().anyMatch(Objects::isNull);
    } else if (dbValue instanceof Double) {
      // TODO maybe more correct number matching here as well
      return filterValue.stream()
          .filter(Number.class::isInstance)
          .map(Number.class::cast)
          .anyMatch(value -> Double.valueOf(value.doubleValue()).equals(dbValue));
    } else {
      Class<?> clazz = dbValue.getClass();
      return filterValue.stream()
          .filter(clazz::isInstance)
          .anyMatch(value -> value.equals(dbValue));
    }
  }

  @Override
  public void validateFilterInput(List<?> filterValue) {
    if (null == filterValue || filterValue.isEmpty()) {
      String msg =
          String.format("Operation %s was expecting a non-empty list", getOpCode().getRawValue());
      throw new DocumentAPIRequestException(msg);
    }
  }
}
