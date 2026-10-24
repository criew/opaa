package io.opaa.linktarget;

import java.time.Instant;
import java.util.List;

/**
 * The allowed link targets as stored: normalized origins in the order the administration entered
 * them, empty by default.
 */
public record LinkTargetSettings(List<String> allowedOrigins, Instant updatedAt) {

  public LinkTargetSettings {
    allowedOrigins = List.copyOf(allowedOrigins);
  }
}
