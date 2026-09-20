package io.opaa.permission;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.test.OpaaIntegrationTest;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The precondition of ADR-0036, Entscheidung 8, held mechanically: <b>no personal history source
 * without the maximum retention period</b>. Both sides are read off the production side - the
 * tables from the Liquibase-built schema, the sweepers from the wiring the application actually
 * starts with - so a new history table added for #1813, #1815, #1818 or #1819 fails here until it
 * brings its own {@link PermissionHistorySweeper}.
 *
 * <p><b>What it cannot see:</b> a history table that does not end in {@code _history}. The suffix
 * is the only marker the schema offers; a table named otherwise escapes this guard, and no
 * assertion here would notice.
 */
@OpaaIntegrationTest
class PermissionHistorySweeperCoverageTest {

  @Autowired private List<PermissionHistorySweeper> sweepers;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void everyHistoryTableOfTheSchemaIsSweptByExactlyOneSweeper() {
    Set<String> sweptTables =
        sweepers.stream().map(PermissionHistorySweeper::historyTable).collect(Collectors.toSet());

    assertThat(sweptTables)
        .as("two sweepers naming the same table would leave another one unswept")
        .hasSize(sweepers.size());
    assertThat(historyTablesOfTheSchema())
        .as(
            "every history table needs its own PermissionHistorySweeper - the retention period is"
                + " the precondition of every personal history source")
        .containsExactlyInAnyOrderElementsOf(sweptTables);
  }

  private List<String> historyTablesOfTheSchema() {
    return jdbcTemplate.queryForList(
        "SELECT table_name FROM information_schema.tables"
            + " WHERE table_schema = current_schema() AND table_type = 'BASE TABLE'"
            + " AND table_name LIKE '%\\_history'",
        String.class);
  }
}
