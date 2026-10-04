package io.opaa.connection;

import io.opaa.common.ValidationException;
import io.opaa.connection.profile.ConnectionProfile;
import io.opaa.connection.profile.ConnectionProfileRepository;
import io.opaa.connection.profile.EffectiveSourceSettings;
import io.opaa.connection.profile.EffectiveSourceSettings.Purpose;
import io.opaa.connection.profile.LibraryConnection;
import io.opaa.connection.profile.LibraryConnectionRepository;
import io.opaa.connection.profile.ProfileAdmission;
import io.opaa.connection.profile.SecretTarget;
import io.opaa.connection.profile.ServerAddress;
import io.opaa.connection.profile.SourceBlocks;
import io.opaa.connection.profile.SourceDraft;
import io.opaa.connection.profile.SourceDraft.DraftOwner;
import io.opaa.connection.token.ConnectionSecrets;
import io.opaa.connection.token.PersonAccounts;
import io.opaa.connection.token.SecretOwner.PersonOwned;
import io.opaa.connection.token.SecretRefusedException;
import io.opaa.indexing.source.Secret;
import io.opaa.indexing.source.SourceBlock;
import io.opaa.indexing.source.SourceConnectorRegistry;
import io.opaa.indexing.source.SourceSettings;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.SourceType;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Where a private library meets its owner's connected account (ADR-0041, Entscheidung 6): it goes
 * only onto a profile admitting persons on which its owner has an account, reaches only the target
 * that account is issued for, and signs in with the owner's secret - never with one of its own.
 */
@Component
@Transactional(readOnly = true)
public class PrivateLibraryConnections {

  private final ConnectionProfileRepository profiles;
  private final LibraryConnectionRepository connections;
  private final PersonAccounts accounts;
  private final ConnectionSecrets secrets;
  private final EffectiveSourceSettings effective;
  private final SourceConnectorRegistry connectors;

  public PrivateLibraryConnections(
      ConnectionProfileRepository profiles,
      LibraryConnectionRepository connections,
      PersonAccounts accounts,
      ConnectionSecrets secrets,
      EffectiveSourceSettings effective,
      SourceConnectorRegistry connectors) {
    this.profiles = profiles;
    this.connections = connections;
    this.accounts = accounts;
    this.secrets = secrets;
    this.effective = effective;
    this.connectors = connectors;
  }

  /**
   * The profile a private library of {@code type} owned by {@code ownerUserId} runs on.
   *
   * @throws io.opaa.common.NotFoundException for an unknown profile
   * @throws ValidationException (German 400) without a profile, for one that admits no persons, and
   *     where the owner has no connected account on it
   */
  public ConnectionProfile requireOwnProfile(UUID ownerUserId, UUID profileId, SourceType type) {
    if (profileId == null) {
      throw new ValidationException(
          "Eine private Bibliothek läuft über ein verbundenes Konto: connectionProfileId ist"
              + " erforderlich");
    }
    ConnectionProfile profile =
        ProfileAdmission.require(
            profiles.findById(profileId), type, connectors.descriptor(type), DraftOwner.PERSON);
    if (accounts.accountsAmong(Set.of(ownerUserId), Set.of(profileId)).isEmpty()) {
      throw new ValidationException(
          "Für den Zugang „"
              + profile.getName()
              + "“ ist kein verbundenes Konto hinterlegt. Eine private Bibliothek läuft nur über"
              + " ein eigenes verbundenes Konto; es lässt sich auf der Seite „Verbundene Konten“"
              + " verbinden.");
    }
    return profile;
  }

  /** {@code requestedUrl} under {@code profile}, or the profile's own address when omitted. */
  public String addressOn(ConnectionProfile profile, String requestedUrl) {
    String address = requestedUrl == null ? profile.getServerUrl() : requestedUrl;
    if (!ServerAddress.covers(profile.getServerUrl(), address)) {
      throw new ValidationException(
          "Die Adresse muss unter der Server-Adresse des Zugangs liegen: "
              + profile.getServerUrl());
    }
    return address;
  }

  /**
   * The configuration a private library of {@code ownerUserId} reaches its source with on {@code
   * profileId} - a new one ({@code libraryId} {@code null}) or a stored one moving there - before
   * anything is saved: the draft under the profile's frame, with the owner's secret where it is
   * handed out now.
   *
   * @throws ValidationException (German 400) for a secret in the draft and for a target other than
   *     the one the owner's connected account is issued for
   */
  public Draft ofDraft(
      SourceType type, UUID profileId, UUID libraryId, SourceSettings requested, UUID ownerUserId) {
    if (requested.sourceCredentials() != null && !requested.sourceCredentials().isBlank()) {
      throw new ValidationException(
          "Eine private Bibliothek meldet sich mit dem verbundenen Konto an und nimmt keine"
              + " eigenen Zugangsdaten (sourceCredentials)");
    }
    ConnectionProfile profile = profiles.findById(profileId).orElseThrow();
    SourceSettings composed =
        effective.ofDraft(
            new SourceDraft(
                type, profileId, libraryId, requested.withoutCredentials(), DraftOwner.PERSON));
    requireOwnTarget(profile, composed);
    PersonOwned owner = new PersonOwned(profileId, ownerUserId);
    try {
      Secret secret = secrets.current(owner, effective.personTarget(profile));
      return new Draft(composed.withCredentials(secret), null);
    } catch (SecretRefusedException e) {
      return new Draft(composed, SourceBlocks.secretBlock(e.reason(), profile, owner));
    }
  }

  /**
   * Refuses (German 400) a private library whose target on {@code profile} - origin and the
   * connector's binding, such as a share - differs from the one its owner's account is issued for.
   */
  public void requireOwnTarget(ConnectionProfile profile, SourceSettings composed) {
    String target = SecretTarget.of(connectors.connector(profile.getSourceType()), composed).key();
    if (!Objects.equals(target, effective.personTarget(profile))) {
      throw new ValidationException(
          "Das Ziel der privaten Bibliothek – Adresse oder Bindung wie eine Freigabe – weicht von"
              + " dem Ziel ab, für das das verbundene Konto beim Zugang „"
              + profile.getName()
              + "“ gilt. Eine private Bibliothek erreicht nur das Ziel ihres Zugangs.");
    }
  }

  /**
   * {@link #requireOwnTarget} for a stored private library on its current profile, as its fields
   * stand in the caller's transaction; nothing for one without a profile.
   */
  public void requireOwnTargetOf(KnowledgeLibrary library) {
    connections
        .findById(library.getId())
        .map(LibraryConnection::getProfileId)
        .flatMap(profiles::findById)
        .ifPresent(
            profile -> requireOwnTarget(profile, effective.of(library, Purpose.SETTINGS_ONLY)));
  }

  /**
   * A private library's configuration before it is saved; {@code refused} says why its owner's
   * secret is not handed out now, {@code null} while it is.
   */
  public record Draft(SourceSettings settings, SourceBlock refused) {}
}
