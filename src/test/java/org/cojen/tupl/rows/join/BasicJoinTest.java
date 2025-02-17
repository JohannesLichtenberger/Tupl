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

import org.junit.*;
import static org.junit.Assert.*;

import org.cojen.tupl.*;

/**
 * 
 *
 * @author Brian S O'Neill
 */
public class BasicJoinTest {
    public static void main(String[] args) throws Exception {
        org.junit.runner.JUnitCore.main(BasicJoinTest.class.getName());
    }

    private static Database mDb;
    private static Table<Department> mDepartment;
    private static Table<Employee> mEmployee;

    private Table mJoin;

    @BeforeClass
    public static void setupAll() throws Exception {
        mDb = Database.open(new DatabaseConfig());
        mDepartment = mDb.openTable(Department.class);
        mEmployee = mDb.openTable(Employee.class);
        Filler.fillCompany(null, mDepartment, mEmployee);
    }

    @AfterClass
    public static void teardownAll() throws Exception {
        if (mDb != null) {
            mDb.close();
            mDb = null;
        }
        mDepartment = null;
        mEmployee = null;
    }

    @After
    public void teardown() {
        mJoin = null;
    }

    @Test
    public void crossJoin() throws Exception {
        join("department : employee");

        var plan = """
            - nested loops join
              - first
                - full scan over primary key: org.cojen.tupl.rows.join.Department
                  key columns: +id
              - join
                - full scan over primary key: org.cojen.tupl.rows.join.Employee
                  key columns: +id
            """;

        var results = new String[] {
            "{department={id=31, companyId=1, name=Sales}, employee={departmentId=31, country=Australia, lastName=Rafferty}}",
            "{department={id=31, companyId=1, name=Sales}, employee={departmentId=33, country=Australia, lastName=Jones}}",
            "{department={id=31, companyId=1, name=Sales}, employee={departmentId=33, country=Australia, lastName=Heisenberg}}",
            "{department={id=31, companyId=1, name=Sales}, employee={departmentId=34, country=United States, lastName=Robinson}}",
            "{department={id=31, companyId=1, name=Sales}, employee={departmentId=34, country=Germany, lastName=Smith}}",
            "{department={id=31, companyId=1, name=Sales}, employee={departmentId=null, country=Germany, lastName=Williams}}",
            "{department={id=33, companyId=2, name=Engineering}, employee={departmentId=31, country=Australia, lastName=Rafferty}}",
            "{department={id=33, companyId=2, name=Engineering}, employee={departmentId=33, country=Australia, lastName=Jones}}",
            "{department={id=33, companyId=2, name=Engineering}, employee={departmentId=33, country=Australia, lastName=Heisenberg}}",
            "{department={id=33, companyId=2, name=Engineering}, employee={departmentId=34, country=United States, lastName=Robinson}}",
            "{department={id=33, companyId=2, name=Engineering}, employee={departmentId=34, country=Germany, lastName=Smith}}",
            "{department={id=33, companyId=2, name=Engineering}, employee={departmentId=null, country=Germany, lastName=Williams}}",
            "{department={id=34, companyId=1, name=Clerical}, employee={departmentId=31, country=Australia, lastName=Rafferty}}",
            "{department={id=34, companyId=1, name=Clerical}, employee={departmentId=33, country=Australia, lastName=Jones}}",
            "{department={id=34, companyId=1, name=Clerical}, employee={departmentId=33, country=Australia, lastName=Heisenberg}}",
            "{department={id=34, companyId=1, name=Clerical}, employee={departmentId=34, country=United States, lastName=Robinson}}",
            "{department={id=34, companyId=1, name=Clerical}, employee={departmentId=34, country=Germany, lastName=Smith}}",
            "{department={id=34, companyId=1, name=Clerical}, employee={departmentId=null, country=Germany, lastName=Williams}}",
            "{department={id=35, companyId=2, name=Marketing}, employee={departmentId=31, country=Australia, lastName=Rafferty}}",
            "{department={id=35, companyId=2, name=Marketing}, employee={departmentId=33, country=Australia, lastName=Jones}}",
            "{department={id=35, companyId=2, name=Marketing}, employee={departmentId=33, country=Australia, lastName=Heisenberg}}",
            "{department={id=35, companyId=2, name=Marketing}, employee={departmentId=34, country=United States, lastName=Robinson}}",
            "{department={id=35, companyId=2, name=Marketing}, employee={departmentId=34, country=Germany, lastName=Smith}}",
            "{department={id=35, companyId=2, name=Marketing}, employee={departmentId=null, country=Germany, lastName=Williams}}",
        };

        eval(plan, results, "{*}");
    }

