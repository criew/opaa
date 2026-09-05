package io.opaa.indexing.metadata;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The controlled Dokumentart vocabulary as an in-memory snapshot (metadata-schema.md, Teil II (a)).
 * {@link #resolve} maps a term to a code only on an exact, case- and umlaut-insensitive match of
 * the code, the label or a seeded synonym - never on similarity. {@link #resolveToken} adds the
 * seeded Kompositum rule for a token read out of a file name or a Dokumentkopf, and is therefore
 * <b>not</b> used for a declared or manually entered value, which must name a vocabulary term
 * itself. A term matching nothing yields {@link Optional#empty()}, and the field stays empty.
 */
public final class DocumentTypeVocabulary {

  /** One seeded Kompositum ending, normalized, with the code it denotes. */
  private record SuffixRule(String suffix, int minPrefixLength, String code) {}

  private final Map<String, String> codeByNormalizedTerm;
  private final Map<String, String> labelByCode;
  private final List<SuffixRule> suffixRules;
  private final Map<String, Set<String>> suffixExclusionsByCode;

  private DocumentTypeVocabulary(
      Map<String, String> codeByNormalizedTerm,
      Map<String, String> labelByCode,
      List<SuffixRule> suffixRules,
      Map<String, Set<String>> suffixExclusionsByCode) {
    this.codeByNormalizedTerm = Map.copyOf(codeByNormalizedTerm);
    this.labelByCode = Map.copyOf(labelByCode);
    this.suffixRules = List.copyOf(suffixRules);
    this.suffixExclusionsByCode = Map.copyOf(suffixExclusionsByCode);
  }

  public static DocumentTypeVocabulary of(Collection<DocumentTypeVocabularyEntry> entries) {
    Map<String, String> terms = new LinkedHashMap<>();
    Map<String, String> labels = new LinkedHashMap<>();
    List<SuffixRule> rules = new ArrayList<>();
    Map<String, Set<String>> exclusions = new LinkedHashMap<>();
    for (DocumentTypeVocabularyEntry entry : entries) {
      labels.put(entry.getCode(), entry.getLabel());
      terms.putIfAbsent(normalize(entry.getCode()), entry.getCode());
      terms.putIfAbsent(normalize(entry.getLabel()), entry.getCode());
      for (String synonym : entry.getSynonyms()) {
        terms.putIfAbsent(normalize(synonym), entry.getCode());
      }
      for (DocumentTypeSuffix suffix : entry.getSuffixes()) {
        rules.add(
            new SuffixRule(
                normalize(suffix.getSuffix()), suffix.getMinPrefixLength(), entry.getCode()));
      }
      Set<String> excluded = new LinkedHashSet<>();
      for (String token : entry.getSuffixExclusions()) {
        excluded.add(normalize(token));
      }
      exclusions.put(entry.getCode(), Set.copyOf(excluded));
    }
    return new DocumentTypeVocabulary(terms, labels, rules, exclusions);
  }

  public static DocumentTypeVocabulary empty() {
    return new DocumentTypeVocabulary(Map.of(), Map.of(), List.of(), Map.of());
  }

  /** The code {@code term} denotes exactly, or empty when it denotes none. */
  public Optional<String> resolve(String term) {
    if (term == null || term.isBlank()) {
      return Optional.empty();
    }
    return Optional.ofNullable(codeByNormalizedTerm.get(normalize(term)));
  }

  /**
   * The code a single token out of a file name or a Dokumentkopf denotes: an exact vocabulary term,
   * or - failing that - the Dokumentart whose seeded ending the token carries as a Kompositum
   * ({@code verwaltungsgebuehrensatzung} is a Satzung). Empty when no ending fits, when the prefix
   * in front of the ending is too short ({@code anordnung}), when the token is seeded as an
   * exclusion of that Dokumentart, or when two different Dokumentarten would fit at once.
   */
  public Optional<String> resolveToken(String token) {
    Optional<String> exact = resolve(token);
    if (exact.isPresent()) {
      return exact;
    }
    if (token == null || token.isBlank()) {
      return Optional.empty();
    }
    String normalized = normalize(token);
    String match = null;
    for (SuffixRule rule : suffixRules) {
      if (!normalized.endsWith(rule.suffix())
          || normalized.length() - rule.suffix().length() < rule.minPrefixLength()
          || suffixExclusionsByCode.getOrDefault(rule.code(), Set.of()).contains(normalized)) {
        continue;
      }
      if (match != null && !match.equals(rule.code())) {
        return Optional.empty();
      }
      match = rule.code();
    }
    return Optional.ofNullable(match);
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
