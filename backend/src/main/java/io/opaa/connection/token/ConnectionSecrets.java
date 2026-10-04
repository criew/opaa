package io.opaa.connection.token;

import io.opaa.api.types.ConnectionEndCause;
import io.opaa.auth.AccountUsability;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.connection.token.PersonAccounts.AccountKey;
import io.opaa.connection.token.SecretOwner.LibraryOwned;
import io.opaa.connection.token.SecretOwner.PersonOwned;
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
import org.springframework.stereotype.Component;

/**
 * The one place that reads, checks, stores and discards the secret a source is reached with,
 * addressed by its {@link SecretOwner}, and the only place that decrypts a stored token. Every read
 * goes to the stored row. A person's secret is handed out only while their account is usable
 * ({@link AccountUsability} with {@link #INACTIVITY_THRESHOLD}) and only to the target it was
 * issued for; resting and deactivated are derived here at every use, never stored.
 */
@Component
public class ConnectionSecrets {

  /** Without a sign-in for this long, a person's connections rest (ADR-0041, Beschluss 15). */
  public static final Duration INACTIVITY_THRESHOLD = Duration.ofDays(90);

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
      Clock clock) {
    this.librariesOnProfile = librariesOnProfile;
    this.libraries = libraries;
    this.tokens = tokens;
    this.accounts = accounts;
    this.users = users;
    this.usability = usability;
    this.encryptor = encryptor;
    this.issuers = issuers;
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
   * secret or one issued for another target is refused by {@link #current} only.
   */
  public Map<SecretOwner, Reason> statesAmong(Collection<SecretOwner> owners) {
    List<UUID> libraryIds = new ArrayList<>();
    List<PersonOwned> persons = new ArrayList<>();
    for (SecretOwner owner : owners) {
      switch (owner) {
        case LibraryOwned(UUID libraryId) -> libraryIds.add(libraryId);
        case PersonOwned person -> persons.add(person);
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
      }
    }
    return states;
  }

  /**
   * The secret {@code owner} stores for the library itself, as a change keeps it on the same
   * origin; {@code null} for none or an unreadable one, and always for a person's secret, which no
   * change of a library carries over.
   */
  public String stored(SecretOwner owner) {
    return switch (owner) {
      case LibraryOwned(UUID libraryId) -> columnOf(libraryId);
      case PersonOwned ignored -> null;
    };
  }

  /** Whether {@code owner} holds a secret at all, regardless of whether it may be used now. */
  public boolean holds(SecretOwner owner) {
    return switch (owner) {
      case LibraryOwned ignored -> stored(owner) != null;
      case PersonOwned person -> tokenOf(person).isPresent();
    };
  }

  /**
   * Stores {@code secret} for {@code owner}, issued for {@code target}; it replaces a held one.
   *
   * @throws IllegalStateException for a library, whose own secret the library administration
   *     writes, and for a person without a connected account on the profile
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
    }
  }

  /** Discards the secret of {@code owner}; for a library in the loaded entity and the column. */
  public void discard(SecretOwner owner) {
    switch (owner) {
      case LibraryOwned(UUID libraryId) -> {
        libraries.findById(libraryId).ifPresent(KnowledgeLibrary::dropSourceCredentials);
        libraries.eraseSourceCredentials(libraryId);
      }
      case PersonOwned person -> accountIdOf(person).ifPresent(tokens::deleteByAccount);
    }
  }

  /**
   * Discards every secret held under {@code profileId} - of its libraries and of persons - for
   * {@code cause}. Ending the persons' connections is left to their accounts.
   */
  public Discarded discardAllUnder(UUID profileId, ConnectionEndCause cause) {
    List<UUID> under = librariesOnProfile.libraryIdsOnProfile(profileId);
    for (UUID libraryId : under) {
      libraries.eraseSourceCredentials(libraryId);
    }
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
   * {@link Reason#EXPIRED} until it is replaced. A library's own secret carries no such state.
   */
  public void rejected(SecretOwner owner) {
    switch (owner) {
      case LibraryOwned ignored -> {}
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
    ConnectionToken token =
        tokenOf(person).orElseThrow(() -> new SecretRefusedException(Reason.NOT_CONNECTED));
    if (!token.getIssuedFor().equals(target)) {
      throw new SecretRefusedException(Reason.TARGET_OUTSIDE_PROFILE);
    }
    if (token.expiredAt(clock.instant())) {
      throw new SecretRefusedException(Reason.EXPIRED);
    }
    String value = decrypt(token);
    return switch (token.getKind()) {
      case PERSONAL_SECRET -> new Secret(SecretKind.PERSONAL_SECRET, value);
      case OAUTH -> issuer().renew(person.profileId(), value, token.getIssuedFor());
    };
  }

  private SecretIssuer issuer() {
    SecretIssuer issuer = issuers.getIfAvailable();
    if (issuer == null) {
      throw new IllegalStateException(
          "An OAuth token is handed out through a SecretIssuer, and none is registered");
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
        usability.snapshot().withInactivityThreshold(INACTIVITY_THRESHOLD).statesOf(found);
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

  private Optional<UUID> accountIdOf(PersonOwned person) {
    return accounts.accountsAmong(Set.of(person.userId()), Set.of(person.profileId())).stream()
        .filter(key -> key.getUserId().equals(person.userId()))
        .filter(key -> key.getProfileId().equals(person.profileId()))
        .map(AccountKey::getId)
        .findFirst();
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