    @Test
    public void innerJoin() throws Exception {
        join("employee : department");

        var plan = """
            - nested loops join
              - first
                - full scan over primary key: org.cojen.tupl.rows.join.Employee
                  key columns: +id
                assignments: ?1 = employee.departmentId
              - join
                - load one using primary key: org.cojen.tupl.rows.join.Department
                  key columns: +id
                  filter: id == ?1
            """;

        var results = new String[] {
            "{department={id=31, companyId=1, name=Sales}, employee={departmentId=31, country=Australia, lastName=Rafferty}}",
            "{department={id=33, companyId=2, name=Engineering}, employee={departmentId=33, country=Australia, lastName=Jones}}",
            "{department={id=33, companyId=2, name=Engineering}, employee={departmentId=33, country=Australia, lastName=Heisenberg}}",
            "{department={id=34, companyId=1, name=Clerical}, employee={departmentId=34, country=United States, lastName=Robinson}}",
            "{department={id=34, companyId=1, name=Clerical}, employee={departmentId=34, country=Germany, lastName=Smith}}",
        };

        eval(plan, results, "department.id == employee.departmentId");
        eval(plan, results, "employee.departmentId == department.id");
        eval(plan, results, "employee.departmentId == department.id" +
             " && department.id == employee.departmentId");

        // Should still loop over employee first, because the overall plan is better.
        join("department : employee");

        eval(plan, results, "department.id == employee.departmentId");
        eval(plan, results, "employee.departmentId == department.id");

        join("employee :: department");

        eval(plan, results, "department.id == employee.departmentId");
        eval(plan, results, "employee.departmentId == department.id");

        // Force a different join order.
        join("department :: employee");

        plan = """
            - nested loops join
              - first
                - full scan over primary key: org.cojen.tupl.rows.join.Department
                  key columns: +id
                assignments: ?1 = department.id
              - join
                - filter: departmentId == ?1
                  - full scan over primary key: org.cojen.tupl.rows.join.Employee
                    key columns: +id
            """;

        eval(plan, results, "department.id == employee.departmentId");
        eval(plan, results, "employee.departmentId == department.id");
    }

    @Test
    public void leftOuterJoin() throws Exception {
        join("employee >: department");

        var plan = """
            - nested loops join
              - first
                - full scan over primary key: org.cojen.tupl.rows.join.Employee
                  key columns: +id
                assignments: ?1 = employee.departmentId
              - outer join
                - load one using primary key: org.cojen.tupl.rows.join.Department
                  key columns: +id
                  filter: id == ?1
            """;

        var results = new String[] {
            "{department={id=31, companyId=1, name=Sales}, employee={departmentId=31, country=Australia, lastName=Rafferty}}",
            "{department={id=33, companyId=2, name=Engineering}, employee={departmentId=33, country=Australia, lastName=Jones}}",
            "{department={id=33, companyId=2, name=Engineering}, employee={departmentId=33, country=Australia, lastName=Heisenberg}}",
            "{department={id=34, companyId=1, name=Clerical}, employee={departmentId=34, country=United States, lastName=Robinson}}",
            "{department={id=34, companyId=1, name=Clerical}, employee={departmentId=34, country=Germany, lastName=Smith}}",
            "{department=null, employee={departmentId=null, country=Germany, lastName=Williams}}",
        };

        eval(plan, results, "department.id == employee.departmentId");
        eval(plan, results, "employee.departmentId == department.id");

        join("department >: employee");

        plan = """
            - nested loops join
              - first
                - full scan over primary key: org.cojen.tupl.rows.join.Department
                  key columns: +id
                assignments: ?1 = department.id
              - outer join
                - filter: departmentId == ?1
                  - full scan over primary key: org.cojen.tupl.rows.join.Employee
                    key columns: +id
            """;

        results = new String[] {
            "{department={id=31, companyId=1, name=Sales}, employee={departmentId=31, country=Australia, lastName=Rafferty}}",
            "{department={id=33, companyId=2, name=Engineering}, employee={departmentId=33, country=Australia, lastName=Jones}}",
            "{department={id=33, companyId=2, name=Engineering}, employee={departmentId=33, country=Australia, lastName=Heisenberg}}",
            "{department={id=34, companyId=1, name=Clerical}, employee={departmentId=34, country=United States, lastName=Robinson}}",
            "{department={id=34, companyId=1, name=Clerical}, employee={departmentId=34, country=Germany, lastName=Smith}}",
            "{department={id=35, companyId=2, name=Marketing}, employee=null}",
        };

        eval(plan, results, "department.id == employee.departmentId");
        eval(plan, results, "employee.departmentId == department.id");
    }

