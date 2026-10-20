package io.opaa.connection.token;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.opaa.auth.AccountUsability;
import io.opaa.auth.UserRepository;
import io.opaa.knowledge.KnowledgeLibraryRepository;
import io.opaa.security.CredentialsEncryptor;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The secret store for unit tests of libraries' secrets: no person holds one, and the rows of
 * libraries lie in a {@link Store} in memory, shared by every store built over it.
 */
public final class TestSecrets {

  private static final String MARKER = "enc:v1:";

  private TestSecrets() {}

  /** The store over {@code libraries}; {@code onProfile} names the libraries of a profile. */
  @SuppressWarnings("unchecked")
  public static ConnectionSecrets overLibraries(
      LibrariesOnProfile onProfile, KnowledgeLibraryRepository libraries) {
    return overLibraries(onProfile, libraries, new Store(), mock(ObjectProvider.class));
  }

  /** {@link #overLibraries} with the libraries' rows in {@code store}. */
  @SuppressWarnings("unchecked")
  public static ConnectionSecrets overLibraries(
      LibrariesOnProfile onProfile, KnowledgeLibraryRepository libraries, Store store) {
    return overLibraries(onProfile, libraries, store, mock(ObjectProvider.class));
  }

  /** {@link #overLibraries} with {@code issuer} for the profiles' own sign-in. */
  @SuppressWarnings("unchecked")
  public static ConnectionSecrets overLibraries(
      LibrariesOnProfile onProfile, KnowledgeLibraryRepository libraries, SecretIssuer issuer) {
    ObjectProvider<SecretIssuer> issuers = mock(ObjectProvider.class);
    when(issuers.getIfAvailable()).thenReturn(issuer);
    return overLibraries(onProfile, libraries, new Store(), issuers);
  }

  @SuppressWarnings("unchecked")
  private static ConnectionSecrets overLibraries(
      LibrariesOnProfile onProfile,
      KnowledgeLibraryRepository libraries,
      Store store,
      ObjectProvider<SecretIssuer> issuers) {
    return new ConnectionSecrets(
        onProfile,
        libraries,
        store.repository,
        new NoAccounts(),
        mock(UserRepository.class),
        mock(AccountUsability.class),
        store.encryptor,
        issuers,
        mock(ObjectProvider.class),
        mock(ObjectProvider.class),
        mock(GrantRevocations.class),
        ConnectionLifecycleProperties.defaults(),
        mock(PlatformTransactionManager.class),
        Clock.systemUTC());
  }

  /**
   * The rows of libraries in memory, with an encryption that marks a value instead of hiding it and
   * counts every decryption.
   */
  public static final class Store {

    private final Map<UUID, ConnectionToken> rows = new LinkedHashMap<>();
    private final AtomicInteger decryptions = new AtomicInteger();
    private final AtomicInteger singleReads = new AtomicInteger();
    private final AtomicInteger batchReads = new AtomicInteger();
    private final ConnectionTokenRepository repository = mock(ConnectionTokenRepository.class);
    private final CredentialsEncryptor encryptor = mock(CredentialsEncryptor.class);

    public Store() {
      when(encryptor.encrypt(any())).thenAnswer(call -> marked(call.getArgument(0)));
      when(encryptor.decrypt(anyString()))
          .thenAnswer(
              call -> {
                decryptions.incrementAndGet();
                String stored = call.getArgument(0);
                return stored.startsWith(MARKER) ? stored.substring(MARKER.length()) : stored;
              });
      when(repository.findByLibraryId(any()))
          .thenAnswer(
              call -> {
                singleReads.incrementAndGet();
                return ofLibrary(call.getArgument(0));
              });
      when(repository.findByLibraryIdIn(any()))
          .thenAnswer(
              call -> {
                batchReads.incrementAndGet();
                return call.<Collection<UUID>>getArgument(0).stream()
                    .map(this::ofLibrary)
                    .flatMap(Optional::stream)
                    .toList();
              });
      when(repository.findById(any()))
          .thenAnswer(call -> Optional.ofNullable(rows.get(call.<UUID>getArgument(0))));
      when(repository.findLockedById(any()))
          .thenAnswer(call -> Optional.ofNullable(rows.get(call.<UUID>getArgument(0))));
      when(repository.save(any()))
          .thenAnswer(
              call -> {
                ConnectionToken token = call.getArgument(0);
                rows.put(token.getId(), token);
                return token;
              });
      org.mockito.Mockito.doAnswer(
              call -> rows.remove(call.<ConnectionToken>getArgument(0).getId()))
          .when(repository)
          .delete(any());
      when(repository.findConsentsUnder(any()))
          .thenAnswer(call -> underProfile(call.getArgument(0)));
      when(repository.deleteConsentsUnder(any()))
          .thenAnswer(
              call -> {
                List<ConnectionToken> under = underProfile(call.getArgument(0));
                under.forEach(token -> rows.remove(token.getId()));
                return under.size();
              });
    }

