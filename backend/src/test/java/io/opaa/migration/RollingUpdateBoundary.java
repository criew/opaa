package io.opaa.migration;

/**
 * The one version boundary without a rolling update so far (backend/docs/migrationen.md, "Rolling
 * Update"): every installation updates to {@value #FIRST_RELEASE_WITH_THE_RULE} with all its
 * instances stopped. The module baselines (version 1.x) lie before it - they are the schema that
 * release starts from - and need neither a contract marker nor a lock timeout; every later
 * migration follows both rules.
 */
final class RollingUpdateBoundary {

  /** The release that first enforces the rule; every update to it is one without rolling update. */
  static final String FIRST_RELEASE_WITH_THE_RULE = "v0.1.0";

  private RollingUpdateBoundary() {}

  /** Whether {@code script} lies before the boundary. */
  static boolean before(String script) {
    return Migrations.version(script).getMajor().intValue() == 1;
  }
}
