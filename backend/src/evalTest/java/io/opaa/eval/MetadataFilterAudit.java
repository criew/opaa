package io.opaa.eval;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Measures whether the core-field filter itself worked, per case and per measurement path (issue
 * #1070) - next to, never instead of, {@link ExpectedStateAudit}: that audit asks "is the case
 * solved", this one asks "did the filter mechanism do what it is for". The two error directions are
 * counted <b>separately</b>, because a combined score would average them away:
 *
 * <ul>
 *   <li><b>"Filter greift nicht"</b> - the case's {@link GoldenCase#confusableDocument()} is still
 *       inside the path's window although the filter should have excluded it.
 *   <li><b>"Filter greift zu stark"</b> - a case marked by {@link GoldenCase#noValueField()} lost
 *       an expected document the Leerwert rule says the filter must keep.
 * </ul>
 *
 * <p>Both directions are bounded by the path's window: a confusable that ranks beyond it, or an
 * expected document the pipeline path's similarity threshold drops, is invisible here. Like the
 * state audit this one reports and never fails the run.
 */
public final class MetadataFilterAudit {

  private MetadataFilterAudit() {}

  /** One case's filter-relevant declaration next to what this path measured for it. */
  public record CaseInput(
      String id,
      boolean filtered,
      String confusableDocument,
      String noValueField,
      List<String> expectedDocuments,
      List<String> rankedFileNames) {}

  /**
   * The audit as it appears in a report. {@code null} for a domain without a single filtered case
   * (comic-characters, city-landmarks) - absent, not "audited and clean".
   *
   * @param filteredCases how many cases were measured with a filter applied.
   * @param casesWithConfusable how many of those name a confusable document, i.e. how many the
   *     "greift nicht" direction could be measured on at all.
   * @param filterNotApplied ids of cases whose confusable document is still in the window.
   * @param noValueCases how many cases are Leerwert-Regel cases.
   * @param filterTooStrong ids of Leerwert-Regel cases whose expected document is missing.
   */
  public record Result(
      int filteredCases,
      int casesWithConfusable,
      List<String> filterNotApplied,
      int noValueCases,
      List<String> filterTooStrong) {

    public boolean clean() {
      return filterNotApplied.isEmpty() && filterTooStrong.isEmpty();
    }
  }

  public static Result fromRawVectorResults(List<RetrievalMetrics.QueryResult> results) {
    return evaluate(results.stream().map(r -> input(r.goldenCase(), r.rankedFileNames())).toList());
  }

  public static Result fromWindowedResults(List<RetrievalMetrics.WindowedQueryResult> results) {
    return evaluate(results.stream().map(r -> input(r.goldenCase(), r.rankedFileNames())).toList());
  }

  static CaseInput input(GoldenCase goldenCase, List<String> rankedFileNames) {
    return new CaseInput(
        goldenCase.id(),
        goldenCase.isFiltered(),
        goldenCase.confusableDocument(),
        goldenCase.noValueField(),
        goldenCase.expectedDocuments(),
        rankedFileNames);
  }

  /** Builds the audit over the filtered cases; {@code null} when no case was filtered. */
  public static Result evaluate(List<CaseInput> inputs) {
    List<CaseInput> filtered = inputs.stream().filter(CaseInput::filtered).toList();
    if (filtered.isEmpty()) {
      return null;
    }
    List<String> filterNotApplied = new ArrayList<>();
    List<String> filterTooStrong = new ArrayList<>();
    int withConfusable = 0;
    int noValueCases = 0;
    for (CaseInput input : filtered) {
      if (input.confusableDocument() != null) {
        withConfusable++;
        if (input.rankedFileNames().contains(input.confusableDocument())) {
          filterNotApplied.add(input.id());
        }
      }
      if (input.noValueField() != null) {
        noValueCases++;
        if (!input.rankedFileNames().containsAll(input.expectedDocuments())) {
          filterTooStrong.add(input.id());
        }
      }
    }
    return new Result(
        filtered.size(),
        withConfusable,
        List.copyOf(filterNotApplied),
        noValueCases,
        List.copyOf(filterTooStrong));
  }

  /** The audit as a block of report text, shared by both paths' writers. */
  public static String renderSummary(Result result) {
    if (result == null) {
      return "Metadatenfilter: kein Fall dieser Domäne trägt einen Filter — keine Aussage über "
          + "die beiden Fehlerrichtungen\n\n";
    }
    StringBuilder sb = new StringBuilder();
    sb.append(
        String.format(
            Locale.ROOT,
            "Metadatenfilter (filter): %d Fälle mit Filter gemessen — Verwechslungspartner "
                + "benannt bei %d, Leerwert-Regel-Fälle %d\n",
            result.filteredCases(),
            result.casesWithConfusable(),
            result.noValueCases()));
    sb.append(
        String.format(
            Locale.ROOT,
            "  Filter greift nicht (Verwechslungspartner im Fenster): %d%s\n",
            result.filterNotApplied().size(),
            result.filterNotApplied().isEmpty() ? "" : " — " + result.filterNotApplied()));
    sb.append(
        String.format(
            Locale.ROOT,
            "  Filter greift zu stark (erwartetes Dokument mit leerem Feld fehlt): %d%s\n",
            result.filterTooStrong().size(),
            result.filterTooStrong().isEmpty() ? "" : " — " + result.filterTooStrong()));
    sb.append('\n');
    return sb.toString();
  }

  /** The same audit as a Markdown block for both baseline-comparison writers. */
  public static String renderMarkdown(Result result) {
    if (result == null) {
      return "";
    }
    StringBuilder sb = new StringBuilder();
    sb.append("\n### Metadatenfilter (`filter`)\n\n");
    sb.append(
        String.format(
            Locale.ROOT,
            "%d Fälle mit Filter gemessen; Verwechslungspartner benannt bei %d, "
                + "Leerwert-Regel-Fälle: %d.\n\n",
            result.filteredCases(),
            result.casesWithConfusable(),
            result.noValueCases()));
    sb.append("| Fehlerrichtung | Fälle | Betroffen |\n|---|---|---|\n");
    sb.append(
        String.format(
            Locale.ROOT,
            "| Filter greift nicht (Verwechslungspartner im Fenster) | %d | %s |\n",
            result.filterNotApplied().size(),
            inlineCodeOrDash(result.filterNotApplied())));
    sb.append(
        String.format(
            Locale.ROOT,
            "| Filter greift zu stark (erwartetes Dokument mit leerem Feld fehlt) | %d | %s |\n",
            result.filterTooStrong().size(),
            inlineCodeOrDash(result.filterTooStrong())));
    sb.append('\n');
    sb.append(
        result.clean()
            ? "**Beide Fehlerrichtungen ohne Befund.**\n"
            : "**Mindestens eine Fehlerrichtung mit Befund** — der Filter selbst, nicht die "
                + "Rangfolge, ist die Stelle, an der zu suchen ist.\n");
    return sb.toString();
  }

  private static String inlineCodeOrDash(List<String> ids) {
    if (ids.isEmpty()) {
      return "—";
    }
    return ids.stream().map(id -> "`" + id + "`").reduce((a, b) -> a + ", " + b).orElse("—");
  }
}
