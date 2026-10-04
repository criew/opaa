package io.opaa.connection.profile;

import io.opaa.common.ValidationException;
import io.opaa.indexing.source.ConnectorData;
import io.opaa.indexing.source.LibrarySourceConnectionResolver;
import io.opaa.indexing.source.Secret;
import io.opaa.indexing.source.SecretKind;
import io.opaa.indexing.source.ServiceAccountKey;
import io.opaa.indexing.source.ServiceAccountTokens;
import io.opaa.indexing.source.SourceBlock.Reason;
import io.opaa.indexing.source.SourceConnectionBlockedException;
import io.opaa.indexing.source.SourceConnector;
import io.opaa.indexing.source.SourceConnectorRegistry;
import io.opaa.indexing.source.SourceCredentialsException;
import io.opaa.indexing.source.SourceSettings;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.KnowledgeLibraryRepository;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * The one composition of a source configuration, for a stored library and for a draft alike: its
 * own fields, under a profile the profile's proxy, TLS switch and defaults over them, and the
 * secret its {@link SecretOwner} holds as the profile's sign-in method allows. Without a profile
 * the own fields and secret stand; a service account key is exchanged by the core (ADR-0040).
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
  private final KnowledgeLibraryRepository libraries;
  private final SourceBlocks blocks;
  private final ConnectionSecrets secrets;
  private final ObjectProvider<SourceConnectorRegistry> registry;
  private final ServiceAccountTokens serviceAccountTokens;
  private final LibrarySourceConnectionResolver ownFields;

  public EffectiveSourceSettings(
      LibraryConnectionRepository connections,
      ConnectionProfileRepository profiles,
      KnowledgeLibraryRepository libraries,
      SourceBlocks blocks,
      ConnectionSecrets secrets,
      ObjectProvider<SourceConnectorRegistry> registry,
      ServiceAccountTokens serviceAccountTokens) {
    this.connections = connections;
    this.profiles = profiles;
    this.libraries = libraries;
    this.blocks = blocks;
    this.secrets = secrets;
    this.registry = registry;
    this.serviceAccountTokens = serviceAccountTokens;
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
    return compose(
        Own.of(library), profile.map(ProfileFrame::of), secretFor(library, profile, purpose));
  }

  /**
   * The configuration a probe or a new library reaches the source with, before anything is saved.
   * The stored secret of the draft's library stands in for an omitted one only while the address
   * keeps its origin and the connector its binding ({@link ServerAddress#sameOrigin}, {@link
   * SourceConnector#keepsCredentials}); without a profile the library's own proxy and TLS switch
   * then travel with it. An uploaded service account key reaches the connector as its access token.
   * A stored library's blocks do not refuse a draft.
   *
   * @throws ValidationException (German 400) for a value the draft's profile sets otherwise or an
   *     address outside it
   * @throws SourceCredentialsException when a service account key is refused
   */
  public SourceSettings ofDraft(SourceDraft draft) {
    KnowledgeLibrary library = libraryOf(draft);
    Optional<ProfileFrame> frame = frameOf(draft);
    SourceSettings requested =
        draft.requested().sourceCredentials() == null
                || draft.requested().sourceCredentials().isBlank()
            ? draft.requested().withoutCredentials()
            : draft.requested();
    frame.ifPresent(found -> found.requireFits(requested));
    String url = addressOf(requested, frame);
    SourceConnector connector = registry.getObject().connector(draft.type());
    Own own = Own.of(requested.withSourceUrl(url));
    boolean keepsStored =
        library != null
            && requested.sourceCredentials() == null
            && keepsStoredSecret(connector, library, url, requested.connectorSettings());
    if (frame.isEmpty() && keepsStored) {
      own = own.withTransport(library.getSourceProxy(), library.isSourceInsecureSsl());
    }
    Secret secret;
    if (frame.isPresent()) {
      secret = draftSecretUnder(frame.get(), requested, keepsStored ? library : null);
    } else if (keepsStored) {
      secret = ownFields.currentSecret(library);
    } else {
      secret = requested.credentials();
    }
    if (requested.sourceCredentials() != null && signsWithKey(connector)) {
      secret = signedUpload(connector, compose(own, frame, null), requested, library);
    }
    return compose(own, frame, secret);
  }

  /**
   * The connector settings the draft's library carries under the draft's profile, as {@link
   * #ofDraft} would compose them without a request; {@code null} before a library exists.
   */
  public ConnectorData storedOf(SourceDraft draft) {
    KnowledgeLibrary library = libraryOf(draft);
    return library == null
        ? null
        : compose(Own.of(library), frameOf(draft), null).connectorSettings();
  }

  /**
   * {@code draft}'s requested change of its library as the library's profile frames it: proxy, TLS
   * switch and defaults of the profile over the requested fields.
   *
   * @throws ValidationException (German 400) for a value the profile sets otherwise
   */
  public SourceSettings ofChange(SourceDraft draft) {
    Optional<ProfileFrame> frame = frameOf(draft);
    frame.ifPresent(found -> found.requireFits(draft.requested()));
    return frame.map(found -> found.over(draft.requested())).orElse(draft.requested());
  }

  /**
   * What of {@code validated}, the connector's answer to {@code draft}, the library stores itself:
   * without what the draft's profile sets.
   */
  public SourceSettings ownPart(SourceDraft draft, SourceSettings validated) {
    return frameOf(draft).map(found -> found.ownPart(validated)).orElse(validated);
  }

  /**
   * The secret {@code library} is reached with now, {@code null} for none; no lock refuses it.
   *
   * @throws SourceConnectionBlockedException when what ends a running run applies
   */
  public Secret currentSecret(KnowledgeLibrary library) {
    return secretFor(library, profileFor(library, Purpose.CHANGE), Purpose.RUN);
  }

  /** The owner of the secret {@code library} is reached with, as its connection names it. */
  public SecretOwner secretOwnerOf(KnowledgeLibrary library) {
    return SecretOwner.of(
        connections.findById(library.getId()).map(LibraryConnection::getProfileId).orElse(null),
        library);
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
      case PERSONAL_SECRET ->
          secrets.current(
              SecretOwner.of(profile.get().getId(), library),
              library.getSourceUrl(),
              profile.get().getName());
      case OAUTH, CLIENT_CREDENTIALS, SERVICE_ACCOUNT_KEY ->
          throw new IllegalStateException(
              "Sign-in method " + profile.get().getAuthMethod() + " passed the source blocks");
    };
  }

  /**
   * A draft's secret under a profile: none without sign-in, else the sent one or, for a personal
   * secret, the stored one of {@code keeping}. No library signs in by app registration yet.
   */
  private Secret draftSecretUnder(
      ProfileFrame frame, SourceSettings requested, KnowledgeLibrary keeping) {
    ConnectionProfile profile = frame.profile();
    return switch (profile.getAuthMethod()) {
      case NONE -> null;
      case PERSONAL_SECRET -> {
        if (requested.sourceCredentials() != null) {
          yield requested.credentials();
        }
        yield keeping == null
            ? null
            : Secret.personal(secrets.stored(SecretOwner.of(profile.getId(), keeping)));
      }
      case OAUTH, CLIENT_CREDENTIALS, SERVICE_ACCOUNT_KEY -> requested.credentials();
    };
  }

  /** The library's stored secret stands for {@code url}: same origin, binding and subject. */
  private boolean keepsStoredSecret(
      SourceConnector connector, KnowledgeLibrary library, String url, ConnectorData requested) {
    ConnectorData stored = ConnectorData.storedIn(library);
    return ServerAddress.sameOrigin(library.getSourceUrl(), url)
        && connector.keepsCredentials(library.getSourceUrl(), url)
        && Objects.equals(
            ServiceAccountTokens.subjectOf(connector, stored),
            ServiceAccountTokens.subjectOf(connector, requested != null ? requested : stored));
  }

  /** The access token an uploaded service account key is exchanged for, {@code null} for none. */
  private Secret signedUpload(
      SourceConnector connector,
      SourceSettings composed,
      SourceSettings requested,
      KnowledgeLibrary library) {
    String key = ServiceAccountKey.parse(requested.sourceCredentials()).storedForm();
    ConnectorData settings =
        composed.connectorSettings() != null
            ? composed.connectorSettings()
            : library == null ? null : ConnectorData.storedIn(library);
    String token =
        serviceAccountTokens.forConnector(
            connector,
            composed.withCredentials(new Secret(SecretKind.SERVICE_ACCOUNT_KEY, key)),
            settings);
    return token == null ? null : new Secret(SecretKind.ACCESS_TOKEN, token);
  }

  private static boolean signsWithKey(SourceConnector connector) {
    return connector.descriptor().profileDeclaration().serviceAccountKey() != null;
  }

  private KnowledgeLibrary libraryOf(SourceDraft draft) {
    return draft.libraryId() == null
        ? null
        : libraries
            .findById(draft.libraryId())
            .orElseThrow(() -> new IllegalArgumentException("unknown library of a draft"));
  }

  /** The draft's profile: as named and admitting it, else the library's current one, if any. */
  private Optional<ProfileFrame> frameOf(SourceDraft draft) {
    if (draft.profileId() != null) {
      return Optional.of(
          ProfileFrame.of(
              ProfileAdmission.require(
                  profiles.findById(draft.profileId()),
                  draft.type(),
                  registry.getObject().descriptor(draft.type()))));
    }
    if (draft.libraryId() == null) {
      return Optional.empty();
    }
    UUID current =
        connections.findById(draft.libraryId()).map(LibraryConnection::getProfileId).orElse(null);
    return current == null ? Optional.empty() : profiles.findById(current).map(ProfileFrame::of);
  }

  /** The requested address, under a profile its server address when omitted, and lying under it. */
  private static String addressOf(SourceSettings requested, Optional<ProfileFrame> frame) {
    if (frame.isEmpty()) {
      return requested.sourceUrl();
    }
    String serverUrl = frame.get().profile().getServerUrl();
    String url = requested.sourceUrl() == null ? serverUrl : requested.sourceUrl();
    if (!ServerAddress.covers(serverUrl, url)) {
      throw new ValidationException(
          "Die Adresse muss unter der Server-Adresse des Zugangs liegen: " + serverUrl);
    }
    return url;
  }

  /** Own fields, the profile's frame over them and {@code secret}: the one composition. */
  private static SourceSettings compose(Own own, Optional<ProfileFrame> frame, Secret secret) {
    return new SourceSettings(
            own.path(),
            own.url(),
            frame.map(ProfileFrame::proxy).orElse(own.proxy()),
            null,
            frame.map(ProfileFrame::insecureSsl).orElse(own.insecureSsl()),
            frame.map(found -> found.over(own.settings())).orElse(own.settings()))
        .withCredentials(secret);
  }

  /** The fields a library or a draft brings itself, before a profile frames them. */
  private record Own(
      String path, String url, String proxy, boolean insecureSsl, ConnectorData settings) {

    static Own of(KnowledgeLibrary library) {
      return new Own(
          library.getSourcePath(),
          library.getSourceUrl(),
          library.getSourceProxy(),
          library.isSourceInsecureSsl(),
          ConnectorData.storedIn(library));
    }

    static Own of(SourceSettings requested) {
      return new Own(
          requested.sourcePath(),
          requested.sourceUrl(),
          requested.sourceProxy(),
          requested.sourceInsecureSsl(),
          requested.connectorSettings());
    }

    Own withTransport(String proxy, boolean insecureSsl) {
      return new Own(path, url, proxy, insecureSsl, settings);
    }
  }
}