    @Test
    public void rightOuterJoin() throws Exception {
        join("employee :< department");

        var plan = """
            - nested loops join
              - first
                - full scan over primary key: org.cojen.tupl.rows.join.Department
                  key columns: +id
                assignments: ?1 = department.id
              - outer join
                - filter: departmentId == ?1
                  - full scan over primary key: org.cojen.tupl.rows.join.Employee
                    key columns: +id
            """;

        var results = new String[] {
            "{department={id=31, companyId=1, name=Sales}, employee={departmentId=31, country=Australia, lastName=Rafferty}}",
            "{department={id=33, companyId=2, name=Engineering}, employee={departmentId=33, country=Australia, lastName=Jones}}",
            "{department={id=33, companyId=2, name=Engineering}, employee={departmentId=33, country=Australia, lastName=Heisenberg}}",
            "{department={id=34, companyId=1, name=Clerical}, employee={departmentId=34, country=United States, lastName=Robinson}}",
            "{department={id=34, companyId=1, name=Clerical}, employee={departmentId=34, country=Germany, lastName=Smith}}",
            "{department={id=35, companyId=2, name=Marketing}, employee=null}",
        };

        eval(plan, results, "department.id == employee.departmentId");
        eval(plan, results, "employee.departmentId == department.id");

        join("department :< employee");

        plan = """
            - nested loops join
              - first
                - full scan over primary key: org.cojen.tupl.rows.join.Employee
                  key columns: +id
                assignments: ?1 = employee.departmentId
              - outer join
                - load one using primary key: org.cojen.tupl.rows.join.Department
                  key columns: +id
                  filter: id == ?1
            """;

        results = new String[] {
            "{department={id=31, companyId=1, name=Sales}, employee={departmentId=31, country=Australia, lastName=Rafferty}}",
            "{department={id=33, companyId=2, name=Engineering}, employee={departmentId=33, country=Australia, lastName=Jones}}",
            "{department={id=33, companyId=2, name=Engineering}, employee={departmentId=33, country=Australia, lastName=Heisenberg}}",
            "{department={id=34, companyId=1, name=Clerical}, employee={departmentId=34, country=United States, lastName=Robinson}}",
            "{department={id=34, companyId=1, name=Clerical}, employee={departmentId=34, country=Germany, lastName=Smith}}",
            "{department=null, employee={departmentId=null, country=Germany, lastName=Williams}}",
        };

        eval(plan, results, "department.id == employee.departmentId");
        eval(plan, results, "employee.departmentId == department.id");
    }

    @Test
    public void fullOuterJoin() throws Exception {
        join("employee >:< department");

        var plan = """
            - disjoint union
              - nested loops join
                - first
                  - full scan over primary key: org.cojen.tupl.rows.join.Employee
                    key columns: +id
                  assignments: ?1 = employee.departmentId
                - outer join
                  - load one using primary key: org.cojen.tupl.rows.join.Department
                    key columns: +id
                    filter: id == ?1
              - nested loops join
                - first
                  - full scan over primary key: org.cojen.tupl.rows.join.Department
                    key columns: +id
                  assignments: ?1 = department.id
                - anti join
                  - exists
                    - filter: departmentId == ?1
                      - full scan over primary key: org.cojen.tupl.rows.join.Employee
                        key columns: +id
            """;

        var results = new String[] {
            "{department={id=31, companyId=1, name=Sales}, employee={departmentId=31, country=Australia, lastName=Rafferty}}",
            "{department={id=33, companyId=2, name=Engineering}, employee={departmentId=33, country=Australia, lastName=Jones}}",
            "{department={id=33, companyId=2, name=Engineering}, employee={departmentId=33, country=Australia, lastName=Heisenberg}}",
            "{department={id=34, companyId=1, name=Clerical}, employee={departmentId=34, country=United States, lastName=Robinson}}",
            "{department={id=34, companyId=1, name=Clerical}, employee={departmentId=34, country=Germany, lastName=Smith}}",
            "{department=null, employee={departmentId=null, country=Germany, lastName=Williams}}",
            "{department={id=35, companyId=2, name=Marketing}, employee=null}",
        };

        eval(plan, results, "department.id == employee.departmentId");
        eval(plan, results, "employee.departmentId == department.id");

        join("department >:< employee");

        plan = """
            - disjoint union
              - nested loops join
                - first
                  - full scan over primary key: org.cojen.tupl.rows.join.Department
                    key columns: +id
                  assignments: ?1 = department.id
                - outer join
                  - filter: departmentId == ?1
                    - full scan over primary key: org.cojen.tupl.rows.join.Employee
                      key columns: +id
              - nested loops join
                - first
                  - full scan over primary key: org.cojen.tupl.rows.join.Employee
                    key columns: +id
                  assignments: ?1 = employee.departmentId
                - anti join
                  - exists
                    - load one using primary key: org.cojen.tupl.rows.join.Department
                      key columns: +id
                      filter: id == ?1
            """;

        results = new String[] {
            "{department={id=31, companyId=1, name=Sales}, employee={departmentId=31, country=Australia, lastName=Rafferty}}",
            "{department={id=33, companyId=2, name=Engineering}, employee={departmentId=33, country=Australia, lastName=Jones}}",
            "{department={id=33, companyId=2, name=Engineering}, employee={departmentId=33, country=Australia, lastName=Heisenberg}}",
            "{department={id=34, companyId=1, name=Clerical}, employee={departmentId=34, country=United States, lastName=Robinson}}",
            "{department={id=34, companyId=1, name=Clerical}, employee={departmentId=34, country=Germany, lastName=Smith}}",
            "{department={id=35, companyId=2, name=Marketing}, employee=null}",
            "{department=null, employee={departmentId=null, country=Germany, lastName=Williams}}",
        };

        eval(plan, results, "department.id == employee.departmentId");
        eval(plan, results, "employee.departmentId == department.id");
    }

