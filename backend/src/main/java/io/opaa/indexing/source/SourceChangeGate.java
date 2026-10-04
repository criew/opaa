package io.opaa.indexing.source;

import io.opaa.knowledge.KnowledgeLibrary;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The only way a change of a library's source reaches its connector: validated against the
 * effective configuration before, applied to the library's own part, and the run state the saved
 * change invalidates discarded. "Effective" is the library's own configuration merged with the
 * defaults of its profile, as {@link SourceConnectionResolver} resolves it.
 */
public class SourceChangeGate {

  private final SourceConnectorRegistry connectors;

  public SourceChangeGate(SourceConnectorRegistry connectors) {
    this.connectors = connectors;
  }

  /**
   * The change {@code requested} as {@code library}'s connector validates it against {@code
   * before}, the effective configuration until now; see {@link SourceConnector#validateChange}.
   */
  public SourceSettings validate(
      KnowledgeLibrary library,
      SourceSettings before,
      SourceSettings requested,
      boolean replacesConnection) {
    return connector(library).validateChange(before, requested, replacesConnection);
  }

  /** Writes the connector settings of {@code validated} onto {@code library}'s own part. */
  public void apply(KnowledgeLibrary library, SourceSettings validated) {
    connector(library).applyChange(library, ConnectorData.storedIn(library), validated);
  }

  /**
   * Discards the run state the saved change from {@code before} to {@code after}, both effective,
   * invalidates, and returns the {@link SourceConnector#settingsState} keys that changed.
   */
  public Set<String> applied(
      KnowledgeLibrary library, SourceSettings before, SourceSettings after) {
    SourceConnector connector = connector(library);
    Map<String, Object> previous = connector.settingsState(library, before.connectorSettings());
    Map<String, Object> current = connector.settingsState(library, after.connectorSettings());
    Set<String> changed = new LinkedHashSet<>();
    for (Map.Entry<String, Object> entry : previous.entrySet()) {
      if (!Objects.equals(entry.getValue(), current.get(entry.getKey()))) {
        changed.add(entry.getKey());
      }
    }
    connector.onSourceChanged(
        library, !Objects.equals(before.sourceUrl(), after.sourceUrl()), changed);
    return changed;
  }

  /**
   * Discards the run state a new address of {@code library} invalidates, its settings unchanged.
   */
  public void addressMoved(KnowledgeLibrary library) {
    connector(library).onSourceChanged(library, true, Set.of());
  }

  private SourceConnector connector(KnowledgeLibrary library) {
    return connectors.connector(library.getSourceType());
  }
}
