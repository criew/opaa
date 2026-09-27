package io.opaa.permission.web;

import io.opaa.api.dto.SuccessionStateResponse;
import io.opaa.permission.SuccessionFinding;

/**
 * The marking "Nachfolge offen" at an object, for every web package that shows one: state and
 * addressee only - no date, no previous owner, no reason (Personalrat Z5).
 */
public final class SuccessionStateResponseMapper {

  private SuccessionStateResponseMapper() {}

  /** What holds and who is responsible, and nothing else; {@code null} for an object in order. */
  public static SuccessionStateResponse toStateResponse(SuccessionFinding finding) {
    if (finding == null) {
      return null;
    }
    return new SuccessionStateResponse(finding.addressee(), finding.addresseeLabel());
  }
}
