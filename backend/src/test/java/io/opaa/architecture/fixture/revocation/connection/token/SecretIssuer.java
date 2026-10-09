package io.opaa.architecture.fixture.revocation.connection.token;

/** The port through which the store reaches a provider. */
public interface SecretIssuer {
  Runnable revocation(String token);
}
