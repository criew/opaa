package io.opaa.connection.token;

import io.opaa.api.types.ConnectionEndCause;
import io.opaa.auth.AccountUsability;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.connection.token.PersonAccounts.AccountKey;
import io.opaa.connection.token.SecretOwner.LibraryOwned;
import io.opaa.connection.token.SecretOwner.PendingConsent;
import io.opaa.connection.token.SecretOwner.PersonOwned;
import io.opaa.connection.token.SecretOwner.ProfileOwned;
import io.opaa.connection.token.SecretOwner.SourceConsent;
import io.opaa.indexing.source.Secret;
import io.opaa.indexing.source.SecretKind;
import io.opaa.indexing.source.SignInRejectedException;
import io.opaa.indexing.source.SourceBlock.Reason;
import io.opaa.indexing.source.SourceCredentialsException;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.KnowledgeLibraryRepository;
import io.opaa.security.CredentialsEncryptionKeyMissingException;
import io.opaa.security.CredentialsEncryptor;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The one place that reads, checks, stores and discards the secret a source is reached with,
 * addressed by its {@link SecretOwner}, and the only place that decrypts a stored token. Every read
 * goes to the stored row. A person's secret is handed out only while their account is usable
 * ({@link AccountUsability} with the configured inactivity threshold) and only to the target it was
 * issued for; resting and deactivated are derived here at every use, never stored. A hand-out
 * records the account's use at most once per {@link #USE_RESOLUTION}. An OAuth grant is renewed
 * here under its row lock and, once a discard committed, revoked on {@link GrantRevocations}. A
 * profile's own sign-in holds no row: its token comes from {@link SecretIssuer#mint}, and whether
 * it is refused the profile row says. A library's own consent is handed out whoever gave it; a
 * pending consent only to the person who gave it, until its library takes it over or it expires.
 */
@Component
public class ConnectionSecrets {

  /** How long before the end a provider names for a consent its owner is warned. */
  public static final Duration EXPIRY_WARNING = ConnectionToken.EXPIRY_WARNING;

  /** How finely the last use of a connected account is kept. */
  static final Duration USE_RESOLUTION = Duration.ofDays(1);

  /** How long before its end an access token is renewed, at most half its lifetime. */
  static final Duration RENEWAL_MARGIN = Duration.ofMinutes(5);

  private static final Logger log = LoggerFactory.getLogger(ConnectionSecrets.class);
  private static final String ENCRYPTED_MARKER = "enc:";

  private final LibrariesOnProfile librariesOnProfile;
  private final KnowledgeLibraryRepository libraries;
  private final ConnectionTokenRepository tokens;
  private final PersonAccounts accounts;
  private final UserRepository users;
  private final AccountUsability usability;
  private final CredentialsEncryptor encryptor;
  private final ObjectProvider<SecretIssuer> issuers;
  private final ObjectProvider<GrantRejections> grantRejections;
  private final ObjectProvider<SourceConsentRejections> consentRejections;
  private final GrantRevocations revocations;
  private final Duration inactivityThreshold;
  private final TransactionTemplate usage;
  private final TransactionTemplate renewal;
  private final Clock clock;

  ConnectionSecrets(
      LibrariesOnProfile librariesOnProfile,
      KnowledgeLibraryRepository libraries,
      ConnectionTokenRepository tokens,
      PersonAccounts accounts,
      UserRepository users,
      AccountUsability usability,
      CredentialsEncryptor encryptor,
      ObjectProvider<SecretIssuer> issuers,
      ObjectProvider<GrantRejections> grantRejections,
      ObjectProvider<SourceConsentRejections> consentRejections,
      GrantRevocations revocations,
      ConnectionLifecycleProperties lifecycle,
      PlatformTransactionManager transactionManager,
      Clock clock) {
    this.librariesOnProfile = librariesOnProfile;
    this.libraries = libraries;
    this.tokens = tokens;
    this.accounts = accounts;
    this.users = users;
    this.usability = usability;
    this.encryptor = encryptor;
    this.issuers = issuers;
    this.grantRejections = grantRejections;
    this.consentRejections = consentRejections;
    this.revocations = revocations;
    this.inactivityThreshold = lifecycle.inactivityThreshold();
    this.usage = new TransactionTemplate(transactionManager);
    this.usage.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.renewal = new TransactionTemplate(transactionManager);
    this.renewal.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.clock = clock;
  }

