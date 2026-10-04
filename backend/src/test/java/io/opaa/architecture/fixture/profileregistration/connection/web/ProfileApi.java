package io.opaa.architecture.fixture.profileregistration.connection.web;

import io.opaa.architecture.fixture.profileregistration.connection.profile.ProfileRegistrations;

/** The administration reads the registration directly. */
public abstract class ProfileApi {
  void read(ProfileRegistrations registrations) {
    registrations.registrationOf("p");
  }
}
