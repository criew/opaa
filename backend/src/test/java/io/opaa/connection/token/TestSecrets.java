package io.opaa.connection.token;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.opaa.auth.AccountUsability;
import io.opaa.auth.UserRepository;
import io.opaa.knowledge.KnowledgeLibraryRepository;
import io.opaa.security.CredentialsEncryptor;
import java.time.Clock;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;

/** The secret store for unit tests of libraries' secrets: no person holds one. */
public final class TestSecrets {

  private TestSecrets() {}

  /** The store over {@code libraries}; {@code onProfile} names the libraries of a profile. */
  @SuppressWarnings("unchecked")
  public static ConnectionSecrets overLibraries(
      LibrariesOnProfile onProfile, KnowledgeLibraryRepository libraries) {
    return overLibraries(onProfile, libraries, mock(ObjectProvider.class));
  }

  /** {@link #overLibraries} with {@code issuer} for the profiles' own sign-in. */
  @SuppressWarnings("unchecked")
  public static ConnectionSecrets overLibraries(
      LibrariesOnProfile onProfile, KnowledgeLibraryRepository libraries, SecretIssuer issuer) {
    ObjectProvider<SecretIssuer> issuers = mock(ObjectProvider.class);
    when(issuers.getIfAvailable()).thenReturn(issuer);
    return overLibraries(onProfile, libraries, issuers);
  }

  private static ConnectionSecrets overLibraries(
      LibrariesOnProfile onProfile,
      KnowledgeLibraryRepository libraries,
      ObjectProvider<SecretIssuer> issuers) {
    return new ConnectionSecrets(
        onProfile,
        libraries,
        mock(ConnectionTokenRepository.class),
        (userIds, profileIds) -> List.of(),
        mock(UserRepository.class),
        mock(AccountUsability.class),
        mock(CredentialsEncryptor.class),
        issuers,
        Clock.systemUTC());
  }
}
