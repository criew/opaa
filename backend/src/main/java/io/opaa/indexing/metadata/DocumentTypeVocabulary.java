package io.opaa.indexing.metadata;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The controlled Dokumentart vocabulary as an in-memory snapshot (metadata-schema.md, Teil II (a)).
 * {@link #resolve} maps a token to a code only on an exact, case- and umlaut-insensitive match of
 * the code, the label or a seeded synonym - never on similarity. A token matching nothing yields
 * {@link Optional#empty()}, and the field stays empty.
 */
public final class DocumentTypeVocabulary {

  private final Map<String, String> codeByNormalizedTerm;
  private final Map<String, String> labelByCode;

  private DocumentTypeVocabulary(
      Map<String, String> codeByNormalizedTerm, Map<String, String> labelByCode) {
    this.codeByNormalizedTerm = Map.copyOf(codeByNormalizedTerm);
    this.labelByCode = Map.copyOf(labelByCode);
  }

  public static DocumentTypeVocabulary of(Collection<DocumentTypeVocabularyEntry> entries) {
    Map<String, String> terms = new LinkedHashMap<>();
    Map<String, String> labels = new LinkedHashMap<>();
    for (DocumentTypeVocabularyEntry entry : entries) {
      labels.put(entry.getCode(), entry.getLabel());
      terms.putIfAbsent(normalize(entry.getCode()), entry.getCode());
      terms.putIfAbsent(normalize(entry.getLabel()), entry.getCode());
      for (String synonym : entry.getSynonyms()) {
        terms.putIfAbsent(normalize(synonym), entry.getCode());
      }
    }
    return new DocumentTypeVocabulary(terms, labels);
  }

  public static DocumentTypeVocabulary empty() {
    return new DocumentTypeVocabulary(Map.of(), Map.of());
  }

  /** The code {@code term} denotes exactly, or empty when it denotes none. */
  public Optional<String> resolve(String term) {
    if (term == null || term.isBlank()) {
      return Optional.empty();
    }
    return Optional.ofNullable(codeByNormalizedTerm.get(normalize(term)));
  }

  public boolean containsCode(String code) {
    return code != null && labelByCode.containsKey(code);
  }

  public Optional<String> labelOf(String code) {
    return Optional.ofNullable(labelByCode.get(code));
  }

  /**
   * Lower-cased, umlauts and ß transcribed ({@code Gebührenverzeichnis} and {@code
   * gebuehrenverzeichnis} are the same term), surrounding whitespace removed.
   */
  static String normalize(String term) {
    return term.strip()
        .toLowerCase(Locale.GERMAN)
        .replace("ä", "ae")
        .replace("ö", "oe")
        .replace("ü", "ue")
        .replace("ß", "ss");
  }
}
