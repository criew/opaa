package io.opaa.indexing.document;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.format.shared.IngestTextSanitizer.Finding;
import java.util.EnumMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SuspiciousPatternNoteTest {

  @Test
  void theNoteNamesEachClassWithItsCount() {
    Map<Finding, Integer> findings = new EnumMap<>(Finding.class);
    findings.put(Finding.INVISIBLE_CHARACTERS, 17);
    findings.put(Finding.ROLE_TOKEN, 2);
    findings.put(Finding.DATA_URL_PLACEHOLDER, 1);

    assertThat(SuspiciousPatternNote.of(findings))
        .isEqualTo(
            "Auffällige Muster im Text: 17 × unsichtbare Zeichen entfernt, 2 × Rollen-Token"
                + " entschärft, 1 × Adresse mit Platzhalter für Daten. Das Dokument wurde"
                + " aufgenommen.");
  }

  @Test
  void withoutAFindingThereIsNoNote() {
    assertThat(SuspiciousPatternNote.of(Map.of())).isNull();
  }
}
