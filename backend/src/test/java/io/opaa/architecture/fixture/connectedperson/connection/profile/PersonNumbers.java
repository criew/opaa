package io.opaa.architecture.fixture.connectedperson.connection.profile;

/** The one class that may ask the exact counts. */
public class PersonNumbers {
  PersonConnections persons;

  public String totalOf(String profileId) {
    return persons.countsAmong(profileId) < 5 ? "<5" : "exact";
  }
}
