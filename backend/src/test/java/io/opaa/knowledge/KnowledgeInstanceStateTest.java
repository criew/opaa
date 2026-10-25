package io.opaa.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/**
 * The connector flows of the shared libraries: each flow a connector names is reported, switched on
 * exactly when a shared library of its type exists.
 */
class KnowledgeInstanceStateTest {

  private static final UUID ORGANIZATION = UUID.randomUUID();

  private final KnowledgeLibraryRepository libraries = mock(KnowledgeLibraryRepository.class);
  private final MalwareScanService malwareScan = mock(MalwareScanService.class);

  @Test
  void aConnectorFlowIsOnExactlyWhenASharedLibraryOfItsTypeExists() {
    when(libraries.countSharedBySourceType(ORGANIZATION))
        .thenReturn(
            List.<Object[]>of(
                new Object[] {SourceType.of("CONFLUENCE"), 2L},
                new Object[] {SourceType.UPLOAD, 1L}));
    ConnectorDataFlows connectorFlows =
        () ->
            Map.of(
                SourceType.of("CONFLUENCE"), "source-confluence",
                SourceType.of("S3"), "source-s3");

    Map<String, Boolean> flows = state(connectorFlows).dataFlows(ORGANIZATION);

    assertThat(flows)
        .containsEntry("source-confluence", true)
        .containsEntry("source-s3", false)
        .doesNotContainKey("source-web");
  }

  private KnowledgeInstanceState state(ConnectorDataFlows connectorFlows) {
    StaticListableBeanFactory beans = new StaticListableBeanFactory();
    beans.addBean("connectorDataFlows", connectorFlows);
    return new KnowledgeInstanceState(
        libraries,
        mock(UploadedOriginalStore.class),
        malwareScan,
        beans.getBeanProvider(ConnectorDataFlows.class));
  }
}