    @Test
    public void filterCrossJoin() throws Exception {
        join("employee : department");

        var plan = """
            - nested loops join
              - first
                - load one using primary key: org.cojen.tupl.rows.join.Department
                  key columns: +id
                  filter: id == ?1
              - join
                - full scan over primary key: org.cojen.tupl.rows.join.Employee
                  key columns: +id
            """;

        var results = new String[] {
            "{department={id=31, companyId=1, name=Sales}, employee={departmentId=31, country=Australia, lastName=Rafferty}}",
            "{department={id=31, companyId=1, name=Sales}, employee={departmentId=33, country=Australia, lastName=Jones}}",
            "{department={id=31, companyId=1, name=Sales}, employee={departmentId=33, country=Australia, lastName=Heisenberg}}",
            "{department={id=31, companyId=1, name=Sales}, employee={departmentId=34, country=United States, lastName=Robinson}}",
            "{department={id=31, companyId=1, name=Sales}, employee={departmentId=34, country=Germany, lastName=Smith}}",
            "{department={id=31, companyId=1, name=Sales}, employee={departmentId=null, country=Germany, lastName=Williams}}",
        };

        eval(plan, results, "department.id == ?", 31);

        plan = """
            - nested loops join
              - first
                - load one using primary key: org.cojen.tupl.rows.join.Employee
                  key columns: +id
                  filter: id == ?1
              - join
                - full scan over primary key: org.cojen.tupl.rows.join.Department
                  key columns: +id
            """;

        results = new String[] { };

        eval(plan, results, "employee.id == ?", 999);
    }

    @Test
    public void filterInnerJoin() throws Exception {
        join("employee : department");

        var plan = """
            - nested loops join
              - first
                - load one using primary key: org.cojen.tupl.rows.join.Department
                  key columns: +id
                  filter: id == ?1
                assignments: ?2 = department.id
              - join
                - filter: departmentId == ?2
                  - full scan over primary key: org.cojen.tupl.rows.join.Employee
                    key columns: +id
            """;

        var results = new String[] {
            "{department={id=34, companyId=1, name=Clerical}, employee={departmentId=34, country=United States, lastName=Robinson}}",
            "{department={id=34, companyId=1, name=Clerical}, employee={departmentId=34, country=Germany, lastName=Smith}}",
        };
 
        eval(plan, results, "department.id == employee.departmentId && department.id == ?", 34);

        // This next one has a broken join specification.

        // TODO: The range scans should instead be LoadOne.

        plan = """
            - nested loops join
              - first
                - full scan over primary key: org.cojen.tupl.rows.join.Employee
                  key columns: +id
                assignments: ?2 = employee.departmentId
              - join
                - range union
                  - range scan over primary key: org.cojen.tupl.rows.join.Department
                    key columns: +id
                    range: id >= ?2 .. id <= ?2
                  - range scan over primary key: org.cojen.tupl.rows.join.Department
                    key columns: +id
                    range: id >= ?1 .. id <= ?1
            """;

        results = new String[] {
            "{department={id=31, companyId=1, name=Sales}, employee={departmentId=31, country=Australia, lastName=Rafferty}}",
            "{department={id=34, companyId=1, name=Clerical}, employee={departmentId=31, country=Australia, lastName=Rafferty}}",
            "{department={id=33, companyId=2, name=Engineering}, employee={departmentId=33, country=Australia, lastName=Jones}}",
            "{department={id=34, companyId=1, name=Clerical}, employee={departmentId=33, country=Australia, lastName=Jones}}",
            "{department={id=33, companyId=2, name=Engineering}, employee={departmentId=33, country=Australia, lastName=Heisenberg}}",
            "{department={id=34, companyId=1, name=Clerical}, employee={departmentId=33, country=Australia, lastName=Heisenberg}}",
            "{department={id=34, companyId=1, name=Clerical}, employee={departmentId=34, country=United States, lastName=Robinson}}",
            "{department={id=34, companyId=1, name=Clerical}, employee={departmentId=34, country=Germany, lastName=Smith}}",
            "{department={id=34, companyId=1, name=Clerical}, employee={departmentId=null, country=Germany, lastName=Williams}}",
        };

        eval(plan, results, "employee.departmentId == department.id || department.id == ?", 34);
    }

