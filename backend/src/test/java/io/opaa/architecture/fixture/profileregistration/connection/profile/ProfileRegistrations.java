package io.opaa.architecture.fixture.profileregistration.connection.profile;

/** Builds the registration with its secret. */
public class ProfileRegistrations {
  public ClientRegistration registrationOf(String profileId) {
    return new ClientRegistration(profileId);
  }
}
