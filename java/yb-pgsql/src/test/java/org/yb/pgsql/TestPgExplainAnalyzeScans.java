package org.yb.pgsql;

import static org.yb.pgsql.ExplainAnalyzeUtils.NODE_INDEX_SCAN;
import static org.yb.pgsql.ExplainAnalyzeUtils.NODE_INDEX_ONLY_SCAN;
import static org.yb.pgsql.ExplainAnalyzeUtils.NODE_SEQ_SCAN;
import static org.yb.pgsql.ExplainAnalyzeUtils.NODE_YB_SEQ_SCAN;

import java.sql.Statement;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.yb.YBTestRunner;
import org.yb.pgsql.ExplainAnalyzeUtils.PlanCheckerBuilder;
import org.yb.pgsql.ExplainAnalyzeUtils.TopLevelCheckerBuilder;
import org.yb.util.json.Checker;
import org.yb.util.json.Checkers;
import org.yb.util.json.JsonUtil;

@RunWith(value=YBTestRunner.class)
public class TestPgExplainAnalyzeScans extends BasePgExplainAnalyzeTest {
  private static final String MAIN_TABLE = "foo";
  private static final String SIDE_TABLE = "bar";
  private static final String MAIN_RANGE_MC_INDEX = "foo_v1_v2"; // Multi-column Range Index
  private static final String MAIN_HASH_INDEX = "foo_v3";
  private static final String MAIN_RANGE_INDEX = "foo_v4";
  private static final String SIDE_HASH_INDEX = "bar_v1";
  private static final String SIDE_RANGE_INDEX = "bar_v2";
  private static final int NUM_PAGES = 10;
  private static final TopLevelCheckerBuilder SCAN_TOP_LEVEL_CHECKER = makeTopLevelBuilder()
      .storageWriteRequests(Checkers.equal(0))
      .storageFlushRequests(Checkers.equal(0));

  @Before
  public void setUp() throws Exception {
    try (Statement stmt = connection.createStatement()) {
      stmt.execute(String.format("CREATE TABLE %s (k INT PRIMARY KEY, v1 INT, "
          + "v2 INT, v3 INT, v4 INT, v5 INT) SPLIT INTO 2 TABLETS", MAIN_TABLE));

      // Multi-column Range Index on (v1, v2)
      stmt.execute(String.format("CREATE INDEX %s ON %s (v1, v2 ASC)",
        MAIN_RANGE_MC_INDEX, MAIN_TABLE));

      // Hash Index on v3
      stmt.execute(String.format("CREATE INDEX %s ON %s (v3 HASH)",
        MAIN_HASH_INDEX, MAIN_TABLE));

      // Range Index on v4
      stmt.execute(String.format("CREATE INDEX %s ON %s (v4 ASC)",
        MAIN_RANGE_INDEX, MAIN_TABLE));

      // Populate the table
      stmt.execute(String.format("INSERT INTO %s (SELECT i, i, i %% 1024, i, i, "
        + "i %% 128 FROM generate_series(0, %d) i)", MAIN_TABLE, (NUM_PAGES * 1024 - 1)));

      // Create table 'bar'
      stmt.execute(String.format("CREATE TABLE %s (k1 INT, k2 INT, v1 INT, "
          +"v2 INT, v3 INT, PRIMARY KEY (k1 ASC, k2 DESC))", SIDE_TABLE));

      // Hash Index on v1
      stmt.execute(String.format("CREATE INDEX %s ON %s (v1 HASH)",
        SIDE_HASH_INDEX, SIDE_TABLE));

      // Range Index on v2.
      stmt.execute(String.format("CREATE INDEX %s ON %s (v2 ASC)",
        SIDE_RANGE_INDEX, SIDE_TABLE));

      // Populate the table
      stmt.execute(String.format("INSERT INTO %s (SELECT i, i %% 128, i, i %% 1024, i "
        + "FROM generate_series(0, %d) i)", SIDE_TABLE, (NUM_PAGES * 1024 - 1)));
    }
  }

  private static TopLevelCheckerBuilder makeTopLevelBuilder() {
    return JsonUtil.makeCheckerBuilder(TopLevelCheckerBuilder.class, false);
  }

  private static PlanCheckerBuilder makePlanBuilder() {
    return JsonUtil.makeCheckerBuilder(PlanCheckerBuilder.class, false);
  }

