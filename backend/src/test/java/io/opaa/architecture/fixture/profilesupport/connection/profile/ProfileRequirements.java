package io.opaa.architecture.fixture.profilesupport.connection.profile;

import io.opaa.architecture.fixture.profilesupport.indexing.source.ProfileDeclaration;

/** The one place in connections that reads the declared support. */
public class ProfileRequirements {
  public String effective(ProfileDeclaration declaration) {
    return declaration.support();
  }
}
