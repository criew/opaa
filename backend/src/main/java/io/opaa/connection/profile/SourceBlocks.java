package io.opaa.connection.profile;

import io.opaa.api.types.ConnectionAuthMethod;
import io.opaa.connection.token.ConnectionSecrets;
import io.opaa.connection.token.SecretOwner;
import io.opaa.connection.token.SecretOwner.PersonOwned;
import io.opaa.connection.token.SecretOwner.ProfileOwned;
import io.opaa.indexing.source.SourceBlock;
import io.opaa.indexing.source.SourceBlock.Reason;
import io.opaa.indexing.source.SourceConnectionBlockedException;
import io.opaa.indexing.source.SourceConnectorRegistry;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.SourceType;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * The only place that decides whether and why a library's source is blocked. The reasons are tried
 * in their order of declaration in {@link Reason}, and the first that applies is the block; a
 * library without a connection is blocked only by a type lock and by the profile requirement of its
 * type. Callers name the reasons they consider, as one of the sets derived here from the properties
 * of {@link Reason}.
 *
 * <p>Holds no transaction of its own, so a refusal does not mark a caller's transaction for
 * rollback.
 */
@Component
public class SourceBlocks {

  /** Every reason: what refuses the start of a run and the fetch of an original. */
  public static final Set<Reason> ALL = where(reason -> true);

  /** The locks of the system administration ({@link Reason#lock}). */
  public static final Set<Reason> LOCKS = where(Reason::lock);

  /** What refuses a running run and a change ({@link Reason#endsRunningRun}). */
  public static final Set<Reason> ENDING_A_RUNNING_RUN = where(Reason::endsRunningRun);

  /** What an answer marks as not updated ({@link Reason#shownInAnswer}). */
  public static final Set<Reason> SHOWN_IN_ANSWER = where(Reason::shownInAnswer);

  static final String ADMINISTRATION = "Systemverwaltung";
  static final String LIBRARY_MANAGERS = "Verwaltende der Bibliothek";
  static final String OWNER = "Besitzerin der Bibliothek";
  static final String OWNER_OR_ADMINISTRATION = "Besitzerin der Bibliothek bzw. Systemverwaltung";

  /** The reasons the secret store answers; the others follow from rows of this package. */
  private static final Set<Reason> SECRET_REASONS =
      EnumSet.of(Reason.OWNER_DEACTIVATED, Reason.DORMANT, Reason.NOT_CONNECTED, Reason.EXPIRED);

  private static final String ACCOUNTS_PAGE = "auf der Seite „Verbundene Konten“";

  private static final String LOCKED = "Gesperrt – Inhalt wird nicht mehr aktualisiert.";
  private static final String LOCK_CONTENT_STAYS =
      "; der vorhandene Inhalt bleibt durchsuchbar. Zuständig ist die Systemverwaltung.";
  private static final String CONTENT_STAYS =
      " Der Inhalt bleibt durchsuchbar, wird aber nicht mehr aktualisiert.";

  private final ConnectorTypePolicyRepository policies;
  private final LibraryConnectionRepository connections;
  private final ConnectionProfileRepository profiles;
  private final ProfileRequirements requirements;
  private final ConnectionSecrets secrets;

  /**
   * Looked up per call: the core's port depends on this class, and the registry's connectors depend
   * on the core.
   */
  private final ObjectProvider<SourceConnectorRegistry> connectors;

  public SourceBlocks(
      ConnectorTypePolicyRepository policies,
      LibraryConnectionRepository connections,
      ConnectionProfileRepository profiles,
      ProfileRequirements requirements,
      ConnectionSecrets secrets,
      ObjectProvider<SourceConnectorRegistry> connectors) {
    this.policies = policies;
    this.connections = connections;
    this.profiles = profiles;
    this.requirements = requirements;
    this.secrets = secrets;
    this.connectors = connectors;
  }

