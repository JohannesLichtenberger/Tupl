/*
 *  Copyright (C) 2022 Cojen.org
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

package org.cojen.tupl.diag;

import java.io.IOException;
import java.io.PrintStream;
import java.io.Serializable;

/**
 * A query plan tree structure.
 *
 * @author Brian S O'Neill
 */
public abstract sealed class QueryPlan implements Serializable {
    @Override
    public final String toString() {
        var b = new StringBuilder();
        appendTo(b);
        return b.toString();
    }

    public void printTo(PrintStream out) {
        try {
            appendTo((Appendable) out);
        } catch (IOException e) {
            // Not expected.
        }
    }

    public void appendTo(StringBuilder b) {
        try {
            appendTo((Appendable) b);
        } catch (IOException e) {
            // Not expected.
        }
    }

    public void appendTo(Appendable a) throws IOException {
        appendTo(a, "", "");
    }

    /**
     * @param in1 indent to use for first line
     * @param in2 indent to use for remaining lines
     */
    abstract void appendTo(Appendable a, String in1, String in2) throws IOException;

    private static Appendable appendItem(Appendable a, String indent, String title)
        throws IOException
    {
        return a.append(indent).append("...").append(title).append(": ");
    }

    /**
     * Query plan node which accesses rows from a table.
     */
    public static abstract sealed class Table extends QueryPlan {
        public final String table;
        public final String which;
        public final String[] keyColumns;

        /**
         * @param which primary key, alternate key, or secondary index
         * @param keyColumns columns with '+' or '-' prefixes
         */
        public Table(String table, String which, String[] keyColumns) {
            this.table = table;
            this.which = which;
            this.keyColumns = keyColumns;
        }

        Appendable appendKeyColumns(Appendable a, String indent) throws IOException {
            appendItem(a, indent, "key columns");

            for (int i=0; i<keyColumns.length; i++) {
                if (i > 0) {
                    a.append(", ");
                }
                a.append(keyColumns[i]);
            }

            return a;
        }
    }

    /**
     * Query plan node which scans a table.
     */
    public static abstract sealed class Scan extends Table {
        public final boolean reverse;

        /**
         * @param which primary key, alternate key, or secondary index
         * @param keyColumns columns with '+' or '-' prefix
         * @param reverse true if a reverse scan
         */
        public Scan(String table, String which, String[] keyColumns, boolean reverse) {
            super(table, which, keyColumns);
            this.reverse = reverse;
        }

        void appendTo(Appendable a, String in1, String in2, String title) throws IOException {
            a.append(in1);
            if (reverse) {
                a.append("reverse ");
            }
            a.append(title).append(" scan over ").append(which)
                .append(": ").append(table).append('\n');
            appendKeyColumns(a, in2).append('\n');
        }
    }

    /**
     * Query plan node which scans all rows of a table.
     */
    public static final class FullScan extends Scan {
        private static final long serialVersionUID = 1L;

        /**
         * @param which primary key, alternate key, or secondary index
         * @param keyColumns columns with '+' or '-' prefix
         * @param reverse true if a reverse scan
         */
        public FullScan(String table, String which, String[] keyColumns, boolean reverse) {
            super(table, which, keyColumns, reverse);
        }

        @Override
        void appendTo(Appendable a, String in1, String in2) throws IOException {
            appendTo(a, in1, in2, "full");
        }
    }

    /**
     * Query plan node which scans a range of rows from a table.
     */
    public static final class RangeScan extends Scan {
        private static final long serialVersionUID = 1L;

        public final String low, high;

        /**
         * @param which primary key, alternate key, or secondary index
         * @param keyColumns columns with '+' or '-' prefix
         * @param reverse true if a reverse scan
         * @param low filter expression (or null if open)
         * @param high filter expression (or null if open)
         */
        public RangeScan(String table, String which, String[] keyColumns, boolean reverse,
                         String low, String high)
        {
            super(table, which, keyColumns, reverse);
            this.low = low;
            this.high = high;
        }

        @Override
        void appendTo(Appendable a, String in1, String in2) throws IOException {
            appendTo(a, in1, in2, "range");
            appendItem(a, in2, "range");

            if (low != null) {
                a.append(low);
                if (high != null) {
                    a.append(" .. ").append(high);
                }
            } else if (high != null) {
                a.append(high);
            }

            a.append('\n');
        }
    }

