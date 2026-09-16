package io.opaa.eval;

import java.nio.file.Path;

/**
 * The directory the guarded measurement paths write their reports to - {@code build/eval-reports}
 * of the working directory, unless {@value #PROPERTY} names another one.
 *
 * <p><b>The property exists for tests, and only they set it.</b> {@link StaleReportGuard} deletes
 * what these paths name, and the Docker-free tests that exercise it run inside {@code
 * evalUnitTest}, which is part of {@code check} and therefore of every {@code build} - without a
 * directory of their own they would delete the report of an actual measurement run.
 */
final class EvalReportDirectory {

  static final String PROPERTY = "opaa.eval.reportDir";

  private static final String DEFAULT = "build/eval-reports";

  private EvalReportDirectory() {}

  static Path resolve(String fileName) {
    return Path.of(System.getProperty(PROPERTY, DEFAULT)).resolve(fileName);
  }
}