    @Test
    public void filterLeftOuterJoin() throws Exception {
        join("employee >: department");

        var plan = """
            - nested loops join
              - first
                - full scan over primary key: org.cojen.tupl.rows.join.Employee
                  key columns: +id
                assignments: ?2 = employee.departmentId
              - outer join
                - filter: id == ?1
                  - load one using primary key: org.cojen.tupl.rows.join.Department
                    key columns: +id
                    filter: id == ?2
            """;

        var results = new String[] {
            "{department=null, employee={departmentId=31, country=Australia, lastName=Rafferty}}",
            "{department=null, employee={departmentId=33, country=Australia, lastName=Jones}}",
            "{department=null, employee={departmentId=33, country=Australia, lastName=Heisenberg}}",
            "{department={id=34, companyId=1, name=Clerical}, employee={departmentId=34, country=United States, lastName=Robinson}}",
            "{department={id=34, companyId=1, name=Clerical}, employee={departmentId=34, country=Germany, lastName=Smith}}",
            "{department=null, employee={departmentId=null, country=Germany, lastName=Williams}}",
        };

        eval(plan, results, "department.id == employee.departmentId && department.id == ?", 34);

        // This next one has a broken join specification.

        // TODO: The range scans should instead be LoadOne.

        plan = """
            - nested loops join
              - first
                - full scan over primary key: org.cojen.tupl.rows.join.Employee
                  key columns: +id
                assignments: ?2 = employee.departmentId
              - outer join
                - range union
                  - range scan over primary key: org.cojen.tupl.rows.join.Department
                    key columns: +id
                    range: id >= ?2 .. id <= ?2
                  - range scan over primary key: org.cojen.tupl.rows.join.Department
                    key columns: +id
                    range: id >= ?1 .. id <= ?1
            """;

        results = new String[] {
            "{department={id=31, companyId=1, name=Sales}, employee={departmentId=31, country=Australia, lastName=Rafferty}}",
            "{department={id=34, companyId=1, name=Clerical}, employee={departmentId=31, country=Australia, lastName=Rafferty}}",
            "{department={id=33, companyId=2, name=Engineering}, employee={departmentId=33, country=Australia, lastName=Jones}}",
            "{department={id=34, companyId=1, name=Clerical}, employee={departmentId=33, country=Australia, lastName=Jones}}",
            "{department={id=33, companyId=2, name=Engineering}, employee={departmentId=33, country=Australia, lastName=Heisenberg}}",
            "{department={id=34, companyId=1, name=Clerical}, employee={departmentId=33, country=Australia, lastName=Heisenberg}}",
            "{department={id=34, companyId=1, name=Clerical}, employee={departmentId=34, country=United States, lastName=Robinson}}",
            "{department={id=34, companyId=1, name=Clerical}, employee={departmentId=34, country=Germany, lastName=Smith}}",
            "{department={id=34, companyId=1, name=Clerical}, employee={departmentId=null, country=Germany, lastName=Williams}}",
        };

        eval(plan, results, "employee.departmentId == department.id || department.id == ?", 34);
    }