  /**
   * The block for {@code reason}, one the secret store answers for {@code owner} on {@code
   * profile}: its notice names who acts - the owner of a private library, the system administration
   * for the profile's own sign-in, else the library's managers.
   */
  public static SourceBlock secretBlock(
      Reason reason, ConnectionProfile profile, SecretOwner owner) {
    boolean person = owner instanceof PersonOwned;
    String access = "„" + profile.getName() + "“";
    if (owner instanceof ProfileOwned) {
      return profileSignInBlock(reason, access);
    }
    return switch (reason) {
      case OWNER_DEACTIVATED ->
          new SourceBlock(
              reason,
              ADMINISTRATION,
              "Konto deaktiviert: Das Konto der Besitzerin ist deaktiviert, die Verbindung zum"
                  + " Zugang "
                  + access
                  + " ruht. Der Inhalt wird nicht mehr aktualisiert und nach Ablauf der"
                  + " Löschfrist gelöscht. Zuständig ist die Systemverwaltung.");
      case DORMANT ->
          new SourceBlock(
              reason,
              OWNER_OR_ADMINISTRATION,
              "Ruhend: Das Konto der Besitzerin wird derzeit nicht genutzt, die Verbindung zum"
                  + " Zugang "
                  + access
                  + " ruht. Mit ihrer nächsten Anmeldung geht es ohne Neuverbinden weiter; ist"
                  + " ihr Anmeldeweg abgeschaltet, ist die Systemverwaltung zuständig."
                  + CONTENT_STAYS);
      case EXPIRED ->
          person
              ? new SourceBlock(
                  reason,
                  OWNER,
                  "Abgelaufen: Die Anmeldung beim Zugang "
                      + access
                      + " ist abgelaufen oder wurde vom Anbieter abgelehnt. Die Besitzerin"
                      + " verbindet ihr Konto "
                      + ACCOUNTS_PAGE
                      + " neu."
                      + CONTENT_STAYS)
              : new SourceBlock(
                  reason,
                  LIBRARY_MANAGERS,
                  "Abgelaufen: Die Zugangsdaten für den Zugang "
                      + access
                      + " sind abgelaufen oder wurden vom Anbieter abgelehnt. Die Verwaltenden der"
                      + " Bibliothek tragen sie neu ein."
                      + CONTENT_STAYS);
      case NOT_CONNECTED ->
          person
              ? new SourceBlock(
                  reason,
                  OWNER,
                  "Verbindung getrennt: Für den Zugang "
                      + access
                      + " ist kein verbundenes Konto hinterlegt. Die Besitzerin verbindet ihr"
                      + " Konto "
                      + ACCOUNTS_PAGE
                      + "."
                      + CONTENT_STAYS)
              : new SourceBlock(
                  reason,
                  LIBRARY_MANAGERS,
                  "Verbindung getrennt: Für den Zugang \""
                      + profile.getName()
                      + "\" sind keine Zugangsdaten hinterlegt. Die Verwaltenden der"
                      + " Bibliothek tragen sie neu ein."
                      + CONTENT_STAYS);
      case TARGET_OUTSIDE_PROFILE ->
          new SourceBlock(
              reason,
              person ? OWNER : LIBRARY_MANAGERS,
              "Ziel weicht ab: Die Bibliothek erreicht ein anderes Ziel – Adresse oder Bindung wie"
                  + " eine Freigabe – als das, für das die Anmeldung beim Zugang "
                  + access
                  + " gilt. "
                  + (person
                      ? "Die Besitzerin verbindet ihr Konto "
                          + ACCOUNTS_PAGE
                          + " neu; bleibt der Hinweis, legt sie die Bibliothek mit dem Ziel des"
                          + " Zugangs neu an."
                      : "Die Verwaltenden der Bibliothek tragen die Zugangsdaten neu ein.")
                  + CONTENT_STAYS);
      case TYPE_LOCKED, PROFILE_LOCKED, PROFILE_REQUIRED, ACCESS_REMOVED ->
          throw new IllegalArgumentException(reason + " is no answer of the secret store");
    };
  }

  /** The block of a profile's own sign-in: only the system administration can lift it. */
  private static SourceBlock profileSignInBlock(Reason reason, String access) {
    return switch (reason) {
      case EXPIRED ->
          new SourceBlock(
              reason,
              ADMINISTRATION,
              "Abgelaufen: Der Anbieter hat die Anmeldung des Zugangs "
                  + access
                  + " abgelehnt. Die Systemverwaltung trägt ein neues Client-Secret bzw. einen"
                  + " neuen Schlüssel ein oder testet die Anmeldung am Zugang."
                  + CONTENT_STAYS);
      case NOT_CONNECTED ->
          new SourceBlock(
              reason,
              ADMINISTRATION,
              "Verbindung getrennt: Für den Zugang "
                  + access
                  + " ist kein Client-Secret bzw. Dienstkonto-Schlüssel hinterlegt. Zuständig ist"
                  + " die Systemverwaltung."
                  + CONTENT_STAYS);
      case OWNER_DEACTIVATED,
          DORMANT,
          TYPE_LOCKED,
          PROFILE_LOCKED,
          PROFILE_REQUIRED,
          ACCESS_REMOVED,
          TARGET_OUTSIDE_PROFILE ->
          throw new IllegalArgumentException(reason + " is no state of a profile's own sign-in");
    };
  }

