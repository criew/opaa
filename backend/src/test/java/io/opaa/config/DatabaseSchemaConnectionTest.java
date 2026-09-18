package io.opaa.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.test.OpaaIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The pooled connections carry opaa.database.schema at the front of their search_path; without the
 * Hikari data-source property the driver default ({@code "$user", public}) would apply instead.
 */
@OpaaIntegrationTest
class DatabaseSchemaConnectionTest {

  @Autowired private JdbcTemplate jdbcTemplate;

  @Value("${opaa.database.schema}")
  private String schema;

  @Test
  void searchPathStartsWithTheConfiguredSchema() {
    assertThat(jdbcTemplate.queryForObject("SHOW search_path", String.class))
        .isEqualTo(schema + ",public");
    assertThat(jdbcTemplate.queryForObject("SELECT current_schema()", String.class))
        .isEqualTo(schema);
  }
}
