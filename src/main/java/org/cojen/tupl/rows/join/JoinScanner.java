/*
 *  Copyright (C) 2023 Cojen.org
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

package org.cojen.tupl.rows.join;

import java.io.IOException;

import org.cojen.tupl.Scanner;

/**
 * Base class for generated join table scanners.
 *
 * @author Brian S O'Neill
 * @see JoinScannerMaker
 */
public abstract class JoinScanner<J> implements Scanner<J> {
    @Override
    public int characteristics() {
        return NONNULL | ORDERED | CONCURRENT | DISTINCT;
    }

    @Override
    public long estimateSize() {
        return Long.MAX_VALUE;
    }

    protected final Throwable close(J joinRow, Throwable cause) {
        clear(joinRow);
        try {
            close();
        } catch (Throwable e) {
            cause.addSuppressed(e);
        }
        return cause;
    }

    protected abstract void clear(J joinRow);
}