  /**
   * The secret {@code owner} holds now, to be sent to {@code target} - the opaque binding the
   * profile names for its secrets.
   *
   * @throws SecretRefusedException with the reason none is handed out
   */
  public Secret current(SecretOwner owner, String target) {
    return switch (owner) {
      case LibraryOwned(UUID libraryId) -> {
        Secret secret = Secret.personal(columnOf(libraryId));
        if (secret == null) {
          throw new SecretRefusedException(Reason.NOT_CONNECTED);
        }
        yield secret;
      }
      case PersonOwned person -> personSecret(person, target, null);
      case ProfileOwned(UUID profileId) -> issuer().mint(profileId);
      case SourceConsent consent -> consentSecret(consent, consentOf(consent), target, null);
      case PendingConsent pending -> consentSecret(pending, pendingOf(pending), target, null);
    };
  }

  /**
   * The secret to retry with after the source rejected {@code rejected}, which {@code owner} handed
   * out: a profile's token is obtained anew, a person's OAuth token is renewed unless a renewal
   * replaced it meanwhile, every other owner answers as {@link #current}.
   *
   * @throws SecretRefusedException with the reason none is handed out
   */
  public Secret afterRejection(SecretOwner owner, String target, Secret rejected) {
    return switch (owner) {
      case ProfileOwned(UUID profileId) -> {
        issuer().forgetMinted(profileId);
        yield current(owner, target);
      }
      case PersonOwned person -> personSecret(person, target, rejected);
      case LibraryOwned ignored -> current(owner, target);
      case SourceConsent consent -> consentSecret(consent, consentOf(consent), target, rejected);
      case PendingConsent pending -> consentSecret(pending, pendingOf(pending), target, rejected);
    };
  }

  /**
   * Why {@code owner} cannot hand out a secret now, empty while it can; see {@link #statesAmong}.
   */
  public Optional<Reason> stateOf(SecretOwner owner) {
    return Optional.ofNullable(statesAmong(List.of(owner)).get(owner));
  }

  /**
   * {@link #stateOf} for many owners with one query per kind of owner, absent for an owner that can
   * hand one out. It decrypts and renews nothing and does not compare targets, so an unreadable
   * secret or one issued for another target is refused by {@link #current} only. A profile's own
   * sign-in gets no reason here: its state is the profile's, read with the profile row.
   */
  public Map<SecretOwner, Reason> statesAmong(Collection<SecretOwner> owners) {
    List<UUID> libraryIds = new ArrayList<>();
    List<PersonOwned> persons = new ArrayList<>();
    List<UUID> consenting = new ArrayList<>();
    for (SecretOwner owner : owners) {
      switch (owner) {
        case LibraryOwned(UUID libraryId) -> libraryIds.add(libraryId);
        case PersonOwned person -> persons.add(person);
        case SourceConsent consent -> consenting.add(consent.libraryId());
        case ProfileOwned ignored -> {}
        case PendingConsent ignored -> {}
      }
    }
    Set<UUID> holding =
        libraryIds.isEmpty() ? Set.of() : libraries.findIdsHoldingSourceCredentials(libraryIds);
    Map<PersonOwned, Reason> personStates = personStates(persons);
    Map<UUID, ConnectionToken> consentOfLibrary = new HashMap<>();
    if (!consenting.isEmpty()) {
      for (ConnectionToken token : tokens.findByLibraryIdIn(consenting)) {
        consentOfLibrary.put(token.getLibraryId(), token);
      }
    }
    Instant now = clock.instant();
    Map<SecretOwner, Reason> states = new HashMap<>();
    for (SecretOwner owner : owners) {
      switch (owner) {
        case LibraryOwned(UUID libraryId) -> {
          if (!holding.contains(libraryId)) {
            states.put(owner, Reason.NOT_CONNECTED);
          }
        }
        case PersonOwned person -> {
          Reason reason = personStates.get(person);
          if (reason != null) {
            states.put(owner, reason);
          }
        }
        case SourceConsent consent -> {
          ConnectionToken token = consentOfLibrary.get(consent.libraryId());
          if (token == null || !token.getProfileId().equals(consent.profileId())) {
            states.put(owner, Reason.NOT_CONNECTED);
          } else if (token.expiredAt(now)) {
            states.put(owner, Reason.EXPIRED);
          }
        }
        case ProfileOwned ignored -> {}
        case PendingConsent ignored -> {}
      }
    }
    return states;
  }

  /**
   * The secret {@code owner} stores for the library itself, as a change keeps it on the same
   * origin; {@code null} for none or an unreadable one, and always for a person's or a profile's
   * secret, which no change of a library carries over.
   */
  public String stored(SecretOwner owner) {
    return switch (owner) {
      case LibraryOwned(UUID libraryId) -> columnOf(libraryId);
      case PersonOwned ignored -> null;
      case ProfileOwned ignored -> null;
      case SourceConsent ignored -> null;
      case PendingConsent ignored -> null;
    };
  }