    /**
     * Query plan node which loads at most one row from a table.
     */
    public static final class LoadOne extends Table {
        private static final long serialVersionUID = 1L;

        public final String expression;

        /**
         * @param which primary key, alternate key, or secondary index
         * @param keyColumns columns with '+' or '-' prefix
         * @param expression filter expression
         */
        public LoadOne(String table, String which, String[] keyColumns, String expression) {
            super(table, which, keyColumns);
            this.expression = expression;
        }

        @Override
        void appendTo(Appendable a, String in1, String in2) throws IOException {
            a.append(in1).append("load one using ").append(which)
                .append(": ").append(table).append('\n');
            appendKeyColumns(a, in2).append('\n');
            appendItem(a, in2, "expression").append(expression).append('\n');
        }
    }

    /**
     * Query plan node which filters out rows.
     */
    public static final class Filter extends QueryPlan {
        private static final long serialVersionUID = 1L;

        public final String expression;
        public final QueryPlan source;

        /**
         * @param expression filter expression
         * @param source child plan node
         */
        public Filter(String expression, QueryPlan source) {
            this.expression = expression;
            this.source = source;
        }

        @Override
        void appendTo(Appendable a, String in1, String in2) throws IOException {
            a.append(in1).append("filter").append(": ").append(expression).append('\n');
            in2 += "  ";
            source.appendTo(a, in2, in2);
        }
    }

    /**
     * Query plan node which joins rows to another table by a fully specified key.
     */
    public static final class NaturalJoin extends Table {
        private static final long serialVersionUID = 1L;

        public final QueryPlan source;

        /**
         * @param which primary key, alternate key, or secondary index
         * @param keyColumns columns with '+' or '-' prefix
         * @param source child plan node
         */
        public NaturalJoin(String table, String which, String[] keyColumns, QueryPlan source) {
            super(table, which, keyColumns);
            this.source = source;
        }

        @Override
        void appendTo(Appendable a, String in1, String in2) throws IOException {
            a.append(in1).append("natural join to ").append(which)
                .append(": ").append(table).append('\n');
            appendKeyColumns(a, in2).append('\n');
            in2 += "  ";
            source.appendTo(a, in2, in2);
        }
    }

    /**
     * Query plan node which represents a set of plans.
     */
    public static abstract sealed class Set extends QueryPlan {
        public final QueryPlan[] sources;

        /**
         * @param sources child plan nodes
         */
        public Set(QueryPlan... sources) {
            this.sources = sources;
        }

        void appendTo(Appendable a, String in1, String in2, String title) throws IOException {
            a.append(in1).append(title).append('\n');
            String subIn2 = null;
            for (int i=0; i<sources.length; i++) {
                String subIn1 = in2 + (i + 1) + ": ";
                if (subIn2 == null || subIn2.length() < subIn1.length()) {
                    subIn2 = " ".repeat(subIn1.length());
                }
                sources[i].appendTo(a, subIn1, subIn2);
            }
        }
    }

    /**
     * Query plan node which represents an empty set.
     */
    public static final class Empty extends Set {
        public Empty() {
        }

        @Override
        void appendTo(Appendable a, String in1, String in2) throws IOException {
            appendTo(a, in1, in2, "empty");
        }
    }

    /**
     * Query plan node which represents a set of plans executed sequentially.
     */
    public static final class Sequence extends Set {
        private static final long serialVersionUID = 1L;

        /**
         * @param sources child plan nodes
         */
        public Sequence(QueryPlan... sources) {
            super(sources);
        }

        @Override
        void appendTo(Appendable a, String in1, String in2) throws IOException {
            appendTo(a, in1, in2, "sequence");
        }
    }

    /**
     * Query plan node which represents a union set of plans.
     */
    public static final class Union extends Set {
        private static final long serialVersionUID = 1L;

        /**
         * @param sources child plan nodes
         */
        public Union(QueryPlan... sources) {
            super(sources);
        }

        @Override
        void appendTo(Appendable a, String in1, String in2) throws IOException {
            appendTo(a, in1, in2, "union");
        }
    }
}
