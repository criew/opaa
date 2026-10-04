package io.opaa.indexing.source.s3;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.indexing.source.ConnectorData;
import io.opaa.indexing.source.SourceConnector;
import io.opaa.indexing.source.TestSourceConnectors;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.test.SourceTypes;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * What a library's S3 settings show: every reader the scopes, which the handbook names as the scope
 * every reader sees; only a manager region, addressing style and patterns.
 */
class S3SourceConnectorViewTest {

  private final SourceConnector connector =
      TestSourceConnectors.connectors().registry().connector(SourceTypes.S3);

  private final ConnectorData stored =
      ConnectorData.of(
          Map.of(
              "region",
              "eu-central-1",
              "pathStyle",
              true,
              "scopes",
              List.of(Map.of("bucket", "akten")),
              "includePatterns",
              List.of("**/*.pdf")));

  @Test
  void aReaderSeesOnlyTheScopes() {
    ConnectorData view = connector.settingsView(library(), stored, false);

    assertThat(view.asMap()).containsOnlyKeys("scopes");
    assertThat(view.get("scopes").toString()).contains("akten");
  }

  @Test
  void aManagerSeesTheWholeRecord() {
    ConnectorData view = connector.settingsView(library(), stored, true);

    assertThat(view.asMap())
        .containsEntry("region", "eu-central-1")
        .containsEntry("pathStyle", true)
        .containsKeys("scopes", "includePatterns", "excludePatterns");
  }

  private static KnowledgeLibrary library() {
    return KnowledgeLibrary.ownedByUser(
        UUID.randomUUID(),
        "Ablage",
        null,
        UUID.randomUUID(),
        SourceTypes.S3,
        null,
        "https://s3.example.org",
        null,
        null,
        false);
  }
}
