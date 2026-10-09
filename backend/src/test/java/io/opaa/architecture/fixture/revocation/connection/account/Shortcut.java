package io.opaa.architecture.fixture.revocation.connection.account;

import io.opaa.architecture.fixture.revocation.connection.token.SecretIssuer;

/** Runs the port's revocation itself, before any commit. */
public class Shortcut {
  void end(SecretIssuer issuer) {
    issuer.revocation("t").run();
  }
}