  /**
   * Whether {@code owner} holds a secret at all, regardless of whether it may be used now; a
   * profile's registration is no secret of the library and counts as none.
   */
  public boolean holds(SecretOwner owner) {
    return switch (owner) {
      case LibraryOwned ignored -> stored(owner) != null;
      case PersonOwned person -> tokenOf(person).isPresent();
      case ProfileOwned ignored -> false;
      case SourceConsent consent -> consentOf(consent).isPresent();
      case PendingConsent pending -> pendingOf(pending).isPresent();
    };
  }

  /**
   * Stores {@code secret} for {@code owner}, issued for {@code target}; it replaces a held one.
   *
   * @throws IllegalStateException for a library, whose own secret the library administration
   *     writes, for a profile, whose registration the profile administration writes, for a person
   *     without a connected account on the profile, for anything but an OAuth grant as a library's
   *     consent, and for a pending consent, which {@link #storePending} creates
   */
  public void store(SecretOwner owner, NewSecret secret, String target) {
    switch (owner) {
      case LibraryOwned ignored ->
          throw new IllegalStateException(
              "a library's own secret is written by the library administration");
      case PersonOwned person -> {
        UUID accountId =
            accountIdOf(person)
                .orElseThrow(
                    () -> new IllegalStateException("no connected account holds the secret"));
        switch (secret) {
          case NewSecret.Personal personal -> storePersonal(person, accountId, personal, target);
          case NewSecret.OAuthGrant grant -> storeGrant(person, accountId, grant, target);
        }
      }
      case ProfileOwned ignored ->
          throw new IllegalStateException(
              "a profile's registration is written by the profile administration");
      case SourceConsent consent -> {
        if (!(secret instanceof NewSecret.OAuthGrant grant)) {
          throw new IllegalStateException("a library's consent is an OAuth grant");
        }
        storeConsent(consent, grant, target);
      }
      case PendingConsent ignored ->
          throw new IllegalStateException("a pending consent is created by storePending");
    }
  }

  /**
   * Holds {@code grant}, obtained by {@code userId} as {@code accountLabel} ({@code null} for
   * unknown) on profile {@code profileId} for a library not created yet and issued for {@code
   * target}, until {@code lifetime} has passed.
   *
   * @return the pending consent, which only {@code userId} can use and hand to a new library
   */
  public PendingConsent storePending(
      UUID profileId,
      UUID userId,
      String accountLabel,
      NewSecret.OAuthGrant grant,
      String target,
      Duration lifetime) {
    Instant now = clock.instant();
    ConnectionToken token =
        ConnectionToken.pendingGrant(
            profileId, userId, accountLabel, ciphered(grant), target, now, now.plus(lifetime));
    tokens.save(token);
    return new PendingConsent(token.getId(), userId);
  }

  /**
   * Hands {@code pending} to the new library of {@code owner}: only where it is the same person's,
   * not expired, on the same profile and issued for {@code target}. Needs a transaction.
   *
   * @return whether the library took it over; nothing changes otherwise
   */
  public boolean takeOver(PendingConsent pending, SourceConsent owner, String target) {
    ConnectionToken token = tokens.findLockedById(pending.tokenId()).orElse(null);
    if (token == null
        || !token.pendingFor(pending.userId(), clock.instant())
        || !token.getProfileId().equals(owner.profileId())
        || !token.getIssuedFor().equals(target)
        || consentOf(owner).isPresent()) {
      return false;
    }
    token.takenOverBy(owner.libraryId(), clock.instant());
    tokens.save(token);
    return true;
  }

  /**
   * What {@code pending} is while its person can use it, empty for one unknown, another person's or
   * expired; read without decrypting.
   */
  public Optional<PendingView> pendingView(PendingConsent pending) {
    return pendingOf(pending)
        .map(
            token ->
                new PendingView(
                    token.getProfileId(),
                    token.getPendingAccountLabel(),
                    token.getPendingExpiresAt()));
  }

  /**
   * Discards every pending consent past its end, revoked after the caller's transaction commits.
   * Needs a transaction.
   *
   * @return how many went
   */
  public int discardExpiredPending() {
    List<ConnectionToken> expired = tokens.findPendingExpiredBy(clock.instant());
    revokeAfterCommit(expired);
    tokens.deleteAll(expired);
    return expired.size();
  }

