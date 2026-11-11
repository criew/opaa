package io.opaa.query.runlog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.opaa.compliance.ComplianceManifest.TableRef;
import io.opaa.compliance.ComplianceManifestLoader;
import io.opaa.compliance.InstanceDisclosure.DataSetDescription;
import io.opaa.compliance.InstanceDisclosure.ProtocolField;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AgentRunLogInstanceStateTest {

  @Test
  void describesEveryColumnTheManifestListsForBothLogs() {
    AgentRunLogRetentionSettingsRepository repository =
        mock(AgentRunLogRetentionSettingsRepository.class);
    when(repository.findSingleton()).thenReturn(Optional.empty());

    List<DataSetDescription> dataSets =
        new AgentRunLogInstanceState(repository).dataSetDescriptions();

    assertThat(dataSets)
        .extracting(DataSetDescription::id)
        .containsExactly(
            AgentRunLogInstanceState.STEP_LOG_ID, AgentRunLogInstanceState.SANDBOX_LOG_ID)
        .isSorted();
    assertThat(dataSets.get(0).fields())
        .extracting(ProtocolField::name)
        .containsExactlyInAnyOrderElementsOf(manifestColumns("agent_step_log"));
    assertThat(dataSets.get(1).fields())
        .extracting(ProtocolField::name)
        .containsExactlyInAnyOrderElementsOf(manifestColumns("agent_sandbox_log"));
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

  private static List<String> manifestColumns(String table) {
    TableRef ref =
        ComplianceManifestLoader.load().data().stream()
            .flatMap(entry -> entry.tables().stream())
            .filter(candidate -> candidate.name().equals(table))
            .findFirst()
            .orElseThrow();
    List<String> columns = new ArrayList<>(ref.personalColumns());
    if (ref.nonPersonalColumns() != null) {
      columns.addAll(ref.nonPersonalColumns());
    }
    return columns;
  }
}
