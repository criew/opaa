package io.opaa.indexing.source.smb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.common.ValidationException;
import io.opaa.indexing.source.ConnectorData;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SmbSourceSettingsTest {

  @Test
  void withoutFoldersTheWholeShareIsRead() {
    assertThat(SmbSourceSettings.read(null).folders()).containsExactly("/");
    assertThat(SmbSourceSettings.read(ConnectorData.of(Map.of())).folders()).containsExactly("/");
    assertThat(SmbSourceSettings.read(ConnectorData.of(Map.of("folders", List.of()))).folders())
        .containsExactly("/");
  }

  @Test
  void foldersAreNormalisedWithBackslashesAsSlashes() {
    SmbSourceSettings settings =
        SmbSourceSettings.read(
            ConnectorData.of(Map.of("folders", List.of(" Akten\\2026\\ ", "/Protokolle/"))));

    assertThat(settings.folders()).containsExactly("/Akten/2026", "/Protokolle");
    assertThat(settings.toData().get("folders")).isEqualTo(List.of("/Akten/2026", "/Protokolle"));
  }

  @Test
  void overlappingFoldersAreRefusedWhateverTheirCase() {
    assertThatThrownBy(
            () ->
                SmbSourceSettings.read(
                    ConnectorData.of(Map.of("folders", List.of("/Akten", "/akten/2026")))))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("überschneiden sich");
  }

  @ParameterizedTest
  @ValueSource(strings = {"/Akten/../Geheim", "/A*", "/Akten,Protokolle", "/Akten/x?", "  "})
  void anInvalidFolderIsRefused(String folder) {
    assertThatThrownBy(
            () -> SmbSourceSettings.read(ConnectorData.of(Map.of("folders", List.of(folder)))))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void anUnknownFieldIsRefused() {
    assertThatThrownBy(() -> SmbSourceSettings.read(ConnectorData.of(Map.of("domain", "X"))))
        .isInstanceOf(ValidationException.class);
  }
}
