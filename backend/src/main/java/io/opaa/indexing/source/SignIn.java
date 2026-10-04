package io.opaa.indexing.source;

import io.opaa.api.types.ConnectionAuthMethod;
import io.opaa.api.types.ConnectionOwnership;
import io.opaa.api.types.PersonalSecretForm;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * One sign-in method a connector offers, with the owners ({@code LIBRARY}, {@code PERSON}) a
 * connection signing in this way may have. A personal secret names its form; a service account key
 * and client credentials carry their token endpoint and are owned by a library only (ADR-0038,
 * Nachtrag Verbindungsprofile).
 */
public record SignIn(
    ConnectionAuthMethod method, Set<ConnectionOwnership> owners, SignInDetails details) {

  public SignIn {
    Objects.requireNonNull(method, "method");
    Objects.requireNonNull(details, "details");
    if (owners == null || owners.isEmpty() || owners.contains(ConnectionOwnership.BOTH)) {
      throw new IllegalArgumentException("a sign-in names LIBRARY, PERSON or both as owners");
    }
    owners = Set.copyOf(EnumSet.copyOf(owners));
    boolean keyMethod = method == ConnectionAuthMethod.SERVICE_ACCOUNT_KEY;
    if (keyMethod != details instanceof ServiceAccountKeyAuth) {
      throw new IllegalArgumentException(
          "a service account key sign-in, and only that, names its token endpoint");
    }
    if (keyMethod && !owners.equals(Set.of(ConnectionOwnership.LIBRARY))) {
      throw new IllegalArgumentException("only a library owns a service account key");
    }
    boolean clientMethod = method == ConnectionAuthMethod.CLIENT_CREDENTIALS;
    if (clientMethod != details instanceof ClientCredentialsAuth) {
      throw new IllegalArgumentException(
          "a client credentials sign-in, and only that, names its token endpoint");
    }
    if (clientMethod && !owners.equals(Set.of(ConnectionOwnership.LIBRARY))) {
      throw new IllegalArgumentException("only a library owns a client credentials sign-in");
    }
    if ((method == ConnectionAuthMethod.PERSONAL_SECRET) != details instanceof PersonalSecretAuth) {
      throw new IllegalArgumentException(
          "a personal secret sign-in, and only that, names the form of its secret");
    }
  }

  /** {@code method} without details, for connections owned by {@code owners}. */
  public static SignIn of(ConnectionAuthMethod method, ConnectionOwnership... owners) {
    return new SignIn(method, Set.of(owners), NoDetails.INSTANCE);
  }

  /** The sign-in by a personal secret of {@code form}, for connections owned by {@code owners}. */
  public static SignIn personalSecret(PersonalSecretForm form, ConnectionOwnership... owners) {
    return new SignIn(
        ConnectionAuthMethod.PERSONAL_SECRET, Set.of(owners), new PersonalSecretAuth(form));
  }

  /** The form of the personal secret, {@code null} for any other method. */
  public PersonalSecretForm secretForm() {
    return details instanceof PersonalSecretAuth auth ? auth.form() : null;
  }

  /** The sign-in by service account key, which the core performs for a library. */
  public static SignIn serviceAccountKey(ServiceAccountKeyAuth auth) {
    return new SignIn(
        ConnectionAuthMethod.SERVICE_ACCOUNT_KEY, Set.of(ConnectionOwnership.LIBRARY), auth);
  }

  /** The sign-in by the profile's client credentials, for a library. */
  public static SignIn clientCredentials(ClientCredentialsAuth auth) {
    return new SignIn(
        ConnectionAuthMethod.CLIENT_CREDENTIALS, Set.of(ConnectionOwnership.LIBRARY), auth);
  }

  /** Whether a profile of {@code ownership} may choose this sign-in. */
  public boolean admits(ConnectionOwnership ownership) {
    return switch (ownership) {
      case LIBRARY, PERSON -> owners.contains(ownership);
      case BOTH ->
          owners.contains(ConnectionOwnership.LIBRARY)
              && owners.contains(ConnectionOwnership.PERSON);
    };
  }
}
