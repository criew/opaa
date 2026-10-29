package io.opaa.indexing.source.confluence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import io.opaa.common.ValidationException;
import io.opaa.indexing.source.confluence.webhook.ConfluenceWebhookService;
import io.opaa.indexing.spi.ConnectorData;
import io.opaa.indexing.spi.SourceSettings;
import io.opaa.indexing.spi.SourceSyncStateRepository;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.test.SourceTypes;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The scope "Alle Spaces": accepted instead of a selection, stored as such, never both. */
class ConfluenceAllSpacesTest {

  private static final String URL = "https://wiki.example.org";

  private final ConfluenceSourceConnector connector =
      new ConfluenceSourceConnector(
          mock(ConfluenceConnectionService.class),
          mock(SourceSyncStateRepository.class),
          mock(ConfluenceWebhookService.class));

  private static SourceSettings request(Map<String, Object> settings) {
    return new SourceSettings(null, URL, "pat", ConnectorData.of(settings));
  }

  private static KnowledgeLibrary library() {
    return KnowledgeLibrary.ownedByUser(
        UUID.randomUUID(),
        "Wiki",
        null,
        UUID.randomUUID(),
        SourceTypes.CONFLUENCE,
        null,
        URL,
        "pat");
  }

  // regression guard for #3117: an empty selection was the only way to say "everything" and refused
  @Test
  void allSpacesIsAcceptedWithoutASelection() {
    SourceSettings validated =
        connector.validate(request(Map.of("edition", "DATA_CENTER", "allSpaces", true)));

    assertThat(validated.connectorSettings().asMap())
        .containsEntry("allSpaces", true)
        .containsEntry("spaces", List.of());
  }

  @Test
  void allSpacesAndASelectionAreRefusedTogether() {
    assertThatThrownBy(
            () ->
                connector.validate(
                    request(
                        Map.of(
                            "edition",
                            "DATA_CENTER",
                            "allSpaces",
                            true,
                            "spaces",
                            List.of(Map.of("key", "ENG"))))))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("allSpaces");
  }

  @Test
  void withoutAllSpacesASelectionIsStillRequired() {
    assertThatThrownBy(
            () ->
                connector.validate(
                    request(
                        Map.of("edition", "DATA_CENTER", "allSpaces", false, "spaces", List.of()))))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("mindestens ein Space");
  }

  @Test
  void aStoredSelectionStaysExplicitAndSwitchingToAllDiscardsTheSyncState() {
    KnowledgeLibrary library = library();
    ConfluenceTestSettings.configure(
        library, ConfluenceEdition.CLOUD, List.of(new ConfluenceSpaceSelection("ENG", "Technik")));
    ConnectorData explicit = ConnectorData.storedIn(library);
    assertThat(explicit.asMap()).doesNotContainKey("allSpaces");
    Object before = connector.settingsState(library, explicit).get("confluenceSpaces");

    SourceSettings change =
        connector.validateChange(
            new SourceSettings(null, URL, "pat", explicit),
            new SourceSettings(null, null, null, ConnectorData.of(Map.of("allSpaces", true))),
            false);
    connector.applyChange(library, explicit, change);

    ConnectorData all = ConnectorData.storedIn(library);
    assertThat(all.asMap()).containsEntry("allSpaces", true).containsEntry("spaces", List.of());
    assertThat(connector.settingsView(library, all, false).asMap())
        .containsEntry("allSpaces", true);
    assertThat(connector.settingsState(library, all).get("confluenceSpaces")).isNotEqualTo(before);
  }
}
