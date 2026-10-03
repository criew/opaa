package io.opaa.indexing.source.nextcloud;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.common.ValidationException;
import io.opaa.indexing.source.ConnectorData;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The folder selection and the instance address as they are stored. */
class NextcloudSourceSettingsTest {

  private static NextcloudSourceSettings read(Object folders) {
    return NextcloudSourceSettings.read(ConnectorData.of(Map.of("folders", folders)));
  }

  @Test
  void foldersAreNormalisedToOneLeadingAndNoTrailingSlash() {
    assertThat(read(List.of("/")).folders()).containsExactly("/");
    assertThat(read(List.of("Projekte/", " /Gruppen//Akten ")).folders())
        .containsExactly("/Projekte", "/Gruppen/Akten");
  }

  @Test
  void overlappingFoldersAreRefusedButANamesakeSiblingIsNot() {
    assertThatThrownBy(() -> read(List.of("/Projekte", "/Projekte/Akten")))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("überschneiden sich");
    assertThatThrownBy(() -> read(List.of("/", "/Projekte")))
        .isInstanceOf(ValidationException.class);
    assertThat(read(List.of("/Projekte", "/Projekte-Archiv")).folders()).hasSize(2);
  }

  @Test
  void aFolderPathWithATraversalACommaOrNothingIsRefused() {
    for (String path : List.of("/a/../b", "/a,b", "", "/a/./b")) {
      assertThatThrownBy(() -> read(List.of(path)))
          .as(path)
          .isInstanceOf(ValidationException.class);
    }
    assertThatThrownBy(() -> read(List.of()))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("mindestens ein Ordner");
    assertThatThrownBy(
            () ->
                NextcloudSourceSettings.read(
                    ConnectorData.of(Map.of("folders", List.of("/a"), "region", "x"))))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("region");
  }

  @Test
  void theAddressIsCutBeforeAPastedWebDavPathAndLosesItsTrailingSlash() {
    assertThat(
            NextcloudConnection.normalizeBaseUrl(
                "https://Cloud.Example.org/nextcloud/remote.php/dav/files/techniker/"))
        .isEqualTo(URI.create("https://cloud.example.org/nextcloud"));
    assertThat(NextcloudConnection.normalizeBaseUrl("https://cloud.example.org/index.php/apps"))
        .isEqualTo(URI.create("https://cloud.example.org"));
    assertThat(NextcloudConnection.normalizeBaseUrl("http://127.0.0.1:8080/"))
        .isEqualTo(URI.create("http://127.0.0.1:8080"));
  }

  @Test
  void anAddressWithCredentialsQueryOrAnotherSchemeIsRefused() {
    for (String url :
        List.of("ftp://cloud.example.org", "https://u:p@cloud.example.org", "https://c.org/?a=1")) {
      assertThatThrownBy(() -> NextcloudConnection.normalizeBaseUrl(url))
          .as(url)
          .isInstanceOf(NextcloudConnection.InvalidNextcloudConfigurationException.class);
    }
  }

  @Test
  void theConnectionNeverNamesThePassword() {
    NextcloudConnection connection =
        NextcloudConnection.of(
            NextcloudTestStores.settings("https://cloud.example.org", "u:geheim", List.of("/")),
            "techniker:sehr-geheim");

    assertThat(connection.toString()).doesNotContain("sehr-geheim");
    assertThat(connection.username()).isEqualTo("techniker");
  }
}
