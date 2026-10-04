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
   * The top-level keys the connector settings may carry - the only keys a profile default may name.
   * None by default, matching the default {@link #readSettings}.
   */
  default Set<String> settingsKeys() {
    return Set.of();
  }

  /**
   * Checks the values of a profile's connector defaults beyond their kind; {@code read} has passed
   * {@link ProfileDefaults#read} already and is never {@code null}. Returns what the profile keeps;
   * by default {@code read} unchanged.
   */
  default ConnectorData readProfileDefaults(ConnectorData read) {
    return read;
  }

  /**
   * The address {@link #validate} stores for {@code requested}, {@code null} included - what the
   * core compares against the stored origin before it keeps stored credentials. A connector with a
   * fixed target returns it here; by default the request is taken as sent.
   */
  default String normalizeSourceUrl(String requested) {
    return requested;
  }

  /**
   * Whether stored credentials, kept while {@code requestedSourceUrl} names the same origin as
   * {@code storedSourceUrl}, also stand for it - a narrower binding than scheme, host and port,
   * such as the share of a file server. By default the origin is all that counts.
   */
  default boolean keepsCredentials(String storedSourceUrl, String requestedSourceUrl) {
    return true;
  }

  /**
   * The account a service account key imitates under {@code settings}, {@code null} for none
   * (ADR-0040, Entscheidung 4). The core signs the assertion with it and keeps stored credentials
   * only while it stays the same. No secret.
   */
  default String assertionSubject(ConnectorData settings) {
    return null;
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
