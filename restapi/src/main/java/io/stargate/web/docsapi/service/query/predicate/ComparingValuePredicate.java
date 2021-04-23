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

package io.stargate.web.docsapi.service.query.predicate;

import java.util.Comparator;

/**
 * Helper interface that can be used by any standard predicate that depends on the value comparing.
 */
public interface ComparingValuePredicate extends StringFilterPredicate<String>, DoubleFilterPredicate<Number>, BooleanValuePredicate<Boolean> {

    /**
     * Method for the comparing predicates that resolves if predicate test is true or false.
     * Note that we always compare filter value against the DB value.
     *
     * @param compareValue comparison value
     * @return If this comparison value satisfies the predicate test
     */
    boolean isSatisfied(int compareValue);

    /**
     * {@inheritDoc}
     */
    default boolean test(String filterValue, String dbValue) {
        int compare = Comparator.<String>naturalOrder().compare(filterValue, dbValue);
        return isSatisfied(compare);
    }

    /**
     * {@inheritDoc}
     */
    default boolean test(Number filterValue, Double dbValue) {
        // TODO do we wanna have more sophisticated compare for the numbers
        int compare = Comparator.<Double>naturalOrder().compare(filterValue.doubleValue(), dbValue);
        return isSatisfied(compare);
    }

    /**
     * {@inheritDoc}
     */
    default boolean test(Boolean filterValue, Boolean dbValue) {
        int compare = Comparator.<Boolean>naturalOrder().compare(filterValue, dbValue);
        return isSatisfied(compare);
    }

}