  /** The first of the {@code considered} reasons that blocks {@code library}, empty if none. */
  public Optional<SourceBlock> blockOf(KnowledgeLibrary library, Set<Reason> considered) {
    return Optional.ofNullable(blocksAmong(List.of(library), considered).get(library.getId()));
  }

  /**
   * {@link #blockOf} for many libraries with at most four queries, however many there are; a
   * library not blocked is absent.
   */
  public Map<UUID, SourceBlock> blocksAmong(
      Collection<KnowledgeLibrary> libraries, Set<Reason> considered) {
    Map<UUID, SourceBlock> blocks = new HashMap<>();
    for (Facts facts : factsOf(libraries, considered)) {
      facts.firstBlock(considered).ifPresent(block -> blocks.put(facts.library().getId(), block));
    }
    return blocks;
  }

  /**
   * The profile {@code library} is connected through, empty for its own address, once none of the
   * {@code considered} reasons blocks it.
   *
   * @throws SourceConnectionBlockedException with the first of them that applies
   */
  public Optional<ConnectionProfile> requireUnblocked(
      KnowledgeLibrary library, Set<Reason> considered) {
    Facts facts = factsOf(List.of(library), considered).getFirst();
    facts
        .firstBlock(considered)
        .ifPresent(
            block -> {
              throw new SourceConnectionBlockedException(block);
            });
    return Optional.ofNullable(facts.profile());
  }

  private static Set<Reason> where(Predicate<Reason> property) {
    EnumSet<Reason> reasons = EnumSet.noneOf(Reason.class);
    for (Reason reason : Reason.values()) {
      if (property.test(reason)) {
        reasons.add(reason);
      }
    }
    return Collections.unmodifiableSet(reasons);
  }

  private List<Facts> factsOf(Collection<KnowledgeLibrary> libraries, Set<Reason> considered) {
    if (libraries.isEmpty()) {
      return List.of();
    }
    List<ConnectorTypePolicy> policyRows =
        considered.contains(Reason.TYPE_LOCKED) || considered.contains(Reason.PROFILE_REQUIRED)
            ? policies.findAll()
            : List.of();
    Set<String> lockedTypes =
        considered.contains(Reason.TYPE_LOCKED)
            ? policyRows.stream()
                .filter(ConnectorTypePolicy::isLocked)
                .map(policy -> policy.getSourceType().key())
                .collect(Collectors.toSet())
            : Set.of();
    Set<String> profilesOnlyTypes =
        considered.contains(Reason.PROFILE_REQUIRED)
            ? requirements.lockingOwnAddresses(policyRows)
            : Set.of();
    Map<UUID, LibraryConnection> connectionOf =
        connections.findAllById(libraries.stream().map(KnowledgeLibrary::getId).toList()).stream()
            .collect(Collectors.toMap(LibraryConnection::getLibraryId, Function.identity()));
    Set<UUID> profileIds =
        connectionOf.values().stream()
            .map(LibraryConnection::getProfileId)
            .filter(id -> id != null)
            .collect(Collectors.toSet());
    Map<UUID, ConnectionProfile> profileOf =
        profileIds.isEmpty()
            ? Map.of()
            : profiles.findAllById(profileIds).stream()
                .collect(Collectors.toMap(ConnectionProfile::getId, Function.identity()));
    boolean secretConsidered = considered.stream().anyMatch(SECRET_REASONS::contains);
    Map<UUID, ConnectionProfile> profileOfLibrary = new HashMap<>();
    Map<UUID, SecretOwner> ownerOfLibrary = new HashMap<>();
    for (KnowledgeLibrary library : libraries) {
      LibraryConnection connection = connectionOf.get(library.getId());
      ConnectionProfile profile =
          !LibraryConnection.throughProfile(connection)
              ? null
              : profileOf.get(connection.getProfileId());
      if (profile != null) {
        profileOfLibrary.put(library.getId(), profile);
        SecretOwner owner = SecretOwner.of(profile.getId(), profile.getAuthMethod(), library);
        if (secretConsidered && (asksTheStore(profile, owner) || owner instanceof ProfileOwned)) {
          ownerOfLibrary.put(library.getId(), owner);
        }
      }
    }
    Map<SecretOwner, Reason> secretStates =
        ownerOfLibrary.isEmpty() ? Map.of() : secrets.statesAmong(ownerOfLibrary.values());
    Map<SecretOwner, Reason> states = new HashMap<>(secretStates);
    profileOfLibrary.forEach(
        (libraryId, profile) -> {
          if (ownerOfLibrary.get(libraryId) instanceof ProfileOwned owner) {
            Reason reason = profileSignInState(profile);
            if (reason != null) {
              states.put(owner, reason);
            }
          }
        });
    List<Facts> facts = new ArrayList<>();
    for (KnowledgeLibrary library : libraries) {
      SourceType type = library.getSourceType();
      LibraryConnection connection = connectionOf.get(library.getId());
      SecretOwner owner = ownerOfLibrary.get(library.getId());
      facts.add(
          new Facts(
              library,
              lockedTypes.contains(type.key()) ? typeLock(type) : null,
              !LibraryConnection.throughProfile(connection)
                      && profilesOnlyTypes.contains(type.key())
                  ? profileRequiredLock(type)
                  : null,
              connection != null,
              profileOfLibrary.get(library.getId()),
              owner,
              owner == null
                  ? null
                  : withoutPersons(profileOfLibrary.get(library.getId()), owner)
                      ? Reason.NOT_CONNECTED
                      : states.get(owner)));
    }
    return facts;
  }

