package io.opaa.architecture.fixture.profilesupport.connection;

import io.opaa.architecture.fixture.profilesupport.indexing.spi.ProfileDeclaration;

/** Reads the declared support itself; asking whether profiles are admitted is fine. */
public class ConnectorRelease {
  boolean ownAddress(ProfileDeclaration declaration) {
    return declaration.admitsProfiles() && !"REQUIRED".equals(declaration.support());
  }
}
