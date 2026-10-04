package io.opaa.library;

import io.opaa.api.types.ConnectionAuthMethod;
import io.opaa.connection.profile.ConnectionProfile;
import io.opaa.indexing.source.ConnectorData;
import java.util.UUID;

/**
 * The connection profile of a library as its detail shows it; {@code removed} once the profile was
 * deleted, with every other field then {@code null} ("Zugang entfernt"). What the profile sets for
 * the library - {@link Frame} - only its managers see, and never a secret.
 */
public record LibraryProfileState(UUID id, String name, boolean removed, Frame frame) {

  /** The state of a library whose profile was deleted. */
  public static final LibraryProfileState REMOVED = new LibraryProfileState(null, null, true, null);

  /** {@code profile} as a reader sees it, with its frame for a manager ({@code manager}). */
  public static LibraryProfileState of(ConnectionProfile profile, boolean manager) {
    return new LibraryProfileState(
        profile.getId(),
        profile.getName(),
        false,
        manager
            ? new Frame(
                profile.getServerUrl(),
                profile.getAuthMethod(),
                ConnectorData.fromJson(profile.getConnectorSettings()),
                profile.getSourceProxy(),
                profile.isSourceInsecureSsl())
            : null);
  }

  /** What the profile sets for every library on it, as its managers need it to edit the source. */
  public record Frame(
      String serverUrl,
      ConnectionAuthMethod authMethod,
      ConnectorData connectorDefaults,
      String sourceProxy,
      boolean sourceInsecureSsl) {}
}