  /**
   * Why the profile's own sign-in hands out nothing, read from its row without asking the provider:
   * no secret stored, or a registration the provider rejected; {@code null} while it signs in.
   */
  private static Reason profileSignInState(ConnectionProfile profile) {
    if (!profile.isClientSecretSet()) {
      return Reason.NOT_CONNECTED;
    }
    return profile.isSignInRejected() ? Reason.EXPIRED : null;
  }

  /** A person's secret on a profile that admits no persons (any more) is never handed out. */
  static boolean withoutPersons(ConnectionProfile profile, SecretOwner owner) {
    return owner instanceof PersonOwned && !profile.getOwnership().admitsPersons();
  }

  /**
   * Whether the store holds the secret of {@code owner} on {@code profile}: a personal secret of a
   * library, and any secret of a person; the profile's own sign-in is read from its row, OAuth for
   * a library is not supported yet.
   */
  private static boolean asksTheStore(ConnectionProfile profile, SecretOwner owner) {
    ConnectionAuthMethod method = profile.getAuthMethod();
    return method != ConnectionAuthMethod.NONE
        && (method == ConnectionAuthMethod.PERSONAL_SECRET || owner instanceof PersonOwned);
  }

  private SourceBlock typeLock(SourceType type) {
    return new SourceBlock(
        Reason.TYPE_LOCKED,
        ADMINISTRATION,
        LOCKED
            + " Die Systemverwaltung hat die Quellart „"
            + displayName(type)
            + "“ gesperrt"
            + LOCK_CONTENT_STAYS);
  }

  private SourceBlock profileRequiredLock(SourceType type) {
    return new SourceBlock(
        Reason.PROFILE_REQUIRED,
        LIBRARY_MANAGERS,
        LOCKED
            + " Die Quellart „"
            + displayName(type)
            + "“ ist nur noch über Zugänge nutzbar, und diese Bibliothek hat eine eigene"
            + " Adresse; der vorhandene Inhalt bleibt durchsuchbar. Die Verwaltenden der"
            + " Bibliothek ordnen sie einem Zugang zu.");
  }

  private String displayName(SourceType type) {
    return connectors
        .getObject()
        .find(type)
        .map(connector -> connector.descriptor().displayName())
        .orElse(type.key());
  }

