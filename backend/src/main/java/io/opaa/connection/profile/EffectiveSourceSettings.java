package io.opaa.connection.profile;

import io.opaa.api.types.ConnectionAuthMethod;
import io.opaa.common.ValidationException;
import io.opaa.connection.token.ConnectionSecrets;
import io.opaa.connection.token.SecretOwner;
import io.opaa.connection.token.SecretOwner.LibraryOwned;
import io.opaa.connection.token.SecretOwner.PersonOwned;
import io.opaa.connection.token.SecretOwner.ProfileOwned;
import io.opaa.connection.token.SecretRefusedException;
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
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * The one composition of a source configuration, for a stored library and for a draft alike: its
 * own fields, under a profile the profile's proxy, TLS switch and defaults over them, and the
 * secret its {@link SecretOwner} holds as the profile's sign-in method allows - for the profile's
 * own sign-in its access token. Without a profile the own fields and secret stand; a service
 * account key is exchanged by the core (ADR-0040), never handed to a connector.
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
    return compose(Own.of(library), profile.map(this::frame), secretFor(library, profile, purpose));
  }

  /**
   * The configuration a probe or a new library reaches the source with, before anything is saved.
   * The stored secret of the draft's library stands in for an omitted one only while its {@link
   * SecretTarget} stays the same; without a profile the library's own proxy and TLS switch then
   * travel with it. An uploaded service account key, and under a profile with its own sign-in the
   * profile's registration, reaches the connector as its access token. A stored library's blocks do
   * not refuse a draft.
   *
   * @throws ValidationException (German 400) for a value the draft's profile sets otherwise or an
   *     address outside it
   * @throws SourceCredentialsException when a service account key or the profile's sign-in is
   *     refused
   */
  public SourceSettings ofDraft(SourceDraft draft) {
    return ofDraft(draft, true);
  }

  /**
   * {@link #ofDraft} for the validation of a library before it is saved: the profile's own sign-in
   * is not performed, so saving reaches no provider.
   */
  public SourceSettings ofDraftToValidate(SourceDraft draft) {
    return ofDraft(draft, false);
  }

  private SourceSettings ofDraft(SourceDraft draft, boolean signIn) {
    KnowledgeLibrary library = libraryOf(draft);
    Optional<ProfileFrame> frame = frameOf(draft);
    SourceSettings requested =
        draft.requested().sourceCredentials() == null
                || draft.requested().sourceCredentials().isBlank()
            ? draft.requested().withoutCredentials()
            : draft.requested();
    frame.ifPresent(
        found -> {
          found.requireFits(requested);
          found.requireNoForeignSecret(requested);
        });
    String url = addressOf(requested, frame);
    SourceConnector connector = registry.getObject().connector(draft.type());
    Own own = Own.of(requested.withSourceUrl(url));
    boolean keepsStored =
        library != null
            && requested.sourceCredentials() == null
            && keepsStoredSecret(connector, library, frame, url, requested.connectorSettings());
    if (frame.isEmpty() && keepsStored) {
      own = own.withTransport(Own.of(library).transport());
    }
    Secret secret;
    if (frame.isPresent()) {
      secret = draftSecretUnder(frame.get(), requested, keepsStored ? library : null, signIn);
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
   * {@code draft}'s requested change of its library as the library's profile frames it, composed
   * like {@link #of}; absent connector settings stay absent, so the stored ones stand.
   *
   * @throws ValidationException (German 400) for a value the profile sets otherwise, or a new
   *     secret the profile's sign-in does not take
   */
  public SourceSettings ofChange(SourceDraft draft) {
    Optional<ProfileFrame> frame = frameOf(draft);
    SourceSettings requested = draft.requested();
    frame.ifPresent(
        found -> {
          found.requireFits(requested);
          if (draft.libraryId() != null
              && requested.sourceCredentials() != null
              && !requested
                  .sourceCredentials()
                  .equals(secrets.stored(ownerOn(found.profile(), libraryOf(draft))))) {
            found.requireNoForeignSecret(requested);
          }
        });
    SourceSettings composed = compose(Own.of(requested), frame, requested.credentials());
    return requested.connectorSettings() == null ? composed.withConnectorSettings(null) : composed;
  }

  /**
   * Connecting {@code library} through {@code profile} adopts the profile's frame: its own proxy,
   * TLS switch and values under bound keys go, and so does its secret where the profile takes none.
   */
  public void adoptFrame(KnowledgeLibrary library, ConnectionProfile profile) {
    ProfileFrame frame = frame(profile);
    ConnectorData own = frame.ownSettings(ConnectorData.storedIn(library));
    library.updateSourceSettings(own == null ? null : own.toJson());
    library.dropTransport();
    if (!frame.takesSecret()) {
      secrets.discard(new LibraryOwned(library.getId()));
    }
  }

  /**
   * Releasing {@code library} from {@code profile} makes the profile's frame its own: the defaults
   * join its connector settings, and proxy and TLS switch become the library's, so the
   * configuration it runs with stays the same.
   */
  public void releaseFrame(KnowledgeLibrary library, ConnectionProfile profile) {
    ProfileFrame frame = frame(profile);
    ConnectorData settings = frame.over(ConnectorData.storedIn(library));
    library.updateSourceSettings(settings == null ? null : settings.toJson());
    TransportRules transport = frame.serverTransport();
    library.replaceTransport(transport.proxy(), transport.insecureSsl());
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

  /**
   * The secret to retry with after the source rejected the one {@code library} was reached with: a
   * profile's own token is obtained anew, every other secret is read as {@link #currentSecret}.
   *
   * @throws SourceConnectionBlockedException when what ends a running run applies
   */
  public Secret secretAfterRejection(KnowledgeLibrary library) {
    Optional<ConnectionProfile> profile = profileFor(library, Purpose.CHANGE);
    if (profile.isPresent() && ownerOn(profile.get(), library) instanceof ProfileOwned owner) {
      return secretOf(owner, profile.get(), library, true);
    }
    return secretFor(library, profile, Purpose.RUN);
  }

  /** The owner of the secret {@code library} is reached with, as its connection names it. */
  public SecretOwner secretOwnerOf(KnowledgeLibrary library) {
    return connections
        .findById(library.getId())
        .map(LibraryConnection::getProfileId)
        .flatMap(profiles::findById)
        .map(profile -> ownerOn(profile, library))
        .orElseGet(() -> SecretOwner.of(null, null, library));
  }

  private static SecretOwner ownerOn(ConnectionProfile profile, KnowledgeLibrary library) {
    return SecretOwner.of(profile.getId(), profile.getAuthMethod(), library);
  }

  private Optional<ConnectionProfile> profileFor(KnowledgeLibrary library, Purpose purpose) {
    if (purpose == Purpose.SETTINGS_ONLY) {
      return currentProfile(library);
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
    ConnectionProfile found = profile.get();
    SecretOwner owner = ownerOn(found, library);
    return switch (found.getAuthMethod()) {
      case NONE -> null;
      case PERSONAL_SECRET, CLIENT_CREDENTIALS, SERVICE_ACCOUNT_KEY ->
          secretOf(owner, found, library, false);
      case OAUTH -> {
        if (owner instanceof PersonOwned) {
          yield secretOf(owner, found, library, false);
        }
        throw new IllegalStateException(
            "Sign-in method " + found.getAuthMethod() + " passed the source blocks");
      }
    };
  }

  /**
   * The secret {@code owner} holds on {@code profile} now, {@code afterRejection} the one to retry
   * with.
   *
   * @throws SourceConnectionBlockedException with the store's reason, worded by {@link
   *     SourceBlocks}
   */
  private Secret secretOf(
      SecretOwner owner,
      ConnectionProfile profile,
      KnowledgeLibrary library,
      boolean afterRejection) {
    if (SourceBlocks.withoutPersons(profile, owner)) {
      throw new SourceConnectionBlockedException(
          SourceBlocks.secretBlock(Reason.NOT_CONNECTED, profile, owner));
    }
    String target = targetOf(library, profile).key();
    try {
      return afterRejection
          ? secrets.afterRejection(owner, target)
          : secrets.current(owner, target);
    } catch (SecretRefusedException e) {
      throw new SourceConnectionBlockedException(
          SourceBlocks.secretBlock(e.reason(), profile, owner));
    }
  }

  /**
   * A draft's secret under a profile, with its kind: for a personal secret the sent one, else the
   * stored one of {@code keeping}; for the profile's own sign-in its access token where {@code
   * signIn}; none for OAuth, which takes no secret of the library yet.
   */
  private Secret draftSecretUnder(
      ProfileFrame frame, SourceSettings requested, KnowledgeLibrary keeping, boolean signIn) {
    ConnectionProfile profile = frame.profile();
    return switch (profile.getAuthMethod()) {
      case NONE, OAUTH -> null;
      case PERSONAL_SECRET -> {
        if (requested.sourceCredentials() != null) {
          yield requested.credentials();
        }
        yield keeping == null ? null : storedSecretFor(profile, keeping);
      }
      case CLIENT_CREDENTIALS, SERVICE_ACCOUNT_KEY -> signIn ? profileToken(profile) : null;
    };
  }

  /**
   * The access token of {@code profile}'s own sign-in for a probe.
   *
   * @throws SourceCredentialsException with the notice why none is handed out
   */
  private Secret profileToken(ConnectionProfile profile) {
    ProfileOwned owner = new ProfileOwned(profile.getId());
    try {
      return secrets.current(owner, null);
    } catch (SecretRefusedException e) {
      throw new SourceCredentialsException(
          SourceBlocks.secretBlock(e.reason(), profile, owner).notice());
    }
  }

  /**
   * The secret {@code library} holds under {@code profile}, as a run would get it; a missing one is
   * {@code null}, so the probe reports the failed sign-in instead of a refusal.
   */
  private Secret storedSecretFor(ConnectionProfile profile, KnowledgeLibrary library) {
    try {
      return secretOf(ownerOn(profile, library), profile, library, false);
    } catch (SourceConnectionBlockedException e) {
      if (e.block().reason() == Reason.NOT_CONNECTED) {
        return null;
      }
      throw e;
    }
  }

  /**
   * The library's stored secret stands for {@code url} with {@code requested} connector settings
   * under {@code frame}: the target, as {@link SecretTarget} reads it, stays the same.
   */
  private boolean keepsStoredSecret(
      SourceConnector connector,
      KnowledgeLibrary library,
      Optional<ProfileFrame> frame,
      String url,
      ConnectorData requested) {
    Own stored = Own.of(library);
    SourceSettings before = compose(stored, currentFrame(library), null);
    SourceSettings after =
        compose(
            new Own(
                stored.path(),
                url,
                stored.transport(),
                requested != null ? requested : stored.settings()),
            frame,
            null);
    return SecretTarget.of(connector, before).admits(SecretTarget.of(connector, after));
  }

  /**
   * The key of the target a person's secret on {@code profile} is issued for: the profile's server
   * address under its frame, as {@link SecretTarget} reads it - the same key a private library on
   * the profile asks with while its address keeps the origin and its connector the binding.
   */
  public String personTarget(ConnectionProfile profile) {
    return SecretTarget.of(
            registry.getObject().connector(profile.getSourceType()),
            compose(
                new Own(null, profile.getServerUrl(), new TransportRules(null, false), null),
                Optional.of(frame(profile)),
                null))
        .key();
  }

  /** The target of the secret {@code library} holds under {@code profile}. */
  private SecretTarget targetOf(KnowledgeLibrary library, ConnectionProfile profile) {
    return SecretTarget.of(
        registry.getObject().connector(library.getSourceType()),
        compose(Own.of(library), Optional.of(frame(profile)), null));
  }

  private Optional<ProfileFrame> currentFrame(KnowledgeLibrary library) {
    return currentProfile(library).map(this::frame);
  }

  /** The profile {@code library} is connected through now, empty for its own address. */
  Optional<ConnectionProfile> currentProfile(KnowledgeLibrary library) {
    return connections
        .findById(library.getId())
        .map(LibraryConnection::getProfileId)
        .flatMap(profiles::findById);
  }

  /**
   * {@code library}'s effective configuration at {@code address} under {@code profile} - empty for
   * its own address, also one not saved yet - with {@code secret}; no block refuses it. The one
   * composition, for a transition between frames.
   */
  SourceSettings framed(
      KnowledgeLibrary library,
      Optional<ConnectionProfile> profile,
      String address,
      Secret secret) {
    Own own = Own.of(library);
    return compose(
        new Own(own.path(), address, own.transport(), own.settings()),
        profile.map(this::frame),
        secret);
  }

  /**
   * The secret {@code library} holds under {@code profile} - empty for its own address - as a
   * change sees it, {@code null} for none or a missing one; no block refuses it and no key is
   * signed.
   */
  Secret heldSecret(KnowledgeLibrary library, Optional<ConnectionProfile> profile) {
    if (profile.isEmpty()) {
      return ownFields.resolveForChange(library).credentials();
    }
    return profile.get().getAuthMethod() == ConnectionAuthMethod.PERSONAL_SECRET
        ? storedSecretFor(profile.get(), library)
        : null;
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
          frame(
              ProfileAdmission.require(
                  profiles.findById(draft.profileId()),
                  draft.type(),
                  registry.getObject().descriptor(draft.type()),
                  draft.owner())));
    }
    if (draft.libraryId() == null) {
      return Optional.empty();
    }
    UUID current =
        connections.findById(draft.libraryId()).map(LibraryConnection::getProfileId).orElse(null);
    return current == null ? Optional.empty() : profiles.findById(current).map(this::frame);
  }

  /** The frame of {@code profile}, its bound keys as its connector declares them. */
  private ProfileFrame frame(ConnectionProfile profile) {
    return ProfileFrame.of(
        profile,
        registry.getObject().descriptor(profile.getSourceType()).profileDeclaration().defaults());
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

  /**
   * Own fields, the profile's frame over them and {@code secret}: the one composition. Under a
   * profile only its transport reaches the address; it is the address's, not any other target's.
   */
  private static SourceSettings compose(Own own, Optional<ProfileFrame> frame, Secret secret) {
    TransportRules toAddress = frame.map(ProfileFrame::serverTransport).orElse(own.transport());
    return new SourceSettings(
            own.path(),
            own.url(),
            toAddress.proxy(),
            null,
            toAddress.insecureSsl(),
            frame.map(found -> found.over(own.settings())).orElse(own.settings()))
        .withCredentials(secret);
  }

  /** The fields a library or a draft brings itself, before a profile frames them. */
  private record Own(String path, String url, TransportRules transport, ConnectorData settings) {

    static Own of(KnowledgeLibrary library) {
      return new Own(
          library.getSourcePath(),
          library.getSourceUrl(),
          new TransportRules(library.getSourceProxy(), library.isSourceInsecureSsl()),
          ConnectorData.storedIn(library));
    }

    static Own of(SourceSettings requested) {
      return new Own(
          requested.sourcePath(),
          requested.sourceUrl(),
          new TransportRules(requested.sourceProxy(), requested.sourceInsecureSsl()),
          requested.connectorSettings());
    }

    Own withTransport(TransportRules rules) {
      return new Own(path, url, rules, settings);
    }
  }
}
