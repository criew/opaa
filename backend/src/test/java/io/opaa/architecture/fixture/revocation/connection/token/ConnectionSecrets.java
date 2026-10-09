package io.opaa.architecture.fixture.revocation.connection.token;

/** The store hands a revocation to the pool after the commit. */
public class ConnectionSecrets {
  Runnable afterCommit(SecretIssuer issuer) {
    return issuer.revocation("t");
  }
}
