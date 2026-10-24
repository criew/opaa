package io.opaa.indexing.source.sharepoint;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.indexing.source.sharepoint.DriveRule.ItemAddress;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The identities and sentences a {@link DriveRule} gives the shared drive store. */
class DriveRuleTest {

  private static final DriveRule OTHER =
      new DriveRule(
          "other",
          "Other",
          Set.of("personal", "business"),
          "kein Laufwerk dieser Art",
          "nicht sichtbar",
          "nicht mehr im Laufwerk",
          "beim Start nicht erreichbar",
          "Ordner „%s“ fehlt",
          "Laufwerk „%s“");

  @Test
  void sharePointIdentitiesKeepTheirScheme() {
    assertThat(DriveRule.SHAREPOINT.pathPrefix()).isEqualTo("sharepoint://");
    assertThat(DriveRule.SHAREPOINT.filePath("b!drive-0", "item_1"))
        .isEqualTo("sharepoint://b!drive-0/item_1");
  }

  @Test
  void anIdentityIsReadBackOnlyUnderItsOwnScheme() {
    String path = DriveRule.SHAREPOINT.filePath("b!drive-0", "item_1");

    assertThat(DriveRule.SHAREPOINT.address(path)).contains(new ItemAddress("b!drive-0", "item_1"));
    assertThat(OTHER.address(path)).isEmpty();
    assertThat(OTHER.address(OTHER.filePath("b!drive-0", "item_1")))
        .contains(new ItemAddress("b!drive-0", "item_1"));
  }

  @Test
  void anIdentityWithAnotherShapeOrForeignIdsNamesNoItem() {
    assertThat(DriveRule.SHAREPOINT.address(null)).isEmpty();
    assertThat(DriveRule.SHAREPOINT.address("sharepoint://b!drive-0")).isEmpty();
    assertThat(DriveRule.SHAREPOINT.address("sharepoint://b!drive-0/a/b")).isEmpty();
    assertThat(DriveRule.SHAREPOINT.address("sharepoint://b!drive-0/")).isEmpty();
    assertThat(DriveRule.SHAREPOINT.address("sharepoint://../item")).isEmpty();
    assertThat(DriveRule.SHAREPOINT.address("sharepoint://b!drive-0/item?x=1")).isEmpty();
  }

  @Test
  void onlyTheRulesDriveTypesAreAdmitted() {
    assertThat(DriveRule.SHAREPOINT.admits("documentLibrary")).isTrue();
    assertThat(DriveRule.SHAREPOINT.admits("business")).isFalse();
    assertThat(DriveRule.SHAREPOINT.admits("personal")).isFalse();
    assertThat(DriveRule.SHAREPOINT.admits(null)).isFalse();
    assertThat(OTHER.admits("personal")).isTrue();
    assertThat(OTHER.admits("documentLibrary")).isFalse();
  }

  @Test
  void sharePointSentencesStayAsTheyWere() {
    assertThat(DriveRule.SHAREPOINT.notAdmitted()).isEqualTo(DriveRule.NOT_A_LIBRARY);
    assertThat(DriveRule.SHAREPOINT.notVisible()).isEqualTo(DriveRule.LIBRARY_NOT_VISIBLE);
    assertThat(DriveRule.SHAREPOINT.fileGone())
        .isEqualTo("Die Datei existiert in SharePoint nicht mehr.");
    assertThat(DriveRule.SHAREPOINT.folderMissing("Akten 50%"))
        .isEqualTo(
            "Der gewählte Ordner „Akten 50%“ ist in der Dokumentbibliothek nicht mehr vorhanden;"
                + " bitte die Ordnerauswahl anpassen.");
    assertThat(DriveRule.SHAREPOINT.driveNamed("Dokumente"))
        .isEqualTo("Dokumentbibliothek „Dokumente“");
  }

  @Test
  void anotherRuleNamesItsOwnDrive() {
    assertThat(OTHER.fileGone()).isEqualTo("Die Datei existiert in Other nicht mehr.");
    assertThat(OTHER.folderMissing("Akten")).isEqualTo("Ordner „Akten“ fehlt");
    assertThat(OTHER.driveNamed("Mein Laufwerk")).isEqualTo("Laufwerk „Mein Laufwerk“");
  }
}
