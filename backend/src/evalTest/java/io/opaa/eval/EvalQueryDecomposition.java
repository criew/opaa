package io.opaa.eval;

/**
 * Whether a harness run measures the shipped {@code opaa.query.query-decomposition-enabled=true}
 * configuration or the decomposition-off one (issue #1085).
 *
 * <p>Off by default, which is what every committed pipeline baseline describes: the shipped
 * configuration costs one chat call per query and, under the Mehrfachlauf-Regel
 * (docs/features/retrieval-benchmark.md §3), three runs of the whole pipeline path — measured at
 * ~1.5 s per call on CPU (up to ~10 s under heavy load), more than the nightly job's budget
 * carries. Switching this on is a deliberate, manually invoked measurement whose fixed points
 * differ from the committed baseline's, so {@code PipelineBaselineComparator} reports it as an
 * incomparable baseline rather than as a regression.
 */
final class EvalQueryDecomposition {

  private static final String PROPERTY = "opaa.eval.queryDecomposition";

  private EvalQueryDecomposition() {}

  static boolean requestedForThisRun() {
    return Boolean.getBoolean(PROPERTY);
  }
}
