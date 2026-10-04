package io.opaa.architecture.fixture.connectedperson.connection.account;

/** Stores a connection without signing in; its own package signs in first. */
public class ConnectedAccountService {
  public void established(String profileId) {}

  public void connect(String profileId) {
    established(profileId);
  }
}
