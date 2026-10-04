package io.opaa.architecture.fixture.profileregistration.connection.oauth;

import io.opaa.architecture.fixture.profileregistration.connection.profile.ClientRegistration;
import io.opaa.architecture.fixture.profileregistration.connection.profile.ProfileRegistrations;

/** The sign-in reads the registration and hands out a token only. */
public class SignIn {
  String token(ProfileRegistrations registrations) {
    ClientRegistration registration = registrations.registrationOf("p");
    return registration.secret().isEmpty() ? null : "token";
  }
}
