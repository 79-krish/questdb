/*******************************************************************************
 *     ___                  _   ____  ____
 *    / _ \ _   _  ___  ___| |_|  _ \| __ )
 *   | | | | | | |/ _ \/ __| __| | | |  _ \
 *   | |_| | |_| |  __/\__ \ |_| |_| | |_) |
 *    \__\_\\__,_|\___||___/\__|____/|____/
 *
 *  Copyright (c) 2014-2019 Appsicle
 *  Copyright (c) 2019-2022 QuestDB
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
 *
 ******************************************************************************/

package io.questdb.griffin;

import io.questdb.cairo.CairoEngine;
import io.questdb.log.Log;
import io.questdb.log.LogFactory;
import io.questdb.std.Os;
import io.questdb.std.Vect;
import io.questdb.test.tools.TestUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;

public class O3SplitPartitionTest extends AbstractO3Test {
    private static final Log LOG = LogFactory.getLog(O3Test.class);

    private final StringBuilder tstData = new StringBuilder();
    @Rule
    public TestName name = new TestName();

    @Before
    public void setUp4() {
        Vect.resetPerformanceCounters();
    }

    @After
    public void tearDown4() {
        int count = Vect.getPerformanceCountersCount();
        if (count > 0) {
            tstData.setLength(0);
            tstData.append(name.getMethodName()).append(",");
            long total = 0;
            for (int i = 0; i < count; i++) {
                long val = Vect.getPerformanceCounter(i);
                tstData.append(val).append("\t");
                total += val;
            }
            tstData.append(total);

            Os.sleep(10);
            System.err.flush();
            System.err.println(tstData);
            System.err.flush();
        }
    }

    @Test
    public void testSplitMidPartitionContended() throws Exception {
        executeWithPool(0, O3SplitPartitionTest::testSplitMidPartition0);
    }

    private static void testSplitMidPartition0(
            CairoEngine engine,
            SqlCompiler compiler,
            SqlExecutionContext executionContext
    ) throws SqlException {
        compiler.compile(
                "create table x as (" +
                        "select" +
                        " cast(x as int) i," +
                        " -x j," +
                        " timestamp_sequence('2020-02-03T13', 60*1000000L) ts" +
                        " from long_sequence(60*24*2)" +
                        ") timestamp (ts) partition by DAY",
                executionContext
        );

        compiler.compile(
                "create table z as (" +
                        "select" +
                        " cast(x as int) * 1000000 i," +
                        " -x - 1000000L as j," +
                        " timestamp_sequence('2020-02-04T23:01', 60*1000000L) ts" +
                        " from long_sequence(50))",
                executionContext
        );

        compiler.compile(
                "create table y as (select * from x union all select * from z)",
                executionContext
        );

        compiler.compile("insert into x select * from z", executionContext);

        TestUtils.assertEquals(
                compiler,
                executionContext,
                "y order by ts",
                "x"
        );
    }
}