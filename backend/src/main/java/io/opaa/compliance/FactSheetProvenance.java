package io.opaa.compliance;

import java.time.LocalDate;
import java.util.Objects;

/**
 * The state a set of fact sheets describes, printed in the head of every sheet and the JSON form.
 *
 * @param version the OPAA version of the build, {@code 0.0.0-dev} outside a release
 * @param commit the commit the manifest was read from
 * @param generatedOn the day the sheets were rendered
 */
public record FactSheetProvenance(String version, String commit, LocalDate generatedOn) {

  public FactSheetProvenance {
    Objects.requireNonNull(version, "version");
    Objects.requireNonNull(commit, "commit");
    Objects.requireNonNull(generatedOn, "generatedOn");
  }
}
