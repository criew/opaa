package io.opaa.architecture.fixture.connectedperson.connection.account;

import io.opaa.architecture.fixture.connectedperson.connection.token.ConnectionSecrets;

/** The one caller that may log the count. */
public class ConnectionLifecycleReconciler {
  ConnectionSecrets secrets;

  public long afterStart() {
    return secrets.countExpiredPersonSecrets();
  }
}
