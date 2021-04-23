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
import io.stargate.db.query.builder.BuiltCondition;
import io.stargate.web.docsapi.service.query.condition.BaseCondition;
import io.stargate.web.docsapi.service.query.predicate.impl.ExistsPredicate;
import org.immutables.value.Value;

import java.util.Optional;

@Value.Immutable
public abstract class ExistsCondition implements BaseCondition {

    /**
     * @return Predicate for the condition.
     */
    @Value.Parameter
    public abstract ExistsPredicate getFilterPredicate();

    /**
     * @return Filter query value.
     */
    @Value.Parameter
    public abstract Boolean getQueryValue();

    /**
     * @return If booleans should be considered as numeric values.
     */
    @Value.Parameter
    public abstract boolean isNumericBooleans();

    /**
     * Validates the value against the predicate.
     */
    @Value.Check
    protected void validate() {
        getFilterPredicate().validateBooleanFilterInput(getQueryValue());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Optional<BuiltCondition> getBuiltCondition() {
        // TODO what's real predicate for the exists
        return Optional.empty();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean test(Row row) {
        Boolean dbValueBoolean = getBooleanDatabaseValue(row, isNumericBooleans());
        Double dbValueDouble = getDoubleDatabaseValue(row);
        String dbValueString = getStringDatabaseValue(row);

        ExistsPredicate filterPredicate = getFilterPredicate();
        Boolean queryValue = getQueryValue();
        return filterPredicate.test(queryValue, dbValueBoolean) ||
                filterPredicate.test(queryValue, dbValueDouble) ||
                filterPredicate.test(queryValue, dbValueString);
    }

}