  /**
   * Discards the secret of {@code owner}; for a library in the loaded entity and the column, for a
   * profile the token held in the process - its registration stays with the profile. Returns how
   * many stored secrets of a person went, always 0 for a library and a profile.
   */
  public int discard(SecretOwner owner) {
    return switch (owner) {
      case LibraryOwned(UUID libraryId) -> {
        libraries.findById(libraryId).ifPresent(KnowledgeLibrary::dropSourceCredentials);
        libraries.eraseSourceCredentials(libraryId);
        yield 0;
      }
      case PersonOwned person ->
          accountIdOf(person)
              .map(
                  accountId -> {
                    tokens
                        .findByConnectedAccountId(accountId)
                        .ifPresent(token -> revokeAfterCommit(List.of(token)));
                    return tokens.deleteByAccount(accountId);
                  })
              .orElse(0);
      case ProfileOwned(UUID profileId) -> {
        forgetMinted(profileId);
        yield 0;
      }
      case SourceConsent consent -> discardRow(consentOf(consent));
      case PendingConsent pending -> discardRow(pendingOf(pending));
    };
  }

  /** Deletes {@code held}, revoked once the caller's transaction committed; 0 for none. */
  private int discardRow(Optional<ConnectionToken> held) {
    held.ifPresent(
        token -> {
          revokeAfterCommit(List.of(token));
          tokens.delete(token);
        });
    return 0;
  }

  /**
   * When the OAuth grant of {@code owner} ends as its provider named it, empty for a grant without
   * a named end, one already past and any other secret; read without decrypting.
   */
  public Optional<Instant> grantEnd(PersonOwned owner) {
    Instant now = clock.instant();
    return tokenOf(owner)
        .filter(token -> token.getKind() == ConnectionToken.Kind.OAUTH)
        .map(ConnectionToken::getExpiresAt)
        .filter(end -> end.isAfter(now));
  }

  /**
   * Claims the persons' OAuth grants whose named end lies within {@link #EXPIRY_WARNING} and was
   * not warned of: each is marked in the caller's transaction, so an end is claimed once. When an
   * end is warned of anew the row decides ({@code ConnectionToken}). Needs a transaction.
   */
  public List<EndingGrant> claimEndingGrants() {
    Instant now = clock.instant();
    List<EndingGrant> ending = new ArrayList<>();
    for (ConnectionToken token : tokens.findGrantsEndingUnwarned(now, now.plus(EXPIRY_WARNING))) {
      token.expiryWarned(now);
      ending.add(
          new EndingGrant(
              token.getConnectedAccountId(), token.getProfileId(), token.getExpiresAt()));
    }
    return ending;
  }

  /**
   * When the OAuth grant of a library's own consent ends as its provider named it; as {@link
   * #grantEnd(PersonOwned)}.
   */
  public Optional<Instant> grantEnd(SourceConsent owner) {
    Instant now = clock.instant();
    return consentOf(owner)
        .map(ConnectionToken::getExpiresAt)
        .filter(end -> end != null && end.isAfter(now));
  }

  /**
   * Claims the libraries' own consents whose named end lies within {@link #EXPIRY_WARNING} and was
   * not warned of, as {@link #claimEndingGrants}. Needs a transaction.
   */
  public List<EndingConsent> claimEndingConsents() {
    Instant now = clock.instant();
    List<EndingConsent> ending = new ArrayList<>();
    for (ConnectionToken token :
        tokens.findLibraryGrantsEndingUnwarned(now, now.plus(EXPIRY_WARNING))) {
      token.expiryWarned(now);
      ending.add(
          new EndingConsent(token.getLibraryId(), token.getProfileId(), token.getExpiresAt()));
    }
    return ending;
  }

  /** How many stored secrets of persons are past their end now, counted without decrypting. */
  public long countExpiredPersonSecrets() {
    return tokens.countPersonsEndedBy(clock.instant());
  }

  /**
   * Discards every secret held under {@code profileId} - of its libraries, their own and pending
   * consents, of persons and the token of its own sign-in - for {@code cause}. Ending the persons'
   * connections is left to their accounts, the libraries' consents to their connections, the
   * profile's registration to the profile administration.
   */
  public Discarded discardAllUnder(UUID profileId, ConnectionEndCause cause) {
    List<UUID> under = librariesOnProfile.libraryIdsOnProfile(profileId);
    for (UUID libraryId : under) {
      libraries.eraseSourceCredentials(libraryId);
    }
    forgetMinted(profileId);
    revokeAfterCommit(tokens.findPersonGrantsUnder(profileId));
    revokeAfterCommit(tokens.findConsentsUnder(profileId));
    tokens.deleteConsentsUnder(profileId);
    int persons = tokens.deletePersonsUnder(profileId);
    log.info(
        "Discarded the secrets of {} libraries and {} persons under profile {} ({})",
        under.size(),
        persons,
        profileId,
        cause);
    return new Discarded(under.size(), persons);
  }