    @Test
    public void filterFullOuterJoin() throws Exception {
        join("employee >:< department");

        var plan = """
            - disjoint union
              - nested loops join
                - first
                  - full scan over primary key: org.cojen.tupl.rows.join.Employee
                    key columns: +id
                  assignments: ?2 = employee.departmentId
                - outer join
                  - filter: id == ?1
                    - load one using primary key: org.cojen.tupl.rows.join.Department
                      key columns: +id
                      filter: id == ?2
              - nested loops join
                - first
                  - load one using primary key: org.cojen.tupl.rows.join.Department
                    key columns: +id
                    filter: id == ?1
                  assignments: ?2 = department.id
                - anti join
                  - exists
                    - filter: departmentId == ?2
                      - full scan over primary key: org.cojen.tupl.rows.join.Employee
                        key columns: +id
            """;

        var results = new String[] {
            "{department=null, employee={departmentId=31, country=Australia, lastName=Rafferty}}",
            "{department=null, employee={departmentId=33, country=Australia, lastName=Jones}}",
            "{department=null, employee={departmentId=33, country=Australia, lastName=Heisenberg}}",
            "{department={id=34, companyId=1, name=Clerical}, employee={departmentId=34, country=United States, lastName=Robinson}}",
            "{department={id=34, companyId=1, name=Clerical}, employee={departmentId=34, country=Germany, lastName=Smith}}",
            "{department=null, employee={departmentId=null, country=Germany, lastName=Williams}}",
        };

        eval(plan, results, "department.id == employee.departmentId && department.id == ?", 34);

        join("department >:< employee");

        plan = """
            - disjoint union
              - nested loops join
                - first
                  - load one using primary key: org.cojen.tupl.rows.join.Department
                    key columns: +id
                    filter: id == ?1
                  assignments: ?2 = department.id
                - outer join
                  - filter: departmentId == ?2
                    - full scan over primary key: org.cojen.tupl.rows.join.Employee
                      key columns: +id
              - nested loops join
                - first
                  - full scan over primary key: org.cojen.tupl.rows.join.Employee
                    key columns: +id
                  assignments: ?2 = employee.departmentId
                - anti join
                  - exists
                    - filter: id == ?1
                      - load one using primary key: org.cojen.tupl.rows.join.Department
                        key columns: +id
                        filter: id == ?2
            """;

        results = new String[] {
            "{department={id=34, companyId=1, name=Clerical}, employee={departmentId=34, country=United States, lastName=Robinson}}",
            "{department={id=34, companyId=1, name=Clerical}, employee={departmentId=34, country=Germany, lastName=Smith}}",
            "{department=null, employee={departmentId=31, country=Australia, lastName=Rafferty}}",
            "{department=null, employee={departmentId=33, country=Australia, lastName=Jones}}",
            "{department=null, employee={departmentId=33, country=Australia, lastName=Heisenberg}}",
            "{department=null, employee={departmentId=null, country=Germany, lastName=Williams}}",
        };

        eval(plan, results, "department.id == employee.departmentId && department.id == ?", 34);

        // This next one has a broken join specification.

        // TODO: The range scans should instead be LoadOne.

        plan = """
            - disjoint union
              - filter: department.id == employee.departmentId || department.id == ?1
                - nested loops join
                  - first
                    - full scan over primary key: org.cojen.tupl.rows.join.Department
                      key columns: +id
                  - outer join
                    - full scan over primary key: org.cojen.tupl.rows.join.Employee
                      key columns: +id
              - nested loops join
                - first
                  - full scan over primary key: org.cojen.tupl.rows.join.Employee
                    key columns: +id
                  assignments: ?2 = employee.departmentId
                - anti join
                  - exists
                    - range union
                      - range scan over primary key: org.cojen.tupl.rows.join.Department
                        key columns: +id
                        range: id >= ?2 .. id <= ?2
                      - range scan over primary key: org.cojen.tupl.rows.join.Department
                        key columns: +id
                        range: id >= ?1 .. id <= ?1
            """;

        results = new String[] {
            "{department={id=31, companyId=1, name=Sales}, employee={departmentId=31, country=Australia, lastName=Rafferty}}",
            "{department={id=33, companyId=2, name=Engineering}, employee={departmentId=33, country=Australia, lastName=Jones}}",
            "{department={id=33, companyId=2, name=Engineering}, employee={departmentId=33, country=Australia, lastName=Heisenberg}}",
            "{department={id=34, companyId=1, name=Clerical}, employee={departmentId=31, country=Australia, lastName=Rafferty}}",
            "{department={id=34, companyId=1, name=Clerical}, employee={departmentId=33, country=Australia, lastName=Jones}}",
            "{department={id=34, companyId=1, name=Clerical}, employee={departmentId=33, country=Australia, lastName=Heisenberg}}",
            "{department={id=34, companyId=1, name=Clerical}, employee={departmentId=34, country=United States, lastName=Robinson}}",
            "{department={id=34, companyId=1, name=Clerical}, employee={departmentId=34, country=Germany, lastName=Smith}}",
            "{department={id=34, companyId=1, name=Clerical}, employee={departmentId=null, country=Germany, lastName=Williams}}",
        };

        eval(plan, results, "department.id == employee.departmentId || department.id == ?", 34);
    }

