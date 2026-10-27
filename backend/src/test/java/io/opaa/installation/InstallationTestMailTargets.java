package io.opaa.installation;

import java.util.List;

/** Mail targets of the installation for unit tests: only loopback is released. */
final class InstallationTestMailTargets {

  static final InstallationMailTargets LOOPBACK =
      new InstallationMailTargets(
          new InstallationProperties(null, List.of(), null, List.of("127.0.0.1")));

  private InstallationTestMailTargets() {}
}
