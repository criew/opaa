package io.opaa.selfdisclosure;

import io.opaa.compliance.SelfDisclosureCategory.Field;
import io.opaa.compliance.SelfDisclosureCategory.Section;
import io.opaa.selfdisclosure.SelfDisclosureArchive.CategoryResult;
import io.opaa.selfdisclosure.SelfDisclosureArchive.OverviewRow;
import io.opaa.selfdisclosure.SelfDisclosureManifestFacts.CategoryFacts;
import io.opaa.selfdisclosure.SelfDisclosureManifestFacts.Exclusion;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Renders {@code index.html}: one self-contained page without script and external resources, {@code
 * lang="de"}, black on white, every value HTML-escaped. Generic over the categories: titles and
 * field labels come from the exporters, explanations and exclusions from the fact manifest.
 */
final class SelfDisclosureOverviewRenderer {

  /** The notice on top of the overview, verbatim from the specification. */
  static final String NOTICE =
      "Diese Auskunft ist nur für Sie. Niemand darf verlangen, dass Sie sie vorlegen oder"
          + " weitergeben.";

  private static final DateTimeFormatter GENERATED_AT =
      DateTimeFormatter.ofPattern("dd.MM.yyyy 'um' HH:mm 'Uhr'");

  private static final String STYLE =
      """
      body{font-family:sans-serif;color:#1a1a1a;background:#ffffff;margin:2rem;line-height:1.5}
      .notice{border:2px solid #1a1a1a;padding:1rem;font-weight:bold}
      table{border-collapse:collapse;margin:0.5rem 0 1.5rem}
      th,td{border:1px solid #595959;padding:0.25rem 0.5rem;text-align:left;vertical-align:top}
      th{background:#e8e8e8}
      td{white-space:pre-wrap;word-break:break-word}
      """;

  private SelfDisclosureOverviewRenderer() {}

  static String render(
      Instant generatedAt,
      ZoneId zone,
      List<CategoryResult> results,
      SelfDisclosureManifestFacts facts,
      List<String> notYetContained) {
    StringBuilder html = new StringBuilder(64 * 1024);
    html.append("<!DOCTYPE html>\n<html lang=\"de\">\n<head>\n<meta charset=\"utf-8\">\n")
        .append("<title>Selbstauskunft</title>\n<style>")
        .append(STYLE)
        .append("</style>\n</head>\n<body>\n<main>\n<h1>Selbstauskunft</h1>\n");
    html.append("<p class=\"notice\">").append(escape(NOTICE)).append("</p>\n");
    html.append("<p>Erstellt am ")
        .append(escape(GENERATED_AT.format(generatedAt.atZone(zone))))
        .append(" (Zeitzone ")
        .append(escape(zone.getId()))
        .append("). Die Dateien <code>&lt;Kategorie&gt;.json</code> enthalten alle Datensätze")
        .append(" maschinenlesbar, Zeitpunkte dort in UTC.</p>\n");
    if (!notYetContained.isEmpty()) {
      html.append(
              "<p><strong>Diese Version enthält noch nicht alle Kategorien; weitere folgen mit"
                  + " einer späteren Version.</strong> Noch nicht enthalten: ")
          .append(escape(String.join(", ", notYetContained)))
          .append(".</p>\n");
    }

    html.append("<h2>Ihre Daten</h2>\n");
    for (CategoryResult result : results) {
      renderCategory(html, result, facts.categories().get(result.category().id()));
    }

    html.append("<h2>Nicht enthaltene Daten</h2>\n<ul>\n");
    for (Exclusion exclusion : facts.whollyExcluded()) {
      html.append("<li><strong>")
          .append(escape(exclusion.what()))
          .append(":</strong> ")
          .append(escape(exclusion.reason()))
          .append("</li>\n");
    }
    html.append(
            "<li><strong>Sicherungen:</strong> Kopien derselben Bestände beim Betrieb, nicht"
                + " über die Anwendung lesbar.</li>\n")
        .append(
            "<li><strong>Geheimnisse</strong> wie Passwort-Hashes, Tokens und verschlüsselte"
                + " Zugangsdaten: ohne Auskunftswert, ihre Ausgabe wäre ein Sicherheitsrisiko.</li>\n")
        .append("</ul>\n");
    html.append("<h2>Hinweise</h2>\n")
        .append(
            "<p>Anlassbezogene Klärungen über Ihre Person und die zugehörigen Protokolleinträge"
                + " sind nicht enthalten; es kann sie geben. Auskunft darüber gibt die oder der"
                + " Datenschutzbeauftragte Ihrer Behörde.</p>\n")
        .append(
            "<p>Diese Auskunft ersetzt nicht die förmliche Bearbeitung eines Auskunftsantrags"
                + " nach Art. 15 DSGVO durch Ihre Behörde. Einen solchen Antrag können Sie"
                + " jederzeit stellen.</p>\n")
        .append(
            "<p>Die Kategorien werden nacheinander gelesen, nicht zu einem einzigen Zeitpunkt."
                + " Ändert sich während der Erstellung etwas, kann eine Kategorie den älteren und"
                + " eine spätere den neueren Stand zeigen.</p>\n");
    html.append("</main>\n</body>\n</html>\n");
    return html.toString();
  }