  private void testExplainNoPushdown(String query, Checker checker) throws Exception {
    final String enableExpressionPushdown = "SET yb_enable_expression_pushdown TO %s";
    try (Statement stmt = connection.createStatement()) {
      stmt.execute(String.format(enableExpressionPushdown, false));
      ExplainAnalyzeUtils.testExplain(stmt, query, checker);
      stmt.execute(String.format(enableExpressionPushdown, true));
    }
  }

  @Test
  public void testSeqScan() throws Exception {
    final String simpleQuery = String.format("SELECT * FROM %s", MAIN_TABLE);
    final String queryWithExpr = String.format("SELECT * FROM %s WHERE v5 < 128", MAIN_TABLE);

    PlanCheckerBuilder SEQ_SCAN_PLAN = makePlanBuilder()
        .nodeType(NODE_SEQ_SCAN)
        .relationName(MAIN_TABLE)
        .alias(MAIN_TABLE)
        .storageTableReadExecutionTime(Checkers.greater(0.0));

    // Simple Seq Scan
    Checker checker = SCAN_TOP_LEVEL_CHECKER
        .storageReadRequests(Checkers.equal(NUM_PAGES + 1))
        .storageReadExecutionTime(Checkers.greater(0.0))
        .plan(SEQ_SCAN_PLAN
            .storageTableReadRequests(Checkers.equal(NUM_PAGES + 1))
            .build())
        .build();

    testExplain(simpleQuery, checker);

    // Seq Scan with Pushdown
    Checker pushdown_checker = SCAN_TOP_LEVEL_CHECKER
        .storageReadRequests(Checkers.equal(6))
        .storageReadExecutionTime(Checkers.greater(0.0))
        .plan(SEQ_SCAN_PLAN
            .storageTableReadRequests(Checkers.equal(6))
            .build())
        .build();

    testExplain(queryWithExpr, pushdown_checker);

    // Seq Scan without Pushdown
    Checker no_pushdown_checker = SCAN_TOP_LEVEL_CHECKER
        .storageReadRequests(Checkers.equal(NUM_PAGES + 1))
        .storageReadExecutionTime(Checkers.greater(0.0))
        .plan(SEQ_SCAN_PLAN
            .storageTableReadRequests(Checkers.equal(NUM_PAGES + 1))
            .build())
        .build();

    testExplainNoPushdown(queryWithExpr, no_pushdown_checker);
  }

  @Test
  public void testYbSeqScan() throws Exception {
    final String simpleQuery = String.format("/*+ SeqScan(foo) */ SELECT * FROM %s",
      MAIN_TABLE);
    final String queryWithExpr = String.format("/*+ SeqScan(foo) */ SELECT * FROM %s "
      + "WHERE v5 < 64", MAIN_TABLE);

    PlanCheckerBuilder YB_SEQ_SCAN_PLAN = makePlanBuilder()
        .nodeType(NODE_YB_SEQ_SCAN)
        .relationName(MAIN_TABLE)
        .alias(MAIN_TABLE)
        .storageTableReadExecutionTime(Checkers.greater(0.0));

    // 1. Simple YB Seq Scan
    Checker checker = SCAN_TOP_LEVEL_CHECKER
        .storageReadRequests(Checkers.equal(NUM_PAGES + 1))
        .storageReadExecutionTime(Checkers.greater(0.0))
        .plan(YB_SEQ_SCAN_PLAN
            .storageTableReadRequests(Checkers.equal(NUM_PAGES + 1))
            .build())
        .build();

    testExplain(simpleQuery, checker);

    // 2. YB Seq Scan with Pushdown
    Checker pushdown_checker = SCAN_TOP_LEVEL_CHECKER
        .storageReadRequests(Checkers.equal(3))
        .storageReadExecutionTime(Checkers.greater(0.0))
        .plan(YB_SEQ_SCAN_PLAN
            .storageTableReadRequests(Checkers.equal(3))
            .build())
        .build();

      testExplain(queryWithExpr, pushdown_checker);

      // 3. Seq Scan without Pushdown
    Checker no_pushdown_checker = SCAN_TOP_LEVEL_CHECKER
        .storageReadRequests(Checkers.equal(NUM_PAGES + 1))
        .storageReadExecutionTime(Checkers.greater(0.0))
        .plan(YB_SEQ_SCAN_PLAN
            .storageTableReadRequests(Checkers.equal(NUM_PAGES + 1))
            .build())
        .build();

    testExplainNoPushdown(queryWithExpr, no_pushdown_checker);
  }

