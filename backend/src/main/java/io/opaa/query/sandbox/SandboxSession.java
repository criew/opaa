package io.opaa.query.sandbox;

import java.time.Duration;
import java.util.List;

/**
 * One sandbox, opened by {@link Sandboxes#open}. Scripts run one after the other in the same
 * working directory, so a later script sees the files an earlier one wrote. Never shared between
 * runs or persons. {@link #close()} deletes the sandbox and never throws.
 */
public interface SandboxSession extends AutoCloseable {

  /** The sandbox's id as the server named it; technical, without any person reference. */
  String id();

  /**
   * Runs {@code code} as {@code main.py}; a failing script is a result with a non-zero exit code,
   * not an exception.
   *
   * @param timeout the script's time limit; it is stopped there and the result says so.
   */
  ScriptRun run(String code, Duration timeout);

  /**
   * The files directly in {@link Sandboxes#OUTPUT_DIR} with the size the sandbox reports, without
   * reading them; bounded in number.
   */
  List<OutputEntry> outputEntries();

  /**
   * Reads the files directly in {@link Sandboxes#OUTPUT_DIR}, bounded in number and in size each;
   * every other entry and every file beyond the bounds is skipped unread.
   */
  List<OutputFile> collectOutput();

  @Override
  void close();

  /**
   * @param exitCode the script's exit code; {@code -1} when the run was stopped at its time limit.
   */
  record ScriptRun(int exitCode, String stdout, String stderr, boolean timedOut) {}

  /** A file of the output directory as listed; {@code name} is untrusted. */
  record OutputEntry(String name, long size) {}

  /** A file of the output directory as read; {@code name} is untrusted. */
  record OutputFile(String name, byte[] content) {}
}
