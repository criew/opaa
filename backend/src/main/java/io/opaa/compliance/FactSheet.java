package io.opaa.compliance;

import java.util.Arrays;
import java.util.Optional;

/**
 * The fact sheets rendered from the {@link ComplianceManifest}. {@link #sheetName()} is the name a
 * template under {@code docs/behoerden/} uses in its placeholder {@code {{faktenblatt:<name>}}} and
 * the file name of the rendered sheet without {@code .md}; the name lists in {@code
 * docs/behoerden/README.md} and {@code check_behoerden_headers.sh} hold exactly these.
 */
public enum FactSheet {
  DATENKATALOG("datenkatalog", "Datenkatalog"),
  PROTOKOLLEREIGNISSE("protokollereignisse", "Protokollereignisse"),
  ROLLENSICHT("rollensicht", "Rollensicht"),
  DATENFLUESSE("datenfluesse", "Datenflüsse nach außen"),
  VOREINSTELLUNGEN("voreinstellungen", "Voreinstellungen"),
  FRISTEN("fristen", "Fristen");

  private final String sheetName;
  private final String title;

  FactSheet(String sheetName, String title) {
    this.sheetName = sheetName;
    this.title = title;
  }

  /** The placeholder name, lower case without umlauts. */
  public String sheetName() {
    return sheetName;
  }

  /** The German title of the sheet. */
  public String title() {
    return title;
  }

  /** The file name of the rendered sheet. */
  public String fileName() {
    return sheetName + ".md";
  }

  /** The sheet with this placeholder name, if any. */
  public static Optional<FactSheet> bySheetName(String sheetName) {
    return Arrays.stream(values()).filter(sheet -> sheet.sheetName.equals(sheetName)).findFirst();
  }
}
