package io.opaa.query.sandbox;

import java.time.Duration;

/**
 * The backend's own interface to the isolated execution environment (ADR-0073): the component
 * behind it (OpenSandbox today) is replaceable without touching a tool. A session is one sandbox
 * without network that lives at most {@code lifetime} and sees nothing but its own working
 * directory; everything that comes back from it is untrusted.
 */
public interface Sandboxes {

  /** The working directory of a sandbox. */
  String WORKSPACE_DIR = "/workspace";

  /** Where the sandbox image keeps the fixed scripts of the application's tools. */
  String INSTALLED_DIR = "/opt/opaa";

  /** The directory a script writes its result files to; the only one read back. */
  String OUTPUT_DIR = "/workspace/out";

  /** The most files {@link SandboxSession#collectOutput} reads; further ones stay unread. */
  int MAX_OUTPUT_FILES = 5;

  /** The largest file {@link SandboxSession#collectOutput} reads; a larger one stays unread. */
  long MAX_OUTPUT_FILE_BYTES = 10L * 1024 * 1024;

  /**
   * Starts a sandbox for one caller. The server removes it after {@code lifetime} (capped by the
   * server's own maximum) even when {@link SandboxSession#close()} never runs.
   *
   * @param readyWithin how long the caller waits for the sandbox to answer; past it the sandbox is
   *     deleted and the call fails.
   * @throws SandboxException when the sandbox server fails, cannot be reached or the sandbox is not
   *     ready in time.
   */
  SandboxSession open(Duration lifetime, Duration readyWithin);
}
