package io.opaa.eval;

import io.opaa.llm.RerankModelRole;

/**
 * What a variant measurement needs to know about the rerank model role — before a variant, to
 * decide whether it may run at all, and after it, to decide whether what it measured is what its
 * name says.
 *
 * <p><b>Checking once, before the variant, is not enough.</b> {@code VariantPrerequisites} keeps a
 * variant that promises reranking from running against an unusable role, but an endpoint that dies
 * <i>during</i> the variant leaves every remaining question falling back to the fused order while
 * the variant's report still carries the reranking name — exactly the silent degradation the
 * prerequisite exists to prevent (issue #1050, docs/features/retrieval-benchmark.md §2).
 *
 * <p>{@link #degradedCallCount()} is the reading that survives a recovery: a role that fails and
 * comes back reports {@link #usable()} {@code true} afterwards, so the state alone would clear a
 * variant whose measurement ran straight through the outage.
 *
 * <p>An interface rather than the role itself, so {@code VariantRunnerTest} can exercise the
 * watchdog without a Spring context, an HTTP endpoint or Docker.
 */
interface RerankRunWatch {

  /** See {@link RerankModelRole#degradedCallCount()}. */
  long degradedCallCount();

  /** See {@link RerankModelRole#usable()}. */
  boolean usable();

  static RerankRunWatch of(RerankModelRole role) {
    return new RerankRunWatch() {
      @Override
      public long degradedCallCount() {
        return role.degradedCallCount();
      }

      @Override
      public boolean usable() {
        return role.usable();
      }
    };
  }
}
