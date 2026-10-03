package io.opaa.connection;

import io.opaa.connection.profile.ConnectionProfile;
import io.opaa.connection.profile.ConnectionProfileRepository;
import io.opaa.connection.profile.LibraryConnection;
import io.opaa.connection.profile.LibraryConnectionRepository;
import io.opaa.connection.profile.ServerAddress;
import io.opaa.indexing.source.ConnectorData;
import io.opaa.indexing.source.LibrarySourceConnectionResolver;
import io.opaa.indexing.source.ServiceAccountTokens;
import io.opaa.indexing.source.SourceConnectionBlockedException;
import io.opaa.indexing.source.SourceConnectionBlockedException.Category;
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
 * library without one is resolved from its own fields. With one, the address must lie under the
 * profile's server address, the secret comes only as the profile's sign-in method allows, and the
 * profile's connector defaults override the library's own settings key by key.
 */
@Component
public class ProfileSourceConnectionResolver implements SourceConnectionResolver {

  private static final String CONTENT_STAYS =
      " Der Inhalt bleibt durchsuchbar, wird aber nicht mehr aktualisiert.";

  private final LibraryConnectionRepository connections;
  private final ConnectionProfileRepository profiles;
  private final SourceConnectionResolver ownFields;

  /**
   * A library without profile resolves from its own fields; a connector that signs in with a
   * service account key gets the access token the core signs for (ADR-0040, Entscheidung 2).
   */
  public ProfileSourceConnectionResolver(
      LibraryConnectionRepository connections,
      ConnectionProfileRepository profiles,
      ObjectProvider<SourceConnectorRegistry> registry,
      ServiceAccountTokens serviceAccountTokens) {
    this.connections = connections;
    this.profiles = profiles;
    this.ownFields =
        new LibrarySourceConnectionResolver(
            type -> registry.getObject().find(type), serviceAccountTokens);
  }

  @Override
  public SourceSettings resolve(KnowledgeLibrary library) {
    Optional<ConnectionProfile> profile = requireProfile(library);
    if (profile.isEmpty()) {
      return ownFields.resolve(library);
    }
    return new SourceSettings(
        library.getSourcePath(),
        library.getSourceUrl(),
        library.getSourceProxy(),
        secretOf(library, profile.get()),
        library.isSourceInsecureSsl(),
        merged(profile.get(), library));
  }

  @Override
  public SourceSettings resolveForChange(KnowledgeLibrary library) {
    return requireProfile(library).isEmpty()
        ? ownFields.resolveForChange(library)
        : resolve(library);
  }

  @Override
  public String currentCredentials(KnowledgeLibrary library) {
    Optional<ConnectionProfile> profile = requireProfile(library);
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

  /**
   * The library's profile, empty for a library without a connection.
   *
   * @throws SourceConnectionBlockedException when the profile is gone or the address left it
   */
  private Optional<ConnectionProfile> requireProfile(KnowledgeLibrary library) {
    Optional<LibraryConnection> connection = connections.findById(library.getId());
    if (connection.isEmpty()) {
      return Optional.empty();
    }
    ConnectionProfile profile =
        Optional.ofNullable(connection.get().getProfileId())
            .flatMap(profiles::findById)
            .orElseThrow(
                () ->
                    new SourceConnectionBlockedException(
                        Category.ACCESS_REMOVED,
                        "Zugang entfernt: Der Zugang dieser Bibliothek wurde gelöscht. Die"
                            + " Verwaltenden der Bibliothek ordnen sie einem anderen Zugang zu."
                            + CONTENT_STAYS));
    if (!ServerAddress.covers(profile.getServerUrl(), library.getSourceUrl())) {
      throw new SourceConnectionBlockedException(
          Category.TARGET_OUTSIDE_PROFILE,
          "Die Adresse der Bibliothek liegt nicht unter der Server-Adresse des Zugangs \""
              + profile.getName()
              + "\". Die Verwaltenden der Bibliothek passen die Adresse an."
              + CONTENT_STAYS);
    }
    return Optional.of(profile);
  }

  private static String secretOf(KnowledgeLibrary library, ConnectionProfile profile) {
    return switch (profile.getAuthMethod()) {
      case NONE -> null;
      case PERSONAL_SECRET -> {
        String secret = library.getSourceCredentials();
        if (secret == null) {
          throw new SourceConnectionBlockedException(
              Category.NOT_CONNECTED,
              "Verbindung getrennt: Für den Zugang \""
                  + profile.getName()
                  + "\" sind keine Zugangsdaten hinterlegt. Die Verwaltenden der Bibliothek"
                  + " tragen sie neu ein."
                  + CONTENT_STAYS);
        }
        yield secret;
      }
      case OAUTH, CLIENT_CREDENTIALS, SERVICE_ACCOUNT_KEY ->
          throw new SourceConnectionBlockedException(
              Category.NOT_CONNECTED,
              "Nicht verbunden: Die Anmeldeart des Zugangs \""
                  + profile.getName()
                  + "\" wird für Bibliotheken noch nicht unterstützt. Zuständig ist die"
                  + " Systemverwaltung."
                  + CONTENT_STAYS);
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
