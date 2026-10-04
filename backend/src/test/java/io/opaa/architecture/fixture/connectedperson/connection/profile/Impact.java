package io.opaa.architecture.fixture.connectedperson.connection.profile;

/** An intermediate step that carries an exact count towards an answer. */
public class Impact {
  PersonConnections persons;

  public long connectedAccounts(String profileId) {
    return persons.countsAmong(profileId);
  }
}