  /**
   * What decides the block of one library: its type lock, the profile requirement of its type, its
   * connection, profile, the owner of its secret where the store holds it and the store's answer.
   */
  private record Facts(
      KnowledgeLibrary library,
      SourceBlock typeLock,
      SourceBlock profileRequiredLock,
      boolean connected,
      ConnectionProfile profile,
      SecretOwner secretOwner,
      Reason secretState) {

    /** Tries the considered reasons in their order of declaration, which is the precedence. */
    private Optional<SourceBlock> firstBlock(Set<Reason> considered) {
      for (Reason reason : Reason.values()) {
        if (considered.contains(reason)) {
          Optional<SourceBlock> block = blockFor(reason);
          if (block.isPresent()) {
            return block;
          }
        }
      }
      return Optional.empty();
    }

    private Optional<SourceBlock> blockFor(Reason reason) {
      return switch (reason) {
        case TYPE_LOCKED -> Optional.ofNullable(typeLock);
        case PROFILE_REQUIRED -> Optional.ofNullable(profileRequiredLock);
        case PROFILE_LOCKED ->
            profile != null && profile.isLocked()
                ? Optional.of(
                    new SourceBlock(
                        Reason.PROFILE_LOCKED,
                        ADMINISTRATION,
                        LOCKED
                            + " Die Systemverwaltung hat den Zugang „"
                            + profile.getName()
                            + "“ gesperrt"
                            + LOCK_CONTENT_STAYS))
                : Optional.empty();
        case ACCESS_REMOVED ->
            connected && profile == null
                ? Optional.of(accessRemoved(library.isOwnerOnly()))
                : Optional.empty();
        case TARGET_OUTSIDE_PROFILE ->
            profile != null && !ServerAddress.covers(profile.getServerUrl(), library.getSourceUrl())
                ? Optional.of(
                    new SourceBlock(
                        Reason.TARGET_OUTSIDE_PROFILE,
                        LIBRARY_MANAGERS,
                        "Die Adresse der Bibliothek liegt nicht unter der Server-Adresse des"
                            + " Zugangs \""
                            + profile.getName()
                            + "\". Die Verwaltenden der Bibliothek passen die Adresse an."
                            + CONTENT_STAYS))
                : Optional.empty();
        case OWNER_DEACTIVATED, DORMANT, EXPIRED -> fromTheStore(reason);
        case NOT_CONNECTED -> profile == null ? Optional.empty() : notConnected();
      };
    }

    /**
     * A private library loses its profile also when the profile stops admitting persons or its
     * connector refuses a change; its owner moves it, the administration where no profile is left.
     */
    private static SourceBlock accessRemoved(boolean privateLibrary) {
      if (privateLibrary) {
        return new SourceBlock(
            Reason.ACCESS_REMOVED,
            OWNER_OR_ADMINISTRATION,
            "Zugang nicht mehr nutzbar: Der Zugang dieser privaten Bibliothek wurde entfernt oder"
                + " trägt sie nicht mehr. Die Besitzerin ordnet sie einem anderen Zugang zu, auf"
                + " dem sie ein verbundenes Konto hat; gibt es keinen, ist die Systemverwaltung"
                + " zuständig."
                + CONTENT_STAYS);
      }
      return new SourceBlock(
          Reason.ACCESS_REMOVED,
          LIBRARY_MANAGERS,
          "Zugang entfernt: Der Zugang dieser Bibliothek wurde gelöscht. Die"
              + " Verwaltenden der Bibliothek ordnen sie einem anderen Zugang zu."
              + CONTENT_STAYS);
    }

    private Optional<SourceBlock> fromTheStore(Reason reason) {
      return secretState == reason
          ? Optional.of(secretBlock(reason, profile, secretOwner))
          : Optional.empty();
    }

    private Optional<SourceBlock> notConnected() {
      ConnectionAuthMethod method = profile.getAuthMethod();
      if (method == ConnectionAuthMethod.NONE) {
        return Optional.empty();
      }
      if (secretOwner != null) {
        return fromTheStore(Reason.NOT_CONNECTED);
      }
      // OAUTH for a library: no library can be connected with it yet
      return Optional.of(
          new SourceBlock(
              Reason.NOT_CONNECTED,
              ADMINISTRATION,
              "Nicht verbunden: Die Anmeldeart des Zugangs \""
                  + profile.getName()
                  + "\" wird für Bibliotheken noch nicht unterstützt. Zuständig ist die"
                  + " Systemverwaltung."
                  + CONTENT_STAYS));
    }
  }
}
