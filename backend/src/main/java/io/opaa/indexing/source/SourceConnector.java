package io.opaa.indexing.source;

import io.opaa.knowledge.KnowledgeLibrary;
import java.util.Map;
import java.util.Set;

/**
 * One kind of library source, registered as a Spring bean in its connector package and reached by
 * the administration only through the {@link SourceConnectorRegistry}. The connector owns the
 * validation of its configuration, the connection test and the state its settings imply; the
 * administration owns permissions, persistence and the audit. Optional abilities are further
 * interfaces the same bean implements ({@link SourceBrowser}).
 *
 * <p>Every method that refuses input throws {@link io.opaa.common.ValidationException} with a
 * German, user-facing message. {@link #validate} and {@link #validateChange} are reached through
 * {@link SourceConnectorRegistry#validateNew} and {@link SourceConnectorRegistry#validateChange}:
 * the settings may still carry fields the connector does not own ({@link
 * SourceConnectorDescriptor#settingFields}), which it ignores - the registry refuses some of them
 * before and every remaining one right after the connector's validation.
 */
public interface SourceConnector {

  SourceConnectorDescriptor descriptor();

  /**
   * Validates the complete configuration of a new library and returns its normalised form - the
   * connection fields as they are stored, the connector-owned fields as {@link #configureNew}
   * applies them.
   */
  SourceSettings validate(SourceSettings requested);

  /**
   * Validates a change of {@code library}'s configuration. The connection fields are only
   * meaningful when {@code replacesConnection}; an absent connector-owned field stays as stored.
   * Returns what {@link #applyChange} applies, the connection fields normalised.
   */
  default SourceSettings validateChange(
      KnowledgeLibrary library, SourceSettings requested, boolean replacesConnection) {
    return replacesConnection ? validate(requested) : requested;
  }

  /** Writes the connector-owned part of {@link #validate}'s result onto an unsaved library. */
  default void configureNew(KnowledgeLibrary library, SourceSettings validated) {}

  /** Writes the connector-owned part of {@link #validateChange}'s result. */
  default void applyChange(KnowledgeLibrary library, SourceSettings validated) {}

  /**
   * The connector-owned settings of {@code library} in comparable form, keyed by the field name the
   * audit records when a value changes.
   */
  default Map<String, Object> settingsState(KnowledgeLibrary library) {
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
   */
  SourceConnectionTestResult testConnection(SourceSettings settings);
}
