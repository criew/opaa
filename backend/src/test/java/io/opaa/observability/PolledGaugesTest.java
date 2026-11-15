package io.opaa.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/** The database reads of polled gauges give up on their own, apart from every other reader. */
class PolledGaugesTest {

  @Test
  void readsAreBoundedByAQueryTimeoutOfTheirOwn() {
    JdbcTemplate reads = PolledGauges.reads(mock(DataSource.class));

    assertThat(reads.getQueryTimeout()).isEqualTo(PolledGauges.QUERY_TIMEOUT_SECONDS);
  }
}
