package io.opaa.indexing.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.test.SourceTypes;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@link SyncStateBasis}: address, path and what the connector compares make the fingerprint, in
 * any key order; a setting the connector does not compare leaves it as it is.
 */
class SyncStateBasisTest {

  private final SourceConnector connector = mock(SourceConnector.class);
  private final SyncStateBasis basis = new SyncStateBasis(() -> registryOf(connector));
  private final KnowledgeLibrary library =
      KnowledgeLibrary.ownedByUser(
          UUID.randomUUID(),
          "Bibliothek",
          null,
          UUID.randomUUID(),
          SourceTypes.S3,
          null,
          "https://speicher.example.org",
          null,
          null,
          false);

  private static SourceConnectorRegistry registryOf(SourceConnector connector) {
    SourceConnectorRegistry registry = mock(SourceConnectorRegistry.class);
    when(registry.connector(any())).thenReturn(connector);
    return registry;
  }

  @Test
  void onlyWhatTheConnectorComparesCounts() {
    SourceSettings weekly = settings("https://speicher.example.org", "a", 7);
    SourceSettings daily = settings("https://speicher.example.org", "a", 1);
    SourceSettings other = settings("https://speicher.example.org", "b", 7);
    when(connector.settingsState(any(), any()))
        .thenAnswer(
            invocation -> {
              ConnectorData stored = invocation.getArgument(1);
              return Map.of("auswahl", List.of(stored.get("auswahl")));
            });

    assertThat(basis.of(library, weekly)).isEqualTo(basis.of(library, daily)).hasSize(64);
    assertThat(basis.of(library, other)).isNotEqualTo(basis.of(library, weekly));
  }

  @Test
  void anotherAddressOrPathIsAnotherBasis() {
    when(connector.settingsState(any(), any())).thenReturn(Map.of("auswahl", "a"));
    SourceSettings first = settings("https://speicher.example.org", "a", 7);

    assertThat(basis.of(library, settings("https://anders.example.org", "a", 7)))
        .isNotEqualTo(basis.of(library, first));
    assertThat(
            basis.of(
                library,
                new SourceSettings("/anderer/pfad", first.sourceUrl(), null, null, false, null)))
        .isNotEqualTo(basis.of(library, first));
  }

  @Test
  void theOrderOfTheKeysDoesNotCount() {
    Map<String, Object> ab = new LinkedHashMap<>();
    ab.put("a", "1");
    ab.put("b", Map.of("x", "2", "y", "3"));
    Map<String, Object> ba = new LinkedHashMap<>();
    ba.put("b", new LinkedHashMap<>(Map.of("y", "3", "x", "2")));
    ba.put("a", "1");

    assertThat(
            SyncStateBasis.WHOLE_SETTINGS.of(
                library, new SourceSettings(null, "u", null, null, false, ConnectorData.of(ab))))
        .isEqualTo(
            SyncStateBasis.WHOLE_SETTINGS.of(
                library, new SourceSettings(null, "u", null, null, false, ConnectorData.of(ba))));
  }

  @Test
  void withoutAConnectorEverySettingCountsButNotTheSecretOrTheTransport() {
    SourceSettings weekly = settings("https://speicher.example.org", "a", 7);

    assertThat(SyncStateBasis.WHOLE_SETTINGS.of(library, weekly))
        .isNotEqualTo(
            SyncStateBasis.WHOLE_SETTINGS.of(
                library, settings("https://speicher.example.org", "a", 1)));
    assertThat(
            SyncStateBasis.WHOLE_SETTINGS.of(
                library,
                new SourceSettings(
                    null,
                    weekly.sourceUrl(),
                    "http://proxy.example.org:3128",
                    "geheim",
                    true,
                    weekly.connectorSettings())))
        .isEqualTo(SyncStateBasis.WHOLE_SETTINGS.of(library, weekly));
  }

  private static SourceSettings settings(String url, String selection, int intervalDays) {
    return new SourceSettings(
        null,
        url,
        null,
        null,
        false,
        ConnectorData.of(Map.of("auswahl", selection, "fullSyncIntervalDays", intervalDays)));
  }
}
