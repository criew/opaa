package io.opaa.compliance;

import java.util.Map;

/** Renders the {@link FactSheet fact sheets} and their JSON form from a manifest. */
public final class FactSheetRenderer {

  private FactSheetRenderer() {}

  public static Map<FactSheet, String> render(
      ComplianceManifest manifest, FactSheetProvenance provenance) {
    throw new UnsupportedOperationException("not implemented");
  }

  public static String renderJson(ComplianceManifest manifest, FactSheetProvenance provenance) {
    throw new UnsupportedOperationException("not implemented");
  }

  public static String anchor(String identifier) {
    throw new UnsupportedOperationException("not implemented");
  }
}
