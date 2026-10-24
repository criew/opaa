package io.opaa.indexing.document;

import io.opaa.format.shared.IngestTextSanitizer.Finding;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The protocol text for what the cleanup after extraction found in one indexed document: counts per
 * class only, never a word of the document, so the run protocol carries no content.
 */
final class SuspiciousPatternNote {

  private SuspiciousPatternNote() {}

  /** The German note for {@code findings}; {@code null} when there is nothing to report. */
  static String of(Map<Finding, Integer> findings) {
    if (findings == null || findings.isEmpty()) {
      return null;
    }
    String counts =
        findings.entrySet().stream()
            .map(entry -> entry.getValue() + " × " + label(entry.getKey()))
            .collect(Collectors.joining(", "));
    return "Auffällige Muster im Text: " + counts + ". Das Dokument wurde aufgenommen.";
  }

  private static String label(Finding finding) {
    return switch (finding) {
      case INVISIBLE_CHARACTERS -> "unsichtbare Zeichen entfernt";
      case ROLE_TOKEN -> "Rollen-Token entschärft";
      case INSTRUCTION_PHRASE -> "Wendung wie „ignoriere alle vorherigen Anweisungen“";
      case DATA_URL_PLACEHOLDER -> "Adresse mit Platzhalter für Daten";
    };
  }
}
