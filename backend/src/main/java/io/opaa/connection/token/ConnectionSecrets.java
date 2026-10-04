package io.opaa.connection.token;

import io.opaa.api.types.ConnectionEndCause;
import io.opaa.auth.AccountUsability;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.connection.token.PersonAccounts.AccountKey;
import io.opaa.connection.token.SecretOwner.LibraryOwned;
import io.opaa.connection.token.SecretOwner.PersonOwned;
import io.opaa.connection.token.SecretOwner.ProfileOwned;
import io.opaa.indexing.source.Secret;
import io.opaa.indexing.source.SecretKind;
import io.opaa.indexing.source.SourceBlock.Reason;
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
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The one place that reads, checks, stores and discards the secret a source is reached with,
 * addressed by its {@link SecretOwner}, and the only place that decrypts a stored token. Every read
 * goes to the stored row. A person's secret is handed out only while their account is usable
 * ({@link AccountUsability} with the configured inactivity threshold) and only to the target it was
 * issued for; resting and deactivated are derived here at every use, never stored. A hand-out
 * records the account's use at most once per {@link #USE_RESOLUTION}. A profile's own sign-in holds
 * no row: its token comes from {@link SecretIssuer#mint}, and whether it is refused the profile row
 * says.
 */
@Component
public class ConnectionSecrets {

  /** How finely the last use of a connected account is kept. */
  static final Duration USE_RESOLUTION = Duration.ofDays(1);

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
  private final Duration inactivityThreshold;
  private final TransactionTemplate usage;
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
    this.inactivityThreshold = lifecycle.inactivityThreshold();
    this.usage = new TransactionTemplate(transactionManager);
    this.usage.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
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
      case PersonOwned person -> personSecret(person, target);
      case ProfileOwned(UUID profileId) -> issuer().mint(profileId);
    };
  }

  /**
   * The secret to retry with after the source rejected one {@code owner} handed out: a profile's
   * token is obtained anew, every other owner answers as {@link #current}.
   *
   * @throws SecretRefusedException with the reason none is handed out
   */
  public Secret afterRejection(SecretOwner owner, String target) {
    if (owner instanceof ProfileOwned(UUID profileId)) {
      issuer().forgetMinted(profileId);
    }
    return current(owner, target);
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
    for (SecretOwner owner : owners) {
      switch (owner) {
        case LibraryOwned(UUID libraryId) -> libraryIds.add(libraryId);
        case PersonOwned person -> persons.add(person);
        case ProfileOwned ignored -> {}
      }
    }
    Set<UUID> holding =
        libraryIds.isEmpty() ? Set.of() : libraries.findIdsHoldingSourceCredentials(libraryIds);
    Map<PersonOwned, Reason> personStates = personStates(persons);
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
        case ProfileOwned ignored -> {}
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
    };
  }

  /**
   * Stores {@code secret} for {@code owner}, issued for {@code target}; it replaces a held one.
   *
   * @throws IllegalStateException for a library, whose own secret the library administration
   *     writes, for a profile, whose registration the profile administration writes, and for a
   *     person without a connected account on the profile
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
        }
      }
      case ProfileOwned ignored ->
          throw new IllegalStateException(
              "a profile's registration is written by the profile administration");
    }
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
      case PersonOwned person -> accountIdOf(person).map(tokens::deleteByAccount).orElse(0);
      case ProfileOwned(UUID profileId) -> {
        forgetMinted(profileId);
        yield 0;
      }
    };
  }

  /** How many stored secrets of persons are past their end now, counted without decrypting. */
  public long countExpiredPersonSecrets() {
    return tokens.countPersonsEndedBy(clock.instant());
  }

  /**
   * Discards every secret held under {@code profileId} - of its libraries, of persons and the token
   * of its own sign-in - for {@code cause}. Ending the persons' connections is left to their
   * accounts, the profile's registration to the profile administration.
   */
  public Discarded discardAllUnder(UUID profileId, ConnectionEndCause cause) {
    List<UUID> under = librariesOnProfile.libraryIdsOnProfile(profileId);
    for (UUID libraryId : under) {
      libraries.eraseSourceCredentials(libraryId);
    }
    forgetMinted(profileId);
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
      case ProfileOwned(UUID profileId) -> forgetMinted(profileId);
      case PersonOwned person ->
          tokenOf(person)
              .ifPresent(
                  token -> {
                    token.endedAt(clock.instant());
                    tokens.save(token);
                  });
    }
  }

  private Secret personSecret(PersonOwned person, String target) {
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
      throw new SecretRefusedException(Reason.NOT_CONNECTED);
    }
    if (token.expiredAt(clock.instant())) {
      throw new SecretRefusedException(Reason.EXPIRED);
    }
    String value = decrypt(token);
    Secret secret =
        switch (token.getKind()) {
          case PERSONAL_SECRET -> new Secret(SecretKind.PERSONAL_SECRET, value);
          case OAUTH -> issuer().renew(person.profileId(), value, token.getIssuedFor());
        };
    markUsed(account);
    return secret;
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

  /** The library's secret as stored now; inside a transaction its managed entity. */
  private String columnOf(UUID libraryId) {
    return libraries.findById(libraryId).map(KnowledgeLibrary::getSourceCredentials).orElse(null);
  }

  /** How many libraries and persons lost their secret under a profile. */
  public record Discarded(int libraries, int persons) {}
}
