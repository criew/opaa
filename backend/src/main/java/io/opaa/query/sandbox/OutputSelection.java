package io.opaa.query.sandbox;

import io.opaa.query.sandbox.SandboxSession.OutputEntry;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Which files of the output directory are read back: in listing order, the first {@link
 * Sandboxes#MAX_OUTPUT_FILES} whose name {@code accepted} admits and whose size is within {@link
 * Sandboxes#MAX_OUTPUT_FILE_BYTES}. A refused file takes no place, so the report to the model and
 * the collection agree file by file.
 */
public final class OutputSelection {

  /** What happens to one listed file. */
  public enum Verdict {
    TAKEN,
    FORMAT,
    TOO_LARGE,
    TOO_MANY
  }

  private OutputSelection() {}

  /** One verdict per entry, in the order of {@code entries}. */
  public static List<Verdict> of(List<OutputEntry> entries, Predicate<String> accepted) {
    List<Verdict> verdicts = new ArrayList<>(entries.size());
    int taken = 0;
    for (OutputEntry entry : entries) {
      Verdict verdict;
      if (!accepted.test(entry.name())) {
        verdict = Verdict.FORMAT;
      } else if (entry.size() > Sandboxes.MAX_OUTPUT_FILE_BYTES) {
        verdict = Verdict.TOO_LARGE;
      } else if (taken == Sandboxes.MAX_OUTPUT_FILES) {
        verdict = Verdict.TOO_MANY;
      } else {
        verdict = Verdict.TAKEN;
        taken++;
      }
      verdicts.add(verdict);
    }
    return verdicts;
  }
}
