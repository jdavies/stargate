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

package io.stargate.web.docsapi.service.query.predicate.impl;

import io.stargate.web.docsapi.exception.DocumentAPIRequestException;
import io.stargate.web.docsapi.service.query.predicate.ComparingValuePredicate;

/**
 * Shared abstract class for all the predicates that require non-null filter values.
 */
public abstract class NotNullValuePredicate implements ComparingValuePredicate {

    /**
     * {@inheritDoc}
     */
    @Override
    public void validateDoubleFilterInput(Number filterValue) {
        validateFilterInput(filterValue);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void validateStringFilterInput(String filterValue) {
        validateFilterInput(filterValue);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void validateBooleanFilterInput(Boolean filterValue) {
        validateFilterInput(filterValue);
    }

    private void validateFilterInput(Object filterInput) {
        if (null == filterInput) {
            String msg = String.format("Operation %s was expecting a non-null value", getRawValue());
            throw new DocumentAPIRequestException(msg);
        }
    }

}
