package io.opaa.indexing.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.test.SourceTypes;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The gate hands the connector the effective settings and reports what changed. */
class SourceChangeGateTest {

  private final SourceConnector connector = mock(SourceConnector.class);
  private final SourceChangeGate gate = new SourceChangeGate(registryOf(connector));
  private final KnowledgeLibrary library =
      KnowledgeLibrary.ownedByUser(
          UUID.randomUUID(),
          "Bibliothek",
          null,
          UUID.randomUUID(),
          SourceTypes.RSS_FEED,
          null,
          "https://neu.example.org/feed",
          null,
          null,
          false);

  private static SourceConnectorRegistry registryOf(SourceConnector connector) {
    SourceConnectorRegistry registry = mock(SourceConnectorRegistry.class);
    when(registry.connector(any())).thenReturn(connector);
    return registry;
  }

  @Test
  void validateHandsTheEffectiveConfigurationBeforeToTheConnector() {
    SourceSettings before = settings("https://alt.example.org/feed", "a");
    SourceSettings requested = settings("https://neu.example.org/feed", null);
    SourceSettings validated = settings("https://neu.example.org/feed", "b");
    when(connector.validateChange(before, requested, true)).thenReturn(validated);

    assertThat(gate.validate(library, before, requested, true)).isSameAs(validated);
  }

  @Test
  void appliedComparesTheEffectiveStatesAndDiscardsTheRunState() {
    SourceSettings before = settings("https://alt.example.org/feed", "a");
    SourceSettings after = settings("https://neu.example.org/feed", "b");
    when(connector.settingsState(library, before.connectorSettings()))
        .thenReturn(Map.of("auswahl", "a", "rhythmus", 1));
    when(connector.settingsState(library, after.connectorSettings()))
        .thenReturn(Map.of("auswahl", "b", "rhythmus", 1));

    assertThat(gate.applied(library, before, after)).containsExactly("auswahl");
    verify(connector).onSourceChanged(library, true, Set.of("auswahl"));
  }

  @Test
  void aMovedAddressDiscardsTheRunStateWithoutChangedSettings() {
    gate.addressMoved(library);

    verify(connector).onSourceChanged(library, true, Set.of());
  }

  @Test
  void applyHandsTheConnectorTheLibrarysOwnSettings() {
    library.updateSourceSettings("{\"auswahl\":\"a\"}");
    SourceSettings validated = settings("https://neu.example.org/feed", "b");

    gate.apply(library, validated);

    verify(connector).applyChange(library, ConnectorData.storedIn(library), validated);
  }

  private static SourceSettings settings(String url, String selection) {
    return new SourceSettings(
        null,
        url,
        null,
        null,
        false,
        selection == null ? null : ConnectorData.of(Map.of("auswahl", selection)));
  }
}
