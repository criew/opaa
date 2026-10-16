package io.opaa.architecture.fixture.processstart.compliance;

import java.io.IOException;

/** Allowed to start processes. */
public class ManifestSnapshot {

  Process status() throws IOException {
    return new ProcessBuilder("git", "status").start();
  }
}
