package io.opaa.indexing.source.confluence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.opaa.api.types.ConfluenceEdition;
import io.opaa.api.types.DocumentSourceType;
import io.opaa.indexing.source.SourceSyncStateRepository;
import io.opaa.indexing.source.confluence.webhook.ConfluenceWebhookService;
import io.opaa.knowledge.KnowledgeLibrary;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** What {@link ConfluenceSourceConnector#settingsView} shows, and that broken settings stay out. */
class ConfluenceSourceConnectorViewTest {

  private final ConfluenceSourceConnector connector =
      new ConfluenceSourceConnector(
          mock(ConfluenceConnectionService.class),
          new ConfluenceProperties(0, null, null, 0, null, 0, 0, 0, null, null, 0),
          mock(SourceSyncStateRepository.class),
          mock(ConfluenceWebhookService.class));

  private static KnowledgeLibrary library() {
    return KnowledgeLibrary.ownedByUser(
        UUID.randomUUID(),
        "Wiki",
        null,
        UUID.randomUUID(),
        false,
        DocumentSourceType.CONFLUENCE,
        null,
        "https://wiki.example.org",
        null,
        "pat",
        false);
  }

  @Test
  void readersSeeEditionAndSpacesAndOnlyAManagerTheRhythm() {
    KnowledgeLibrary library = library();
    ConfluenceTestSettings.configure(
        library, ConfluenceEdition.CLOUD, List.of(new ConfluenceSpaceSelection("ENG", null)));
    ConfluenceTestSettings.fullSyncIntervalDays(library, 14);

    assertThat(connector.settingsView(library, false).asMap())
        .containsKeys("edition", "spaces")
        .doesNotContainKey("fullSyncIntervalDays");
    assertThat(connector.settingsView(library, true).asMap())
        .containsEntry("fullSyncIntervalDays", 14);
  }

  @Test
  void storedSettingsTheRecordRejectsAreLeftOutInsteadOfFailingTheRead() {
    KnowledgeLibrary unknownEdition = library();
    unknownEdition.updateSourceSettings("{\"edition\": \"SERVER\", \"spaces\": []}");
    KnowledgeLibrary spacesNoList = library();
    spacesNoList.updateSourceSettings("{\"edition\": \"CLOUD\", \"spaces\": \"ENG\"}");

    assertThat(connector.settingsView(unknownEdition, true)).isNull();
    assertThat(connector.settingsView(spacesNoList, false)).isNull();
    assertThat(connector.settingsState(spacesNoList)).containsEntry("confluenceSpaces", List.of());
  }
}
