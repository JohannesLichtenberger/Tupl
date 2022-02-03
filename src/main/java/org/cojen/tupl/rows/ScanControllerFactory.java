/*
 *  Copyright (C) 2021 Cojen.org
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU Affero General Public License as
 *  published by the Free Software Foundation, either version 3 of the
 *  License, or (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU Affero General Public License for more details.
 *
 *  You should have received a copy of the GNU Affero General Public License
 *  along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.cojen.tupl.rows;

import org.cojen.tupl.core.RowPredicate;

import org.cojen.tupl.diag.QueryPlan;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public interface ScanControllerFactory<R> {
    QueryPlan plan(Object... args);

    /**
     * Return a ScanController which constructs a RowPredicate from the given filter arguments.
     */
    ScanController<R> newScanController(Object... args);

    /**
     * Return a ScanController which references a RowPredicate as constructed by the first batch.
     */
    default ScanController<R> newScanController(RowPredicate predicate) {
        throw new UnsupportedOperationException();
    }
}