  /**
   * The provider rejected the secret of {@code owner}: a person's secret ends now and is refused as
   * {@link Reason#EXPIRED} until it is replaced; a profile's token is obtained anew at the next
   * ask. A library's own secret carries no such state.
   */
  public void rejected(SecretOwner owner) {
    switch (owner) {
      case LibraryOwned ignored -> {}
      case PendingConsent ignored -> {}
      case ProfileOwned(UUID profileId) -> forgetMinted(profileId);
      case PersonOwned person -> tokenOf(person).ifPresent(this::endNow);
      case SourceConsent consent -> consentOf(consent).ifPresent(this::endNow);
    }
  }

  private void endNow(ConnectionToken token) {
    token.endedAt(clock.instant());
    tokens.save(token);
  }

  /**
   * The access token of a library's own or a pending consent, renewed as a person's; no account
   * decides whether it is handed out.
   */
  private Secret consentSecret(
      SecretOwner owner, Optional<ConnectionToken> held, String target, Secret rejected) {
    ConnectionToken token =
        held.orElseThrow(() -> new SecretRefusedException(Reason.NOT_CONNECTED));
    if (!token.getIssuedFor().equals(target)) {
      throw new SecretRefusedException(Reason.TARGET_OUTSIDE_PROFILE);
    }
    if (token.expiredAt(clock.instant())) {
      throw new SecretRefusedException(Reason.EXPIRED);
    }
    return accessToken(owner, token, rejected == null ? null : rejected.value());
  }

  private Secret personSecret(PersonOwned person, String target, Secret rejected) {
    Reason accountState = accountStates(List.of(person.userId())).get(person.userId());
    if (accountState != null) {
      throw new SecretRefusedException(accountState);
    }
    AccountKey account =
        accountOf(person).orElseThrow(() -> new SecretRefusedException(Reason.NOT_CONNECTED));
    ConnectionToken token =
        tokens
            .findByConnectedAccountId(account.getId())
            .orElseThrow(() -> new SecretRefusedException(Reason.NOT_CONNECTED));
    if (!token.getIssuedFor().equals(target)) {
      throw new SecretRefusedException(Reason.TARGET_OUTSIDE_PROFILE);
    }
    if (token.expiredAt(clock.instant())) {
      throw new SecretRefusedException(Reason.EXPIRED);
    }
    Secret secret =
        switch (token.getKind()) {
          case PERSONAL_SECRET -> new Secret(SecretKind.PERSONAL_SECRET, decrypt(token));
          case OAUTH -> accessToken(person, token, rejected == null ? null : rejected.value());
        };
    markUsed(account);
    return secret;
  }

  /**
   * The access token of the OAuth grant {@code token}: as stored while it lasts beyond the margin
   * and was not {@code rejected}, else renewed in a transaction of its own that holds the row, so
   * one renewal runs per grant and a rotated refresh token is stored in the same transaction. The
   * provider refusing the grant ends the connection; a provider out of reach leaves the stored
   * token in use until it really ends.
   */
  private Secret accessToken(SecretOwner owner, ConnectionToken token, String rejected) {
    if (rejected == null && lasts(token, clock.instant())) {
      return storedAccess(token);
    }
    Renewal renewed = renewal.execute(status -> renewLocked(owner, token.getId(), rejected));
    if (renewed.secret() != null) {
      return renewed.secret();
    }
    if (renewed.failure() != null) {
      throw renewed.failure();
    }
    throw new SecretRefusedException(renewed.refused());
  }

  private Renewal renewLocked(SecretOwner owner, UUID tokenId, String rejected) {
    Instant now = clock.instant();
    ConnectionToken token = tokens.findLockedById(tokenId).orElse(null);
    if (token == null) {
      return Renewal.refused(Reason.NOT_CONNECTED);
    }
    if (token.expiredAt(now)) {
      return Renewal.refused(Reason.EXPIRED);
    }
    String access = decryptAccess(token);
    boolean replacedMeanwhile = rejected != null && access != null && !access.equals(rejected);
    if (access != null && (replacedMeanwhile || rejected == null && lasts(token, now))) {
      return Renewal.of(accessSecret(token, access));
    }
    SecretIssuer.Issued issued;
    try {
      issued = issuer().renew(token.getProfileId(), decrypt(token), token.getIssuedFor());
    } catch (SignInRejectedException e) {
      log.info(
          "The provider no longer takes an OAuth grant under profile {}", token.getProfileId());
      grantRejected(owner, token);
      return Renewal.refused(Reason.EXPIRED);
    } catch (SourceCredentialsException e) {
      boolean usable =
          access != null
              && rejected == null
              && token.getAccessTokenExpiresAt() != null
              && now.isBefore(token.getAccessTokenExpiresAt());
      return usable ? Renewal.of(accessSecret(token, access)) : Renewal.failed(e);
    }
    token.renewed(
        encryptor.encrypt(issued.accessToken()),
        issued.accessTokenExpiresAt(),
        issued.refreshToken() == null ? null : encryptor.encrypt(issued.refreshToken()),
        issued.refreshTokenExpiresAt(),
        now);
    tokens.save(token);
    return Renewal.of(
        new Secret(SecretKind.ACCESS_TOKEN, issued.accessToken(), issued.accessTokenExpiresAt()));
  }

