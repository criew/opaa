package io.opaa.architecture.fixture.processstart.library;

import java.io.IOException;

/** Starts processes outside the manifest delta. */
public class Shell {

  Process run() throws IOException {
    return new ProcessBuilder("ls").start();
  }

  Process exec() throws IOException {
    return Runtime.getRuntime().exec(new String[] {"ls"});
  }
}
