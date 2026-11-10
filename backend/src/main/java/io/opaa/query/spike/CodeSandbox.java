package io.opaa.query.spike;

import io.opaa.query.sandbox.Sandboxes;
import java.util.List;

/**
 * Runs one Python script in a fresh, network-less sandbox that exists only for this call and
 * returns what the script printed and the files it left in {@link #OUTPUT_DIR}. Every file name and
 * every byte that comes back is untrusted: the caller bounds and sanitises them before use.
 */
interface CodeSandbox {

  /** The directory a script writes its result files to; the only one read back. */
  String OUTPUT_DIR = Sandboxes.OUTPUT_DIR;

  /**
   * Runs {@code code}; a failing script is a result with a non-zero exit code, not an exception.
   */
  Run run(String code);

  /**
   * @param exitCode the script's exit code; {@code -1} when the run was stopped at its time limit.
   * @param files the files found in {@link #OUTPUT_DIR}, already bounded in count and size.
   */
  record Run(
      int exitCode, String stdout, String stderr, boolean timedOut, List<OutputFile> files) {}

  /** One file of {@link #OUTPUT_DIR}; {@code name} is the bare name as the sandbox reported it. */
  record OutputFile(String name, byte[] content) {}
}