    @Test
    public void misc() throws Exception {
        join("employee : department");

        // TODO: Should apply the filter before loading by primary key. Or should see the
        // equijoin and move the filter to an earlier level.

        String plan = """
            - nested loops join
              - first
                - filter: employee.id != employee.lastName
                  - full scan over primary key: org.cojen.tupl.rows.join.Employee
                    key columns: +id
                assignments: ?2 = employee.departmentId
              - join
                - filter: id != ?1
                  - load one using primary key: org.cojen.tupl.rows.join.Department
                    key columns: +id
                    filter: id == ?2
            """;

        var results = new String[] {
            "{department={id=33, companyId=2, name=Engineering}, employee={departmentId=33, country=Australia, lastName=Jones}}",
            "{department={id=33, companyId=2, name=Engineering}, employee={departmentId=33, country=Australia, lastName=Heisenberg}}",
            "{department={id=34, companyId=1, name=Clerical}, employee={departmentId=34, country=United States, lastName=Robinson}}",
            "{department={id=34, companyId=1, name=Clerical}, employee={departmentId=34, country=Germany, lastName=Smith}}",
        };

        eval(plan, results,
             "employee.departmentId == department.id " +
             "&& employee.id != employee.lastName " +
             "&& department.id != ?1", 31);

        plan = """
            - nested loops join
              - first
                - filter: employee.id != employee.lastName
                  - full scan over primary key: org.cojen.tupl.rows.join.Employee
                    key columns: +id
              - join
                - filter: id != ?1
                  - full scan over primary key: org.cojen.tupl.rows.join.Department
                    key columns: +id
            """;

        results = new String[] {
            "{department={id=33, companyId=2, name=Engineering}, employee={departmentId=31, country=Australia, lastName=Rafferty}}",
            "{department={id=34, companyId=1, name=Clerical}, employee={departmentId=31, country=Australia, lastName=Rafferty}}",
            "{department={id=35, companyId=2, name=Marketing}, employee={departmentId=31, country=Australia, lastName=Rafferty}}",
            "{department={id=33, companyId=2, name=Engineering}, employee={departmentId=33, country=Australia, lastName=Jones}}",
            "{department={id=34, companyId=1, name=Clerical}, employee={departmentId=33, country=Australia, lastName=Jones}}",
            "{department={id=35, companyId=2, name=Marketing}, employee={departmentId=33, country=Australia, lastName=Jones}}",
            "{department={id=33, companyId=2, name=Engineering}, employee={departmentId=33, country=Australia, lastName=Heisenberg}}",
            "{department={id=34, companyId=1, name=Clerical}, employee={departmentId=33, country=Australia, lastName=Heisenberg}}",
            "{department={id=35, companyId=2, name=Marketing}, employee={departmentId=33, country=Australia, lastName=Heisenberg}}",
            "{department={id=33, companyId=2, name=Engineering}, employee={departmentId=34, country=United States, lastName=Robinson}}",
            "{department={id=34, companyId=1, name=Clerical}, employee={departmentId=34, country=United States, lastName=Robinson}}",
            "{department={id=35, companyId=2, name=Marketing}, employee={departmentId=34, country=United States, lastName=Robinson}}",
            "{department={id=33, companyId=2, name=Engineering}, employee={departmentId=34, country=Germany, lastName=Smith}}",
            "{department={id=34, companyId=1, name=Clerical}, employee={departmentId=34, country=Germany, lastName=Smith}}",
            "{department={id=35, companyId=2, name=Marketing}, employee={departmentId=34, country=Germany, lastName=Smith}}",
            "{department={id=33, companyId=2, name=Engineering}, employee={departmentId=null, country=Germany, lastName=Williams}}",
            "{department={id=34, companyId=1, name=Clerical}, employee={departmentId=null, country=Germany, lastName=Williams}}",
            "{department={id=35, companyId=2, name=Marketing}, employee={departmentId=null, country=Germany, lastName=Williams}}",
        };

        eval(plan, results, "employee.id != employee.lastName && department.id != ?", 31);

        plan = """
            - filter: (employee.departmentId == department.id && employee.id != employee.lastName) || department.name == ?1
              - nested loops join
                - first
                  - full scan over primary key: org.cojen.tupl.rows.join.Employee
                    key columns: +id
                - join
                  - full scan over primary key: org.cojen.tupl.rows.join.Department
                    key columns: +id
            """;
        results = new String[] {
            "{department={id=31, companyId=1, name=Sales}, employee={departmentId=31, country=Australia, lastName=Rafferty}}",
            "{department={id=31, companyId=1, name=Sales}, employee={departmentId=33, country=Australia, lastName=Jones}}",
            "{department={id=33, companyId=2, name=Engineering}, employee={departmentId=33, country=Australia, lastName=Jones}}",
            "{department={id=31, companyId=1, name=Sales}, employee={departmentId=33, country=Australia, lastName=Heisenberg}}",
            "{department={id=33, companyId=2, name=Engineering}, employee={departmentId=33, country=Australia, lastName=Heisenberg}}",
            "{department={id=31, companyId=1, name=Sales}, employee={departmentId=34, country=United States, lastName=Robinson}}",
            "{department={id=34, companyId=1, name=Clerical}, employee={departmentId=34, country=United States, lastName=Robinson}}",
            "{department={id=31, companyId=1, name=Sales}, employee={departmentId=34, country=Germany, lastName=Smith}}",
            "{department={id=34, companyId=1, name=Clerical}, employee={departmentId=34, country=Germany, lastName=Smith}}",
            "{department={id=31, companyId=1, name=Sales}, employee={departmentId=null, country=Germany, lastName=Williams}}",
        };
            
        eval(plan, results,
             "(employee.departmentId == department.id" +
             " && employee.id != employee.lastName)" +
             " || department.name == ?", "Sales");

        plan = """
            - filter: (employee.departmentId == department.id && employee.id != employee.lastName) || department.name == ?2
              - nested loops join
                - first
                  - filter: id != ?1 || name == ?2
                    - full scan over primary key: org.cojen.tupl.rows.join.Department
                      key columns: +id
                - join
                  - full scan over primary key: org.cojen.tupl.rows.join.Employee
                    key columns: +id
            """;

        results = new String[] {
            "{department={id=31, companyId=1, name=Sales}, employee={departmentId=31, country=Australia, lastName=Rafferty}}",
            "{department={id=31, companyId=1, name=Sales}, employee={departmentId=33, country=Australia, lastName=Jones}}",
            "{department={id=31, companyId=1, name=Sales}, employee={departmentId=33, country=Australia, lastName=Heisenberg}}",
            "{department={id=31, companyId=1, name=Sales}, employee={departmentId=34, country=United States, lastName=Robinson}}",
            "{department={id=31, companyId=1, name=Sales}, employee={departmentId=34, country=Germany, lastName=Smith}}",
            "{department={id=31, companyId=1, name=Sales}, employee={departmentId=null, country=Germany, lastName=Williams}}",
            "{department={id=33, companyId=2, name=Engineering}, employee={departmentId=33, country=Australia, lastName=Jones}}",
            "{department={id=33, companyId=2, name=Engineering}, employee={departmentId=33, country=Australia, lastName=Heisenberg}}",
            "{department={id=34, companyId=1, name=Clerical}, employee={departmentId=34, country=United States, lastName=Robinson}}",
            "{department={id=34, companyId=1, name=Clerical}, employee={departmentId=34, country=Germany, lastName=Smith}}",
        };

        eval(plan, results,
             "(employee.departmentId == department.id" +
             " && employee.id != employee.lastName" +
             " && department.id != ?) || department.name == ?", 31, "Sales");
    }

