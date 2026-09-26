package io.opaa.indexing.source.confluence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.opaa.api.types.ConfluenceEdition;
import io.opaa.common.ValidationException;
import io.opaa.indexing.source.SourceBrowser;
import io.opaa.indexing.source.SourceListing;
import io.opaa.indexing.source.SourceSettings;
import io.opaa.indexing.source.SourceSyncStateRepository;
import java.util.List;
import org.junit.jupiter.api.Test;

/** How {@link ConfluenceSourceConnector#browse} turns the space listing into a listing. */
class ConfluenceSourceConnectorBrowseTest {

  private final ConfluenceConnectionService connectionService =
      mock(ConfluenceConnectionService.class);
  private final ConfluenceSourceConnector connector =
      new ConfluenceSourceConnector(
          connectionService,
          new ConfluenceProperties(0, null, null, 0, null, 0, 0, 0, null, null, 0),
          mock(SourceSyncStateRepository.class),
          mock(io.opaa.indexing.source.confluence.webhook.ConfluenceWebhookService.class));

  private static SourceBrowser.Query query(String credentials) {
    return new SourceBrowser.Query(
        new SourceSettings(
            null,
            "https://wiki.example.org",
            null,
            credentials,
            false,
            ConfluenceEdition.DATA_CENTER,
            null,
            null,
            null),
        null,
        null);
  }

  @Test
  void everySpaceBecomesAnEntryWithKeyAndName() throws Exception {
    when(connectionService.listSpaces(anyString(), any(), any(), anyString(), anyBoolean()))
        .thenReturn(
            List.of(
                new ConfluenceSpace("1", "ENG", "Engineering"),
                new ConfluenceSpace("2", "HR", null)));

    SourceListing listing = connector.browse(query("pat"));

    assertThat(listing.complete()).isTrue();
    assertThat(listing.message()).isNull();
    assertThat(listing.entries())
        .extracting(SourceListing.Entry::key, SourceListing.Entry::name)
        .containsExactly(tuple("ENG", "Engineering"), tuple("HR", null));
  }

  @Test
  void aListingWithoutCredentialsIsRefused() {
    assertThatThrownBy(() -> connector.browse(query(null)))
        .isInstanceOf(ValidationException.class)
        .hasMessage("sourceCredentials sind für die Space-Auflistung erforderlich");
  }
}
