package io.opaa.query.absence;

import io.opaa.compliance.InstallationWide;
import io.opaa.compliance.NoDataImpact;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The recheck of an answer that claims something is missing from the sources ({@link
 * AbsenceRecheck}).
 *
 * @param enabled whether such an answer is checked against the question's search scope and, with
 *     new passages found there, answered once more.
 * @param maxPassages passages of the recheck search that go into the second answer at most, before
 *     their neighbours are added (1-20).
 */
@InstallationWide
@NoDataImpact("searches only the scope the question already searched")
@ConfigurationProperties(prefix = "opaa.query.absence-recheck")
public record AbsenceRecheckProperties(
    @DefaultValue("true") @NoDataImpact("switch of a second search and answer within the scope")
        boolean enabled,
    @DefaultValue("4") int maxPassages) {

  public AbsenceRecheckProperties {
    if (maxPassages < 1 || maxPassages > 20) {
      throw new IllegalArgumentException("maxPassages must be within [1, 20], got " + maxPassages);
    }
  }
}