  /** Ends a grant the provider refused: a person's or a library's connection, a pending row. */
  private void grantRejected(SecretOwner owner, ConnectionToken token) {
    switch (owner) {
      case PersonOwned person -> grantRejections.getObject().grantRejected(person);
      case SourceConsent consent -> consentRejections.getObject().consentRejected(consent);
      case PendingConsent ignored -> endNow(token);
      case LibraryOwned ignored -> {}
      case ProfileOwned ignored -> {}
    }
  }

  /**
   * Whether the access token of {@code token} lasts beyond the renewal margin at {@code now}: five
   * minutes, or half its lifetime where that is shorter.
   */
  private static boolean lasts(ConnectionToken token, Instant now) {
    Instant end = token.getAccessTokenExpiresAt();
    if (end == null || token.getAccessTokenCiphertext() == null) {
      return false;
    }
    Duration half = Duration.between(token.getUpdatedAt(), end).dividedBy(2);
    Duration margin = half.compareTo(RENEWAL_MARGIN) < 0 ? half : RENEWAL_MARGIN;
    return now.isBefore(end.minus(margin));
  }

  private Secret storedAccess(ConnectionToken token) {
    String access = decryptAccess(token);
    if (access == null) {
      throw new SecretRefusedException(Reason.NOT_CONNECTED);
    }
    return accessSecret(token, access);
  }

  private static Secret accessSecret(ConnectionToken token, String access) {
    return new Secret(SecretKind.ACCESS_TOKEN, access, token.getAccessTokenExpiresAt());
  }

  /** The access token of {@code token}, {@code null} for none or an unreadable one. */
  private String decryptAccess(ConnectionToken token) {
    String ciphertext = token.getAccessTokenCiphertext();
    if (ciphertext == null || !ciphertext.startsWith(ENCRYPTED_MARKER)) {
      return null;
    }
    try {
      return encryptor.decrypt(ciphertext);
    } catch (CredentialsEncryptionKeyMissingException e) {
      return null;
    }
  }

