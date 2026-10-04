package io.opaa.architecture.fixture.connectedperson.connection.account;

/** The numbers the administration may see. */
public class ConnectedAccountCounts {
  public PersonCount masked(long count) {
    return new PersonCount(count, null);
  }
}
