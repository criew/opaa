package io.opaa.directory.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.ResultSet;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

class DirectorySyncMetricsTest {

  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
  private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
  private final DirectorySyncMetrics metrics = new DirectorySyncMetrics(jdbc, meterRegistry);

  private double gauge(String name) {
    return meterRegistry.get(name).tag("provider", "keycloak").gauge().value();
  }

  /** A stale value would look healthy: an unreadable state is reported as unknown. */
  @Test
  void aFailedReadReportsNaNUnderTheProvidersOfTheLastOne() throws Exception {
    ResultSet row = mock(ResultSet.class);
    when(row.getString(1)).thenReturn("KEYCLOAK");
    when(row.getDouble(2)).thenReturn(42.0);
    doAnswer(
            invocation -> {
              invocation.getArgument(1, RowCallbackHandler.class).processRow(row);
              return null;
            })
        .when(jdbc)
        .query(anyString(), any(RowCallbackHandler.class));
    metrics.refresh();
    assertThat(gauge("opaa.directory_sync.last_success")).isEqualTo(42.0);

    doThrow(new QueryTimeoutException("blocked"))
        .when(jdbc)
        .query(anyString(), any(RowCallbackHandler.class));
    metrics.refresh();

    assertThat(gauge("opaa.directory_sync.last_success")).isNaN();
    assertThat(gauge("opaa.directory_sync.plan.pending.age")).isNaN();
  }
}