    /**
     * Holds {@code value} as the own secret of {@code libraryId} on {@code profileId}, issued for
     * the origin of {@code address} - the target of a connector that binds no further.
     */
    public void hold(UUID profileId, UUID libraryId, String value, String address) {
      ConnectionToken token =
          ConnectionToken.ofLibrarySecret(
              profileId,
              libraryId,
              marked(value),
              io.opaa.connection.profile.ServerAddress.originOf(address),
              Instant.now());
      rows.put(token.getId(), token);
    }

    /** Holds a value the key cannot read as the own secret of {@code libraryId}. */
    public void holdUnreadable(UUID profileId, UUID libraryId, String target) {
      ConnectionToken token =
          ConnectionToken.ofLibrarySecret(
              profileId, libraryId, "plain-not-encrypted", target, Instant.now());
      rows.put(token.getId(), token);
    }

    /**
     * Moves the column of {@code library} - as connecting it through {@code profileId} does - into
     * the store, as {@link #hold}; a library without one is left alone.
     */
    public void moveIn(UUID profileId, io.opaa.knowledge.KnowledgeLibrary library, String address) {
      String secret = library.getSourceCredentials();
      if (secret != null) {
        hold(profileId, library.getId(), secret, address);
        library.dropSourceCredentials();
      }
    }

    /** Deletes whatever row {@code libraryId} holds, as a discard does. */
    public void drop(UUID libraryId) {
      ofLibrary(libraryId).ifPresent(token -> rows.remove(token.getId()));
    }

    /** The own secret of {@code libraryId} as stored, empty for none. */
    public Optional<String> ownSecretOf(UUID libraryId) {
      return ofLibrary(libraryId)
          .filter(token -> token.getKind() == ConnectionToken.Kind.PERSONAL_SECRET)
          .map(ConnectionToken::getSecretCiphertext)
          .map(stored -> stored.startsWith(MARKER) ? stored.substring(MARKER.length()) : stored);
    }

    /** The own secret of {@code libraryId} as the row holds it, encrypted, empty for none. */
    public Optional<String> rawOwnSecretOf(UUID libraryId) {
      return ofLibrary(libraryId)
          .filter(token -> token.getKind() == ConnectionToken.Kind.PERSONAL_SECRET)
          .map(ConnectionToken::getSecretCiphertext);
    }

    /** How many times the rows of one library were read so far. */
    public int singleReads() {
      return singleReads.get();
    }

    /** How many times the rows of several libraries were read in one query so far. */
    public int batchReads() {
      return batchReads.get();
    }

    /** How many values were decrypted so far. */
    public int decryptions() {
      return decryptions.get();
    }

    private Optional<ConnectionToken> ofLibrary(UUID libraryId) {
      return rows.values().stream().filter(t -> libraryId.equals(t.getLibraryId())).findFirst();
    }

    private List<ConnectionToken> underProfile(UUID profileId) {
      return rows.values().stream()
          .filter(t -> t.getProfileId().equals(profileId))
          .filter(t -> t.getLibraryId() != null || t.getPendingUserId() != null)
          .toList();
    }

    private static String marked(String value) {
      return value == null || value.isBlank() ? value : MARKER + value;
    }
  }

  private static final class NoAccounts implements PersonAccounts {
    @Override
    public List<AccountKey> accountsAmong(Collection<UUID> userIds, Collection<UUID> profileIds) {
      return List.of();
    }

    @Override
    public int markUsed(UUID accountId, Instant now, Instant notBefore) {
      return 0;
    }
  }
}
