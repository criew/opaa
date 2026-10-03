package io.opaa.indexing.source;

import io.opaa.knowledge.KnowledgeLibrary;
import java.util.Map;
import java.util.Set;

/**
 * One kind of library source, registered as a Spring bean in its connector package and reached by
 * the administration only through the {@link SourceConnectorRegistry}. The connector owns the
 * validation of its configuration, its connector settings (ADR-0038), the connection test and the
 * state its settings imply; the administration owns permissions, persistence and the audit.
 * Optional abilities are further interfaces the same bean implements ({@link SourceBrowser}, {@link
 * OriginalAccess}, {@link PushIntakeHandler}).
 *
 * <p>Every method that refuses input throws {@link io.opaa.common.ValidationException} with a
 * German, user-facing message.
 */
public interface SourceConnector {

  SourceConnectorDescriptor descriptor();

  /**
   * Reads connector settings as a request carries them into their normalised form; an unknown field
   * or a malformed value is refused. By default a connector has no settings and refuses any but an
   * empty object.
   */
  default ConnectorData readSettings(ConnectorData requested) {
    requested.requireOnly(Set.of());
    return requested.isEmpty() ? null : requested;
  }

  /**
   * Validates the complete configuration of a new library and returns its normalised form - the
   * connection fields as they are stored, the connector settings as {@link #configureNew} applies
   * them.
   */
  SourceSettings validate(SourceSettings requested);

  /**
   * Validates a change of a library's configuration against {@code stored}, its current one as the
   * core resolved it. The connection fields are only meaningful when {@code replacesConnection};
   * absent connector settings, or an absent part of them, stay as stored. Returns what {@link
   * #applyChange} applies, the connection fields normalised.
   */
  default SourceSettings validateChange(
      SourceSettings stored, SourceSettings requested, boolean replacesConnection) {
    return replacesConnection ? validate(requested) : requested;
  }

  /** Writes the connector settings of {@link #validate}'s result onto an unsaved library. */
  default void configureNew(KnowledgeLibrary library, SourceSettings validated) {}

  /**
   * Writes the connector settings of {@link #validateChange}'s result; {@code stored} are the ones
   * {@code library} carries until then.
   */
  default void applyChange(
      KnowledgeLibrary library, ConnectorData stored, SourceSettings validated) {}

  /**
   * The connector settings {@code stored} on {@code library} as a caller sees them, {@code null}
   * for none. By default only a manager ({@code manager}) sees them, whole; a connector that shows
   * readers what the library covers overrides this deliberately. Never a secret - the settings
   * carry none (ADR-0038, Entscheidung 3).
   */
  default ConnectorData settingsView(
      KnowledgeLibrary library, ConnectorData stored, boolean manager) {
    return manager ? stored : null;
  }

  /**
   * The connector settings {@code stored} on {@code library} in comparable form, keyed by the field
   * name the audit records when a value changes.
   */
  default Map<String, Object> settingsState(KnowledgeLibrary library, ConnectorData stored) {
    return Map.of();
  }

  /**
   * Discards the run state a saved change invalidates - {@code addressChanged} for a new {@code
   * sourceUrl}, {@code changedSettings} with the {@link #settingsState} keys that changed.
   */
  default void onSourceChanged(
      KnowledgeLibrary library, boolean addressChanged, Set<String> changedSettings) {}

  /**
   * Probes {@code settings} the way a run would reach the source. A source problem is the result,
   * not an exception; only the caller's own mistake is refused.
   *
   * @param stored the connector settings of the stored library the probe is for, {@code null}
   *     before one exists
   */
  SourceConnectionTestResult testConnection(SourceSettings settings, ConnectorData stored);
}