  private static void renderCategory(
      StringBuilder html, CategoryResult result, CategoryFacts facts) {
    html.append("<section>\n<h3>").append(escape(result.category().title())).append("</h3>\n");
    if (facts != null) {
      html.append("<p>").append(escape(facts.purpose())).append("</p>\n");
    }
    html.append("<p>Datensätze: ").append(result.records());
    if (result.fileName() != null) {
      html.append(", vollständig in <code>").append(escape(result.fileName())).append("</code>");
    }
    html.append(".</p>\n");
    if (facts != null && !facts.exclusions().isEmpty()) {
      html.append("<p>Nicht enthalten:</p>\n<ul>\n");
      for (Exclusion exclusion : facts.exclusions()) {
        html.append("<li><code>")
            .append(escape(exclusion.what()))
            .append("</code>: ")
            .append(escape(exclusion.reason()))
            .append("</li>\n");
      }
      html.append("</ul>\n");
    }
    for (Section section : result.category().sections()) {
      List<OverviewRow> rows =
          result.overview().stream().filter(row -> row.sectionKey().equals(section.key())).toList();
      long total = result.sectionRecords().getOrDefault(section.key(), 0L);
      if (total == 0) {
        continue;
      }
      html.append("<h4>").append(escape(section.title())).append("</h4>\n");
      if (rows.size() < total) {
        html.append("<p>Gezeigt werden ")
            .append(rows.size())
            .append(" von ")
            .append(total)
            .append(" Datensätzen; alle stehen in <code>")
            .append(escape(result.fileName()))
            .append("</code>.</p>\n");
      }
      if (rows.isEmpty()) {
        continue;
      }
      html.append("<table>\n<thead><tr>");
      for (Field field : section.fields()) {
        html.append("<th scope=\"col\">").append(escape(field.label())).append("</th>");
      }
      html.append("</tr></thead>\n<tbody>\n");
      for (OverviewRow row : rows) {
        html.append("<tr>");
        for (String cell : row.cells()) {
          html.append("<td>").append(escape(cell)).append("</td>");
        }
        html.append("</tr>\n");
      }
      html.append("</tbody>\n</table>\n");
    }
    html.append("</section>\n");
  }

  static String escape(String text) {
    if (text == null) {
      return "";
    }
    StringBuilder escaped = new StringBuilder(text.length() + 16);
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      switch (c) {
        case '&' -> escaped.append("&amp;");
        case '<' -> escaped.append("&lt;");
        case '>' -> escaped.append("&gt;");
        case '"' -> escaped.append("&quot;");
        case '\'' -> escaped.append("&#39;");
        default -> escaped.append(c);
      }
    }
    return escaped.toString();
  }
}
