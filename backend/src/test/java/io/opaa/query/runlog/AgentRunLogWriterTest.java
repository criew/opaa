package io.opaa.query.runlog;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ParameterizedPreparedStatementSetter;

/** A log that cannot be written never costs the run its answer: the writer never throws. */
class AgentRunLogWriterTest {

  private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
  private final AgentRunLogWriter writer = new AgentRunLogWriter(jdbc);

  @Test
  @SuppressWarnings("unchecked")
  void aFailingStepLogIsSwallowed() {
    when(jdbc.batchUpdate(
            anyString(), anyList(), anyInt(), any(ParameterizedPreparedStatementSetter.class)))
        .thenThrow(new DataAccessResourceFailureException("no partition for today"));

    assertThatCode(
            () ->
                writer.writeSteps(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    List.of(
                        new AgentStepLogEntry(0, null, "search_knowledge", "DONE", 3, 5, null))))
        .doesNotThrowAnyException();
  }

  @Test
  void aFailingSandboxLogIsSwallowed() {
    when(jdbc.update(anyString(), any(Object[].class)))
        .thenThrow(new DataAccessResourceFailureException("no partition for today"));

    assertThatCode(
            () ->
                writer.writeSandbox(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    new AgentSandboxLogEntry(
                        "sbx-1", null, "run_code", "COMPLETED", 1, 10, 0, 0, null)))
        .doesNotThrowAnyException();
  }
}