  @Test
  public void testIndexScan() throws Exception {
    final String simpleIndexQuery = "SELECT * FROM %s WHERE %s = 128";
    final String indexQueryWithPredicate = "/*+ IndexScan(%s %s) */ SELECT * FROM %s WHERE "
      + "%s < 128";

    PlanCheckerBuilder indexScanPlan = makePlanBuilder()
      .nodeType(NODE_INDEX_SCAN);

    {
      // 1. Simple Primary Hash Index Scan
      Checker checker = SCAN_TOP_LEVEL_CHECKER
          .storageReadRequests(Checkers.equal(1))
          .storageReadExecutionTime(Checkers.greater(0.0))
          .plan(indexScanPlan
              .storageTableReadRequests(Checkers.equal(1))
              .storageTableReadExecutionTime(Checkers.greater(0.0))
              .build())
          .build();

      testExplain(String.format(simpleIndexQuery, MAIN_TABLE, "k"), checker);

      // 2. Simple Primary Range Index Scan
      testExplain(String.format(simpleIndexQuery, SIDE_TABLE, "k1"), checker);
    }

    // 3. Simple Secondary Range Index Scan
    {
      Checker checker = SCAN_TOP_LEVEL_CHECKER
          .storageReadRequests(Checkers.equal(2))
          .storageReadExecutionTime(Checkers.greater(0.0))
          .plan(indexScanPlan
              .storageTableReadRequests(Checkers.equal(1))
              .storageIndexReadRequests(Checkers.equal(1))
              .build())
          .build();

      testExplain(String.format(simpleIndexQuery, MAIN_TABLE, "v1"), checker);
    }

    // 4. Index Scan with Inequality Predicate
    {
      Checker checker = SCAN_TOP_LEVEL_CHECKER
          .storageReadRequests(Checkers.equal(2))
          .storageReadExecutionTime(Checkers.greater(0.0))
          .plan(indexScanPlan
              .storageTableReadRequests(Checkers.equal(1))
              .storageIndexReadRequests(Checkers.equal(1))
              .build())
          .build();

      testExplain(String.format(indexQueryWithPredicate, MAIN_TABLE, MAIN_RANGE_INDEX,
        MAIN_TABLE, "v4"), checker);
    }
  }

  @Test
  public void testIndexOnlyScan() throws Exception {
    final String simpleIndexOnlyQuery = "SELECT v1, v2 FROM %s WHERE %s";
    final String hintedIndexOnlyQuery = "/*+ IndexOnlyScan(%s %s) */ SELECT v1, v2 FROM %s " +
      "WHERE %s";

    PlanCheckerBuilder indexScanPlan = makePlanBuilder()
        .nodeType(NODE_INDEX_ONLY_SCAN)
        .storageIndexReadExecutionTime(Checkers.greater(0.0));

    // 1. Simple Range Index Scan
    {
      Checker checker = SCAN_TOP_LEVEL_CHECKER
          .storageReadRequests(Checkers.equal(1))
          .storageReadExecutionTime(Checkers.greater(0.0))
          .storageWriteRequests(Checkers.equal(0))
          .storageFlushRequests(Checkers.equal(0))
          .plan(indexScanPlan
              .relationName(MAIN_TABLE)
              .alias(MAIN_TABLE)
              .indexName(MAIN_RANGE_MC_INDEX)
              .storageIndexReadRequests(Checkers.equal(1))
              .storageIndexReadExecutionTime(Checkers.greater(0.0))
              .build())
          .build();

      testExplain(String.format(simpleIndexOnlyQuery, MAIN_TABLE, "v1 = 128"), checker);
    }

    // 2. Range Index Scan with Between Clause
    {
      Checker checker = SCAN_TOP_LEVEL_CHECKER
          .storageReadRequests(Checkers.equal(1))
          .storageReadExecutionTime(Checkers.greater(0.0))
          .storageWriteRequests(Checkers.equal(0))
          .storageFlushRequests(Checkers.equal(0))
          .plan(indexScanPlan
              .relationName(MAIN_TABLE)
              .alias(MAIN_TABLE)
              .indexName(MAIN_RANGE_MC_INDEX)
              .storageIndexReadRequests(Checkers.equal(1))
              .storageIndexReadExecutionTime(Checkers.greater(0.0))
              .build())
          .build();

      testExplain(String.format(hintedIndexOnlyQuery, MAIN_TABLE, MAIN_RANGE_MC_INDEX,
      MAIN_TABLE, "v1 IN (5119, 5120, 5121)"), checker);
    }
  }
}