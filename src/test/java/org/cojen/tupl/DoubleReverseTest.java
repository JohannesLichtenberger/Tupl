/*
 *  Copyright (C) 2019 Cojen.org
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
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.cojen.tupl;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public class DoubleReverseTest extends ViewTest {
    public static void main(String[] args) throws Exception {
        org.junit.runner.JUnitCore.main(DoubleReverseTest.class.getName());
    }

    @Override
    protected View openIndex(String name) throws Exception {
        // Call constructor to actually double reverse.
        return new ReverseView(mDb.openIndex(name).viewReverse());
    }

    @Override
    public void counts() throws Exception {
        // Test doesn't work because ReverseView alters the key. Double reverse alters it twice.
    }
}
