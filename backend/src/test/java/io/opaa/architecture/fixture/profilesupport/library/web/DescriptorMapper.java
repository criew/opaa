package io.opaa.architecture.fixture.profilesupport.library.web;

import io.opaa.architecture.fixture.profilesupport.indexing.source.ProfileDeclaration;

/** Outside connections the declaration is shown as it is. */
public class DescriptorMapper {
  String shown(ProfileDeclaration declaration) {
    return declaration.support();
  }
}
