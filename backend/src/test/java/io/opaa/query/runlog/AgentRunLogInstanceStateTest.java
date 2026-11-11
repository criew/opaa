package io.opaa.query.runlog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.opaa.compliance.InstanceDisclosure.DataSetDescription;
import io.opaa.compliance.InstanceDisclosure.ProtocolField;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AgentRunLogInstanceStateTest {

  private static final List<String> STEP_LOG_COLUMNS =
      List.of(
          "run_id",
          "step_index",
          "recorded_at",
          "organization_id",
          "tool",
          "status",
          "hit_count",
          "duration_ms",
          "abort_reason");

  private static final List<String> SANDBOX_LOG_COLUMNS =
      List.of(
          "run_id",
          "session_ref",
          "recorded_at",
          "organization_id",
          "tool",
          "status",
          "script_runs",
          "duration_ms",
          "file_count",
          "file_bytes",
          "abort_reason");

  @Test
  void describesEveryColumnOfBothLogsWithPurposeAndRetention() {
    AgentRunLogRetentionSettingsRepository repository =
        mock(AgentRunLogRetentionSettingsRepository.class);
    when(repository.findSingleton()).thenReturn(Optional.empty());

    List<DataSetDescription> dataSets =
        new AgentRunLogInstanceState(repository).dataSetDescriptions();

    assertThat(dataSets)
        .extracting(DataSetDescription::id)
        .containsExactly(
            AgentRunLogInstanceState.STEP_LOG_ID, AgentRunLogInstanceState.SANDBOX_LOG_ID);
    assertThat(dataSets.get(0).fields())
        .extracting(ProtocolField::name)
        .containsExactlyElementsOf(STEP_LOG_COLUMNS);
    assertThat(dataSets.get(1).fields())
        .extracting(ProtocolField::name)
        .containsExactlyElementsOf(SANDBOX_LOG_COLUMNS);
    for (DataSetDescription dataSet : dataSets) {
      assertThat(dataSet.fields())
          .allSatisfy(
              field -> {
                assertThat(field.purpose()).isNotBlank();
                assertThat(field.retention()).contains("Frist");
              });
      assertThat(dataSet.queryPaths()).hasSize(1);
      assertThat(dataSet.absentQueryPaths()).isNotEmpty();
    }
  }
}
