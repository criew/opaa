package io.opaa.query.sandbox;

import java.time.Duration;
import java.util.List;
import java.util.function.Predicate;

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
   * Writes {@code content} to {@code path} in the working directory, readable by the scripts; an
   * existing file is replaced.
   *
   * @param path {@link Sandboxes#WORKSPACE_DIR}, a slash and a plain file name.
   */
  void upload(String path, byte[] content);

  /**
   * Runs a script that the sandbox image brings along under {@link Sandboxes#INSTALLED_DIR}, never
   * one written by the model, isolated from the working directory's Python files; otherwise as
   * {@link #run}.
   */
  ScriptRun runInstalled(String scriptPath, List<String> arguments, Duration timeout);

  /**
   * The plain files directly in {@code directory} with the size the sandbox reports, without
   * reading them; bounded in number.
   *
   * @param directory below {@link Sandboxes#WORKSPACE_DIR}, plain segments only.
   */
  List<OutputEntry> listFiles(String directory);

  /**
   * One file of {@code directory}, read as it is now; {@code null} when it is larger than {@link
   * Sandboxes#MAX_OUTPUT_FILE_BYTES}.
   *
   * @param name a plain file name as {@link #listFiles} listed it.
   */
  byte[] readFile(String directory, String name);

  /**
   * The files directly in {@link Sandboxes#OUTPUT_DIR} with the size the sandbox reports, without
   * reading them; bounded in number.
   */
  List<OutputEntry> outputEntries();

  /**
   * Reads the files of {@link #outputEntries()} that {@link OutputSelection} takes under {@code
   * accepted}; every other entry is skipped unread. Files not read when {@code within} has passed
   * are left out.
   */
  List<OutputFile> collectOutput(Predicate<String> accepted, Duration within);

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
