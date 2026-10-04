package io.opaa.connection.profile;

import io.opaa.indexing.source.ConnectorData;
import io.opaa.indexing.source.LibrarySourceConnectionResolver;
import io.opaa.indexing.source.Secret;
import io.opaa.indexing.source.ServiceAccountTokens;
import io.opaa.indexing.source.SourceBlock.Reason;
import io.opaa.indexing.source.SourceConnectionBlockedException;
import io.opaa.indexing.source.SourceConnectorRegistry;
import io.opaa.indexing.source.SourceSettings;
import io.opaa.knowledge.KnowledgeLibrary;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * The one composition of a library's effective source configuration: its own fields, under its
 * profile its connector settings overlaid key by key by the profile's defaults, and the secret its
 * {@link SecretOwner} holds as the profile's sign-in method allows. A library without a profile
 * keeps its own fields and secret; a service account key is exchanged by the core (ADR-0040).
 */
@Component
public class EffectiveSourceSettings {

  /**
   * What the configuration is for, and therefore which blocks refuse it and whether it is secret.
   */
  public enum Purpose {
    /** A run's start or an original's fetch: every block refuses, the secret is valid now. */
    RUN(SourceBlocks.ALL),
    /** The base a change is validated against: what ends a running run refuses. */
    CHANGE(SourceBlocks.ENDING_A_RUNNING_RUN),
    /** The connector settings alone: nothing refuses, no secret. */
    SETTINGS_ONLY(Set.of());

    private final Set<Reason> refusedBy;

    Purpose(Set<Reason> refusedBy) {
      this.refusedBy = refusedBy;
    }
  }

  private final LibraryConnectionRepository connections;
  private final ConnectionProfileRepository profiles;
  private final SourceBlocks blocks;
  private final ConnectionSecrets secrets;
  private final LibrarySourceConnectionResolver ownFields;

  public EffectiveSourceSettings(
      LibraryConnectionRepository connections,
      ConnectionProfileRepository profiles,
      SourceBlocks blocks,
      ConnectionSecrets secrets,
      ObjectProvider<SourceConnectorRegistry> registry,
      ServiceAccountTokens serviceAccountTokens) {
    this.connections = connections;
    this.profiles = profiles;
    this.blocks = blocks;
    this.secrets = secrets;
    this.ownFields =
        new LibrarySourceConnectionResolver(
            type -> registry.getObject().find(type), serviceAccountTokens);
  }

  /**
   * {@code library}'s effective configuration for {@code purpose}.
   *
   * @throws SourceConnectionBlockedException when a block {@code purpose} heeds applies
   */
  public SourceSettings of(KnowledgeLibrary library, Purpose purpose) {
    Optional<ConnectionProfile> profile = profileFor(library, purpose);
    return compose(library, profile, secretFor(library, profile, purpose));
  }

  /**
   * The secret {@code library} is reached with now, {@code null} for none; no lock refuses it.
   *
   * @throws SourceConnectionBlockedException when what ends a running run applies
   */
  public Secret currentSecret(KnowledgeLibrary library) {
    return secretFor(library, profileFor(library, Purpose.CHANGE), Purpose.RUN);
  }

  private Optional<ConnectionProfile> profileFor(KnowledgeLibrary library, Purpose purpose) {
    if (purpose == Purpose.SETTINGS_ONLY) {
      return connections
          .findById(library.getId())
          .map(LibraryConnection::getProfileId)
          .flatMap(profiles::findById);
    }
    return blocks.requireUnblocked(library, purpose.refusedBy);
  }

  private Secret secretFor(
      KnowledgeLibrary library, Optional<ConnectionProfile> profile, Purpose purpose) {
    if (purpose == Purpose.SETTINGS_ONLY) {
      return null;
    }
    if (profile.isEmpty()) {
      return purpose == Purpose.RUN
          ? ownFields.currentSecret(library)
          : ownFields.resolveForChange(library).credentials();
    }
    return switch (profile.get().getAuthMethod()) {
      case NONE -> null;
      case PERSONAL_SECRET -> secrets.current(SecretOwner.of(library), library.getSourceUrl());
      case OAUTH, CLIENT_CREDENTIALS, SERVICE_ACCOUNT_KEY ->
          throw new IllegalStateException(
              "Sign-in method " + profile.get().getAuthMethod() + " passed the source blocks");
    };
  }

  /** Own fields, the merged connector settings and {@code secret}: the one composition. */
  private static SourceSettings compose(
      KnowledgeLibrary library, Optional<ConnectionProfile> profile, Secret secret) {
    return new SourceSettings(
            library.getSourcePath(),
            library.getSourceUrl(),
            library.getSourceProxy(),
            null,
            library.isSourceInsecureSsl(),
            profile
                .map(found -> merged(found, library))
                .orElseGet(() -> ConnectorData.storedIn(library)))
        .withCredentials(secret);
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
