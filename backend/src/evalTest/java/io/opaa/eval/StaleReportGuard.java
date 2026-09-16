package io.opaa.eval;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Removes a measurement path's report files before that path measures (#1671), so a run that ends
 * without writing one leaves none behind.
 *
 * <p>Both guarded paths swallow their own failures on purpose, and both report themselves as not
 * executed when a precondition fails - in either case they write nothing. Without this, the report
 * of the <em>previous</em> run stays in {@code build/eval-reports}, and the baseline check, which
 * only asks whether the file exists, compares it as if it were this run's measurement. The checks'
 * "no report is a failure" rule is the right one; this makes it reachable.
 */
final class StaleReportGuard {

  private StaleReportGuard() {}

  /**
   * Deletes {@code files} if they exist. Called before the guarded section of a measurement path,
   * so an {@link IOException} here fails the run rather than being swallowed - a report that cannot
   * be removed is one the baseline check would read as fresh.
   */
  static void discard(Path... files) {
    for (Path file : files) {
      try {
        Files.deleteIfExists(file);
      } catch (IOException e) {
        throw new UncheckedIOException(
            "Could not remove the previous report at '"
                + file.toAbsolutePath()
                + "'. Leaving it in place would let the baseline comparison of this run read the "
                + "previous run's measurement as this one's.",
            e);
      }
    }
  }
}
