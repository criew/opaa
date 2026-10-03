package io.opaa.indexing.source.filesystem;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.common.ValidationException;
import io.opaa.indexing.source.ConnectorData;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FilesystemSourceSettingsTest {

  @Test
  void readsTrimmedPatternsAndWritesThemBack() {
    FilesystemSourceSettings settings =
        FilesystemSourceSettings.of(
            ConnectorData.of(Map.of("excludePatterns", List.of(" Archiv/** ", "**/*.tmp"))));

    assertThat(settings.excludePatterns()).containsExactly("Archiv/**", "**/*.tmp");
    assertThat(settings.toData().asMap())
        .isEqualTo(Map.of("excludePatterns", List.of("Archiv/**", "**/*.tmp")));
  }

  @Test
  void absentSettingsMeanNoPatterns() {
    assertThat(FilesystemSourceSettings.of(null).excludePatterns()).isEmpty();
    assertThat(FilesystemSourceSettings.of(ConnectorData.of(Map.of())).excludePatterns()).isEmpty();
  }

  @Test
  void anInvalidGlobIsRefusedWithAGermanMessageNamingThePattern() {
    assertThatThrownBy(() -> new FilesystemSourceSettings(List.of("Archiv/[2020")))
        .isInstanceOf(ValidationException.class)
        .hasMessage(
            "sourceSettings: Ausschlussmuster: „Archiv/[2020“ ist kein gültiges Glob-Muster, es"
                + " enthält eine „[“ ohne schließende „]“.");
  }

  @Test
  void patternsThatCouldNeverMatchAreRefused() {
    assertThatThrownBy(() -> new FilesystemSourceSettings(List.of("Archiv/")))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("leere Ebene");
    assertThatThrownBy(() -> new FilesystemSourceSettings(List.of("./Archiv/**")))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("die Ebene „.“");
    assertThatThrownBy(() -> new FilesystemSourceSettings(List.of("Archiv/../Plan.pdf")))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("die Ebene „..“");
    assertThatThrownBy(() -> new FilesystemSourceSettings(List.of("Archiv\\2020\\*")))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("Ebenen werden mit „/“ getrennt");
  }

  @Test
  void tooManyAlternativesAreRefused() {
    assertThatThrownBy(() -> new FilesystemSourceSettings(List.of("{a,b,c,d}{a,b,c,d}{a,b,c}")))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("mehr als 32 Alternativen");
  }

  @Test
  void anAbsolutePatternIsRefusedBecausePatternsAreRelative() {
    assertThatThrownBy(() -> new FilesystemSourceSettings(List.of("/data/Archiv/**")))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("relativ zum Verzeichnispfad");
  }

  @Test
  void blankDuplicateTooLongAndTooManyPatternsAreRefused() {
    assertThatThrownBy(() -> new FilesystemSourceSettings(List.of("  ")))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("leeres Muster");
    assertThatThrownBy(() -> new FilesystemSourceSettings(List.of("*.tmp", "*.tmp")))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("mehrfach");
    assertThatThrownBy(() -> new FilesystemSourceSettings(List.of("a".repeat(256))))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("255 Zeichen");
    assertThatThrownBy(() -> new FilesystemSourceSettings(Collections.nCopies(51, "x")))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("höchstens 50");
  }

  @Test
  void anUnknownFieldOrANonListValueIsRefused() {
    assertThatThrownBy(
            () ->
                FilesystemSourceSettings.of(ConnectorData.of(Map.of("includePatterns", List.of()))))
        .isInstanceOf(ValidationException.class)
        .hasMessage("sourceSettings: das Feld includePatterns ist nicht vorgesehen");
    assertThatThrownBy(
            () -> FilesystemSourceSettings.of(ConnectorData.of(Map.of("excludePatterns", "*.tmp"))))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("als Liste");
  }
}
