package io.opaa.compliance;

import java.util.List;

/** The manifest on the classpath breaks its schema; the message lists every violation. */
public final class ComplianceManifestException extends RuntimeException {

  private final List<String> violations;

  public ComplianceManifestException(List<String> violations) {
    super("Invalid compliance manifest (see ADR-0052):\n- " + String.join("\n- ", violations));
    this.violations = List.copyOf(violations);
  }

  public List<String> violations() {
    return violations;
  }
}
