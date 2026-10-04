package io.opaa.architecture.fixture.connectedperson.connection.profile;

import io.opaa.architecture.fixture.connectedperson.connection.token.ConnectionSecrets;

/** Another class that carries the log-only count towards an answer. */
public class ExpiredShare {
  ConnectionSecrets secrets;

  public long expired() {
    return secrets.countExpiredPersonSecrets();
  }
}
