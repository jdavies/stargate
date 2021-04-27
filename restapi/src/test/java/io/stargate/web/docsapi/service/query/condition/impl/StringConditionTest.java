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

package io.stargate.web.docsapi.service.query.condition.impl;

import io.stargate.db.datastore.Row;
import io.stargate.db.query.Predicate;
import io.stargate.db.query.builder.BuiltCondition;
import io.stargate.web.docsapi.service.query.predicate.StringFilterPredicate;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StringConditionTest {

    @Mock
    StringFilterPredicate<String> predicate;

    @Nested
    class Constructor {

        @Test
        public void predicateValidated() {
            String value = RandomStringUtils.randomAlphanumeric(16);

            StringCondition condition = ImmutableStringCondition.of(predicate, value);

            assertThat(condition).isNotNull();
            verify(predicate).validateStringFilterInput(value);
            verifyNoMoreInteractions(predicate);
        }

    }

    @Nested
    class GetBuiltCondition {

        @Test
        public void happyPath() {
            Predicate eq = Predicate.EQ;
            String value = RandomStringUtils.randomAlphanumeric(16);
            when(predicate.getDatabasePredicate()).thenReturn(Optional.of(eq));

            ImmutableStringCondition condition = ImmutableStringCondition.of(predicate, value);
            Optional<BuiltCondition> result = condition.getBuiltCondition();

            assertThat(result).hasValueSatisfying(builtCondition -> {
                assertThat(builtCondition.predicate()).isEqualTo(eq);
                assertThat(builtCondition.value().get()).isEqualTo(value);
                assertThat(builtCondition.lhs()).isEqualTo(BuiltCondition.LHS.column("text_value"));
            });
        }

        @Test
        public void emptyPredicate() {
            String value = RandomStringUtils.randomAlphanumeric(16);
            when(predicate.getDatabasePredicate()).thenReturn(Optional.empty());

            ImmutableStringCondition condition = ImmutableStringCondition.of(predicate, value);
            Optional<BuiltCondition> result = condition.getBuiltCondition();

            assertThat(result).isEmpty();
        }

    }

    @Nested
    class RowTest {

        @Mock
        Row row;

        @Test
        public void nullDatabaseValue() {
            String filterValue = RandomStringUtils.randomAlphanumeric(16);
            when(row.isNull("text_value")).thenReturn(true);
            when(predicate.test(filterValue, null)).thenReturn(true);

            ImmutableStringCondition condition = ImmutableStringCondition.of(predicate, filterValue);
            boolean result = condition.test(row);

            assertThat(result).isTrue();
        }

        @Test
        public void notNullDatabaseValue() {
            String filterValue = RandomStringUtils.randomAlphanumeric(16);
            String databaseValue = RandomStringUtils.randomAlphanumeric(16);
            when(row.isNull("text_value")).thenReturn(false);
            when(row.getString("text_value")).thenReturn(databaseValue);
            when(predicate.test(filterValue, databaseValue)).thenReturn(true);


            ImmutableStringCondition condition = ImmutableStringCondition.of(predicate, filterValue);
            boolean result = condition.test(row);

            assertThat(result).isTrue();
        }

    }

}