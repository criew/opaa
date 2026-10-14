package io.opaa.indexing.spi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import io.opaa.knowledge.KnowledgeLibrary;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The lifecycle defaults of {@link SourceConnector}, without any connector overriding them. */
class SourceConnectorDefaultsTest {

  private static final ConnectorData STORED = ConnectorData.of(Map.of("folders", List.of("/a")));

  /** Records what {@link #validate} gets, normalises the address, reads settings as marked. */
  private static final class StubConnector implements SourceConnector {

    final List<SourceSettings> validated = new ArrayList<>();

    @Override
    public SourceConnectorDescriptor descriptor() {
      throw new UnsupportedOperationException();
    }

    @Override
    public ConnectorData readSettings(ConnectorData requested) {
      return ConnectorData.of(Map.of("read", requested.asMap()));
    }

    @Override
    public SourceSettings validate(SourceSettings requested) {
      validated.add(requested);
      return requested.withSourceUrl("https://normalised.example.org");
    }

    @Override
    public SourceConnectionTestResult testConnection(
        SourceSettings settings, ConnectorData stored) {
      throw new UnsupportedOperationException();
    }
  }

  private final StubConnector connector = new StubConnector();

  @Test
  void aReplacedConnectionWithoutRequestedSettingsIsValidatedWithTheStoredOnes() {
    SourceSettings stored = new SourceSettings(null, "https://old.example.org", null, STORED);
    SourceSettings requested = new SourceSettings(null, "https://new.example.org", "geheim", null);

    SourceSettings result = connector.validateChange(stored, requested, true);

    assertThat(connector.validated)
        .singleElement()
        .satisfies(
            seen -> {
              assertThat(seen.connectorSettings()).isEqualTo(STORED);
              assertThat(seen.sourceUrl()).isEqualTo("https://new.example.org");
            });
    assertThat(result.sourceUrl()).isEqualTo("https://normalised.example.org");
    assertThat(result.connectorSettings()).isNull();
  }

  @Test
  void settingsChangedOnTheirOwnPassReadSettingsOnly() {
    SourceSettings stored = new SourceSettings(null, "https://old.example.org", null, STORED);
    ConnectorData asked = ConnectorData.of(Map.of("folders", List.of("/b")));
    SourceSettings requested = new SourceSettings(null, "https://old.example.org", null, asked);

    SourceSettings result = connector.validateChange(stored, requested, false);

    assertThat(connector.validated).isEmpty();
    assertThat(result.connectorSettings()).isEqualTo(connector.readSettings(asked));
    assertThat(result.sourceUrl()).isEqualTo("https://old.example.org");
  }

  @Test
  void absentSettingsAreNeitherStoredOnANewLibraryNorOnAChange() {
    KnowledgeLibrary library = mock(KnowledgeLibrary.class);
    SourceSettings withoutSettings = new SourceSettings(null, "https://example.org", null, null);

    connector.configureNew(library, withoutSettings);
    connector.applyChange(library, STORED, withoutSettings);

    verify(library, never()).updateSourceSettings(any());
  }

  @Test
  void presentSettingsAreStoredAsTheyAre() {
    KnowledgeLibrary library = mock(KnowledgeLibrary.class);
    SourceSettings withSettings = new SourceSettings(null, "https://example.org", null, STORED);

    connector.configureNew(library, withSettings);
    connector.applyChange(library, null, withSettings);

    verify(library, times(2)).updateSourceSettings(STORED.toJson());
  }
}
