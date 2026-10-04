package io.opaa.connection;

import io.opaa.connection.profile.ConnectionProfile;
import io.opaa.connection.profile.ConnectionProfileRepository;
import io.opaa.connection.profile.LibraryConnection;
import io.opaa.connection.profile.LibraryConnectionRepository;
import io.opaa.connection.profile.SourceBlocks;
import io.opaa.indexing.source.ConnectorData;
import io.opaa.indexing.source.LibrarySourceConnectionResolver;
import io.opaa.indexing.source.ServiceAccountTokens;
import io.opaa.indexing.source.SourceConnectionResolver;
import io.opaa.indexing.source.SourceConnectorRegistry;
import io.opaa.indexing.source.SourceSettings;
import io.opaa.knowledge.KnowledgeLibrary;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * The core's port, answered from the library's connection profile (ADR-0041, Entscheidung 3). A
 * library without one is resolved from its own fields. With one, the secret comes only as the
 * profile's sign-in method allows, and the profile's connector defaults override the library's own
 * settings key by key. Whether a library is blocked decides {@link SourceBlocks}.
 *
 * <p>A lock of the type or the profile blocks {@link #resolve}, which starts a run or fetches an
 * original; {@link #currentCredentials} and {@link #resolveForChange} do not, so a run already
 * going ends regularly and a locked library can still be repaired.
 */
@Component
public class ProfileSourceConnectionResolver implements SourceConnectionResolver {

  private final LibraryConnectionRepository connections;
  private final ConnectionProfileRepository profiles;
  private final SourceBlocks blocks;
  private final SourceConnectionResolver ownFields;

  /**
   * A library without profile resolves from its own fields; a connector that signs in with a
   * service account key gets the access token the core signs for (ADR-0040, Entscheidung 2).
   */
  public ProfileSourceConnectionResolver(
      LibraryConnectionRepository connections,
      ConnectionProfileRepository profiles,
      ObjectProvider<SourceConnectorRegistry> registry,
      ServiceAccountTokens serviceAccountTokens,
      SourceBlocks blocks) {
    this.connections = connections;
    this.profiles = profiles;
    this.blocks = blocks;
    this.ownFields =
        new LibrarySourceConnectionResolver(
            type -> registry.getObject().find(type), serviceAccountTokens);
  }

  @Override
  public SourceSettings resolve(KnowledgeLibrary library) {
    Optional<ConnectionProfile> profile = blocks.requireUnblocked(library, SourceBlocks.ALL);
    return profile.isEmpty() ? ownFields.resolve(library) : fromProfile(library, profile.get());
  }

  @Override
  public boolean isLocked(KnowledgeLibrary library) {
    return blocks.blockOf(library, SourceBlocks.LOCKS).isPresent();
  }

  @Override
  public SourceSettings resolveForChange(KnowledgeLibrary library) {
    Optional<ConnectionProfile> profile = blocks.requireUnblocked(library, SourceBlocks.CONNECTION);
    return profile.isEmpty()
        ? ownFields.resolveForChange(library)
        : fromProfile(library, profile.get());
  }

  @Override
  public String currentCredentials(KnowledgeLibrary library) {
    Optional<ConnectionProfile> profile = blocks.requireUnblocked(library, SourceBlocks.CONNECTION);
    return profile.isEmpty()
        ? ownFields.currentCredentials(library)
        : secretOf(library, profile.get());
  }

  @Override
  public ConnectorData effectiveSettings(KnowledgeLibrary library) {
    return connections
        .findById(library.getId())
        .map(LibraryConnection::getProfileId)
        .flatMap(profiles::findById)
        .map(profile -> merged(profile, library))
        .orElseGet(() -> ConnectorData.storedIn(library));
  }

  private static SourceSettings fromProfile(KnowledgeLibrary library, ConnectionProfile profile) {
    return new SourceSettings(
        library.getSourcePath(),
        library.getSourceUrl(),
        library.getSourceProxy(),
        secretOf(library, profile),
        library.isSourceInsecureSsl(),
        merged(profile, library));
  }

  /** The secret of a library its profile does not block: none, or the library's own. */
  private static String secretOf(KnowledgeLibrary library, ConnectionProfile profile) {
    return switch (profile.getAuthMethod()) {
      case NONE -> null;
      case PERSONAL_SECRET -> library.getSourceCredentials();
      case OAUTH, CLIENT_CREDENTIALS, SERVICE_ACCOUNT_KEY ->
          throw new IllegalStateException(
              "Sign-in method " + profile.getAuthMethod() + " passed the source blocks");
    };
  }

  /** The library's own settings with every key the profile sets replaced by the profile's value. */
  private static ConnectorData merged(ConnectionProfile profile, KnowledgeLibrary library) {
    ConnectorData own = ConnectorData.storedIn(library);
    ConnectorData defaults = ConnectorData.fromJson(profile.getConnectorSettings());
    if (defaults == null) {
      return own;
    }
    Map<String, Object> values = new LinkedHashMap<>();
    if (own != null) {
      values.putAll(own.asMap());
    }
    values.putAll(defaults.asMap());
    return ConnectorData.of(values);
  }
}