    @Test
    public void selfJoin() throws Exception {
        mJoin = mDb.openJoinTable(EmployeeJoinEmployee.class, "first : second");

        String plan = """
            - nested loops join
              - first
                - full scan over primary key: org.cojen.tupl.rows.join.Employee
                  key columns: +id
                assignments: ?1 = first.country, ?2 = first.id
              - join
                - filter: country == ?1
                  - range scan over primary key: org.cojen.tupl.rows.join.Employee
                    key columns: +id
                    range: id > ?2 ..
            """;

        var results = new String[] {
            "{first={departmentId=31, country=Australia, lastName=Rafferty}, second={departmentId=33, country=Australia, lastName=Jones}}",
            "{first={departmentId=31, country=Australia, lastName=Rafferty}, second={departmentId=33, country=Australia, lastName=Heisenberg}}",
            "{first={departmentId=33, country=Australia, lastName=Jones}, second={departmentId=33, country=Australia, lastName=Heisenberg}}",
            "{first={departmentId=34, country=Germany, lastName=Smith}, second={departmentId=null, country=Germany, lastName=Williams}}",
        };

        eval(plan, results, "first.country == second.country && first.id < second.id");
    }

    private void join(String spec) throws Exception {
        mJoin = mDb.openJoinTable(EmployeeJoinDepartment.class, spec);
    }

    @SuppressWarnings("unchecked")
    private void eval(String plan, String[] results, String query, Object... args)
        throws Exception 
    {
        String actualPlan = mJoin.scannerPlan(null, query, args).toString();
        assertEquals(plan, actualPlan);

        int resultNum = 0;

        try (var scanner = mJoin.newScanner(null, query, args)) {
            for (var row = scanner.row(); row != null; row = scanner.step(row)) {
                String result = results[resultNum++];
                String actualResult = row.toString();
                assertEquals(result, actualResult);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void dump(String query, Object... args) throws Exception {
        System.out.println(query);
        System.out.println(mJoin.scannerPlan(null, query, args));

        try (var scanner = mJoin.newScanner(null, query, args)) {
            for (var row = scanner.row(); row != null; row = scanner.step(row)) {
                System.out.println("\"" + row + "\",");
            }
        }
    }
}
