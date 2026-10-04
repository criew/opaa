package io.opaa.architecture.fixture.profileregistration.library;

import io.opaa.architecture.fixture.profileregistration.connection.profile.ProfileRegistrations;

/** library signs with the profile's key itself. */
public class KeyShortcut {
  void sign(ProfileRegistrations registrations) {
    registrations.registrationOf("p");
  }
}
