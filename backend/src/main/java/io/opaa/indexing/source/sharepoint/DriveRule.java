package io.opaa.indexing.source.sharepoint;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * What one kind of Graph drive sets in the shared {@link DriveFileStore}: the {@code driveType}s it
 * reads, the scheme of its identities {@code <scheme>://<drive>/<item>} (also the prefix of the
 * download threads), the product {@code label} of its logs and the sentences naming the drive;
 * {@code notVisible} answers a drive Graph refuses with {@code 403} or {@code 404}, the templates
 * {@code folderMissingTemplate} and {@code driveNamedTemplate} hold one {@code %s} for the name.
 */
record DriveRule(
    String scheme,
    String label,
    Set<String> driveTypes,
    String notAdmitted,
    String notVisible,
    String fileOutsideDrive,
    String unreachableAtStart,
    String folderMissingTemplate,
    String driveNamedTemplate) {

  private static final Pattern SCHEME = Pattern.compile("[a-z][a-z0-9]*");

  static final String NOT_A_LIBRARY =
      "Das Laufwerk ist keine SharePoint-Dokumentbibliothek; OneDrive und andere Laufwerke werden"
          + " nicht gelesen.";
  static final String LIBRARY_NOT_VISIBLE =
      "Die Dokumentbibliothek ist für die Anwendung nicht sichtbar. Bei der Berechtigung"
          + " Sites.Selected muss die Site für die App freigegeben sein.";

  /** The document libraries of SharePoint sites (ADR-0047). */
  static final DriveRule SHAREPOINT =
      new DriveRule(
          "sharepoint",
          "SharePoint",
          Set.of("documentLibrary"),
          NOT_A_LIBRARY,
          LIBRARY_NOT_VISIBLE,
          "Die Datei liegt nicht mehr in der Bibliothek.",
          "Die Dokumentbibliothek war beim Beginn des letzten Vollabgleichs nicht erreichbar.",
          "Der gewählte Ordner „%s“ ist in der Dokumentbibliothek nicht mehr vorhanden; bitte die"
              + " Ordnerauswahl anpassen.",
          "Dokumentbibliothek „%s“");

  DriveRule {
    Objects.requireNonNull(scheme, "scheme");
    Objects.requireNonNull(label, "label");
    Objects.requireNonNull(notAdmitted, "notAdmitted");
    Objects.requireNonNull(notVisible, "notVisible");
    Objects.requireNonNull(fileOutsideDrive, "fileOutsideDrive");
    Objects.requireNonNull(unreachableAtStart, "unreachableAtStart");
    Objects.requireNonNull(folderMissingTemplate, "folderMissingTemplate");
    Objects.requireNonNull(driveNamedTemplate, "driveNamedTemplate");
    if (!SCHEME.matcher(scheme).matches()) {
      throw new IllegalArgumentException("scheme must match " + SCHEME + ": " + scheme);
    }
    driveTypes = Set.copyOf(driveTypes);
  }

  /** A drive and one of its items, as an identity of this kind names them. */
  record ItemAddress(String driveId, String itemId) {}

  /** {@code <scheme>://} - every identity of this kind starts with it. */
  String pathPrefix() {
    return scheme + "://";
  }

  String filePath(String driveId, String itemId) {
    return pathPrefix() + driveId + "/" + itemId;
  }

  /**
   * The drive and item {@code filePath} names, empty for an identity of another kind or one whose
   * ids are no Graph ids ({@link SharePointLibrary#ID}).
   */
  Optional<ItemAddress> address(String filePath) {
    if (filePath == null || !filePath.startsWith(pathPrefix())) {
      return Optional.empty();
    }
    String[] parts = filePath.substring(pathPrefix().length()).split("/", -1);
    if (parts.length != 2
        || !SharePointLibrary.ID.matcher(parts[0]).matches()
        || !SharePointLibrary.ID.matcher(parts[1]).matches()) {
      return Optional.empty();
    }
    return Optional.of(new ItemAddress(parts[0], parts[1]));
  }

  boolean admits(String driveType) {
    return driveType != null && driveTypes.contains(driveType);
  }

  String fileGone() {
    return "Die Datei existiert in " + label + " nicht mehr.";
  }

  String folderMissing(String folderName) {
    return folderMissingTemplate.formatted(folderName);
  }

  String driveNamed(String driveName) {
    return driveNamedTemplate.formatted(driveName);
  }
}