  /**
   * Hands the revocation of the OAuth grants among {@code held} to {@link GrantRevocations} once
   * the caller's transaction committed - with the registration as it stands now, so a caller
   * discards before it changes the registration -, at once without a transaction. A rolled back
   * discard revokes nothing, and no revocation runs on the caller's thread or holds its connection.
   */
  private void revokeAfterCommit(List<ConnectionToken> held) {
    List<Map.Entry<UUID, Runnable>> byProfile = new ArrayList<>();
    for (ConnectionToken token : held) {
      if (token.getKind() != ConnectionToken.Kind.OAUTH) {
        continue;
      }
      SecretIssuer.StoredTokens stored =
          new SecretIssuer.StoredTokens(
              decryptQuietly(token), decryptAccess(token), token.getAccessTokenExpiresAt());
      Runnable revocation = issuer().revocation(token.getProfileId(), stored);
      if (revocation != null) {
        byProfile.add(Map.entry(token.getProfileId(), revocation));
      }
    }
    if (byProfile.isEmpty()) {
      return;
    }
    Runnable handOver = () -> byProfile.forEach(e -> revocations.submit(e.getKey(), e.getValue()));
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              handOver.run();
            }
          });
    } else {
      handOver.run();
    }
  }

  /** The stored secret of {@code token}, {@code null} for an unreadable one. */
  private String decryptQuietly(ConnectionToken token) {
    try {
      return decrypt(token);
    } catch (SecretRefusedException e) {
      return null;
    }
  }

  /**
   * Records the hand-out in a transaction of its own, since a hand-out may run inside a read-only
   * one, and only once {@link #USE_RESOLUTION} has passed: the hand-out path writes at most once a
   * day per account. A failed record costs the record only, never the hand-out.
   */
  private void markUsed(AccountKey account) {
    Instant now = clock.instant();
    Instant notBefore = now.minus(USE_RESOLUTION);
    Instant last = account.getLastUsedAt();
    if (last != null && !last.isBefore(notBefore)) {
      return;
    }
    try {
      usage.executeWithoutResult(status -> accounts.markUsed(account.getId(), now, notBefore));
    } catch (DataAccessException | TransactionException e) {
      log.warn(
          "The use of a connected account could not be recorded ({}); the next hand-out tries"
              + " again",
          e.getClass().getSimpleName());
    }
  }

  private void forgetMinted(UUID profileId) {
    SecretIssuer issuer = issuers.getIfAvailable();
    if (issuer != null) {
      issuer.forgetMinted(profileId);
    }
  }

  private SecretIssuer issuer() {
    SecretIssuer issuer = issuers.getIfAvailable();
    if (issuer == null) {
      throw new IllegalStateException(
          "An OAuth or profile token is handed out through a SecretIssuer, and none is registered");
    }
    return issuer;
  }

  private void storePersonal(
      PersonOwned person, UUID accountId, NewSecret.Personal secret, String target) {
    String ciphertext = encryptor.encrypt(secret.value());
    Instant now = clock.instant();
    ConnectionToken token =
        tokens
            .findByConnectedAccountId(accountId)
            .map(
                held -> {
                  held.replace(ciphertext, secret.expiresAt(), target, now);
                  return held;
                })
            .orElseGet(
                () ->
                    ConnectionToken.ofAccount(
                        person.profileId(),
                        accountId,
                        ciphertext,
                        secret.expiresAt(),
                        target,
                        now));
    tokens.save(token);
  }

  private ConnectionToken.Ciphered ciphered(NewSecret.OAuthGrant grant) {
    return new ConnectionToken.Ciphered(
        encryptor.encrypt(grant.refreshToken()),
        encryptor.encrypt(grant.accessToken()),
        grant.accessTokenExpiresAt(),
        grant.expiresAt());
  }

  /**
   * Stores a library's own consent on its profile; one it replaces is revoked once the caller
   * committed, one under another profile also goes.
   */
  private void storeConsent(SourceConsent owner, NewSecret.OAuthGrant grant, String target) {
    ConnectionToken.Ciphered ciphered = ciphered(grant);
    Instant now = clock.instant();
    Optional<ConnectionToken> held = tokens.findByLibraryId(owner.libraryId());
    held.ifPresent(replaced -> revokeAfterCommit(List.of(replaced)));
    ConnectionToken token;
    if (held.isPresent() && held.get().getProfileId().equals(owner.profileId())) {
      token = held.get();
      token.replaceGrant(ciphered, target, now);
    } else {
      held.ifPresent(
          other -> {
            tokens.delete(other);
            tokens.flush();
          });
      token =
          ConnectionToken.ofLibraryGrant(
              owner.profileId(), owner.libraryId(), ciphered, target, now);
    }
    tokens.save(token);
  }

  private void storeGrant(
      PersonOwned person, UUID accountId, NewSecret.OAuthGrant grant, String target) {
    ConnectionToken.Ciphered ciphered = ciphered(grant);
    Instant now = clock.instant();
    Optional<ConnectionToken> held = tokens.findByConnectedAccountId(accountId);
    held.ifPresent(replaced -> revokeAfterCommit(List.of(replaced)));
    ConnectionToken token =
        held.map(
                found -> {
                  found.replaceGrant(ciphered, target, now);
                  return found;
                })
            .orElseGet(
                () ->
                    ConnectionToken.ofAccountGrant(
                        person.profileId(), accountId, ciphered, target, now));
    tokens.save(token);
  }

  /**
   * The secret of {@code token}; one the key cannot read, or one stored without encryption, counts
   * as none.
   */
  private String decrypt(ConnectionToken token) {
    String ciphertext = token.getSecretCiphertext();
    if (!ciphertext.startsWith(ENCRYPTED_MARKER)) {
      log.warn(
          "A stored token of profile {} is not encrypted; it is not used", token.getProfileId());
      throw new SecretRefusedException(Reason.NOT_CONNECTED);
    }
    try {
      return encryptor.decrypt(ciphertext);
    } catch (CredentialsEncryptionKeyMissingException e) {
      log.warn("A stored token of profile {} cannot be decrypted", token.getProfileId());
      throw new SecretRefusedException(Reason.NOT_CONNECTED);
    }
  }

  private Map<PersonOwned, Reason> personStates(List<PersonOwned> persons) {
    if (persons.isEmpty()) {
      return Map.of();
    }
    Set<UUID> userIds = new HashSet<>();
    Set<UUID> profileIds = new HashSet<>();
    for (PersonOwned person : persons) {
      userIds.add(person.userId());
      profileIds.add(person.profileId());
    }
    Map<UUID, Reason> accountStates = accountStates(userIds);
    Map<PersonOwned, UUID> accountIds = new HashMap<>();
    for (AccountKey key : accounts.accountsAmong(userIds, profileIds)) {
      accountIds.put(new PersonOwned(key.getProfileId(), key.getUserId()), key.getId());
    }
    Map<UUID, ConnectionToken> tokenOfAccount = new HashMap<>();
    if (!accountIds.isEmpty()) {
      for (ConnectionToken token : tokens.findByConnectedAccountIdIn(accountIds.values())) {
        tokenOfAccount.put(token.getConnectedAccountId(), token);
      }
    }
    Instant now = clock.instant();
    Map<PersonOwned, Reason> states = new HashMap<>();
    for (PersonOwned person : persons) {
      Reason accountState = accountStates.get(person.userId());
      ConnectionToken token = tokenOfAccount.get(accountIds.get(person));
      if (accountState != null) {
        states.put(person, accountState);
      } else if (token == null) {
        states.put(person, Reason.NOT_CONNECTED);
      } else if (token.expiredAt(now)) {
        states.put(person, Reason.EXPIRED);
      }
    }
    return states;
  }

  /** Why the accounts of {@code userIds} release no secret now; absent for a usable one. */
  private Map<UUID, Reason> accountStates(Collection<UUID> userIds) {
    List<User> found = users.findAllById(userIds);
    Map<UUID, AccountUsability.State> states =
        usability.snapshot().withInactivityThreshold(inactivityThreshold).statesOf(found);
    Map<UUID, Reason> reasons = new HashMap<>();
    for (UUID userId : userIds) {
      AccountUsability.State state = states.get(userId);
      if (state == null || state.isDeactivated()) {
        reasons.put(userId, Reason.OWNER_DEACTIVATED);
      } else if (!state.isUsable()) {
        reasons.put(userId, Reason.DORMANT);
      }
    }
    return reasons;
  }

  private Optional<AccountKey> accountOf(PersonOwned person) {
    return accounts.accountsAmong(Set.of(person.userId()), Set.of(person.profileId())).stream()
        .filter(key -> key.getUserId().equals(person.userId()))
        .filter(key -> key.getProfileId().equals(person.profileId()))
        .findFirst();
  }

  private Optional<UUID> accountIdOf(PersonOwned person) {
    return accountOf(person).map(AccountKey::getId);
  }

  private Optional<ConnectionToken> tokenOf(PersonOwned person) {
    return accountIdOf(person).flatMap(tokens::findByConnectedAccountId);
  }

  /** The library's own consent, only on the profile {@code owner} names. */
  private Optional<ConnectionToken> consentOf(SourceConsent owner) {
    return tokens
        .findByLibraryId(owner.libraryId())
        .filter(token -> token.getProfileId().equals(owner.profileId()));
  }

  /** The pending consent, only for its person and before it expires. */
  private Optional<ConnectionToken> pendingOf(PendingConsent pending) {
    return tokens
        .findById(pending.tokenId())
        .filter(token -> token.pendingFor(pending.userId(), clock.instant()));
  }

  /** The library's secret as stored now; inside a transaction its managed entity. */
  private String columnOf(UUID libraryId) {
    return libraries.findById(libraryId).map(KnowledgeLibrary::getSourceCredentials).orElse(null);
  }

  /** What a renewal under the row lock came to: a token, a refusal or a failure to throw. */
  private record Renewal(Secret secret, Reason refused, RuntimeException failure) {

    static Renewal of(Secret secret) {
      return new Renewal(secret, null, null);
    }

    static Renewal refused(Reason reason) {
      return new Renewal(null, reason, null);
    }

    static Renewal failed(RuntimeException failure) {
      return new Renewal(null, null, failure);
    }
  }

  /** How many libraries and persons lost their secret under a profile. */
  public record Discarded(int libraries, int persons) {}

  /** A person's OAuth grant on a connected account, ending at {@code endsAt}. */
  public record EndingGrant(UUID connectedAccountId, UUID profileId, Instant endsAt) {}

  /** A library's own consent on a profile, ending at {@code endsAt}. */
  public record EndingConsent(UUID libraryId, UUID profileId, Instant endsAt) {}

  /**
   * A pending consent: its profile, the account it was given as ({@code null} for unknown) and
   * until when a new library can take it over.
   */
  public record PendingView(UUID profileId, String accountLabel, Instant expiresAt) {}
}
