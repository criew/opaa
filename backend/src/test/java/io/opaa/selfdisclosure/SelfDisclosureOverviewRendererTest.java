package io.opaa.selfdisclosure;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.compliance.ComplianceManifestLoader;
import io.opaa.compliance.SelfDisclosureCategory;
import io.opaa.compliance.SelfDisclosureCategory.Field;
import io.opaa.compliance.SelfDisclosureCategory.Section;
import io.opaa.selfdisclosure.SelfDisclosureArchive.CategoryResult;
import io.opaa.selfdisclosure.SelfDisclosureArchive.OverviewRow;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The HTML overview of a self-disclosure: self-contained, escaped, German, with its notices. */
class SelfDisclosureOverviewRendererTest {

  private static final SelfDisclosureManifestFacts FACTS =
      SelfDisclosureManifestFacts.of(ComplianceManifestLoader.load());

  private static final SelfDisclosureCategory NOTIFICATIONS =
      new SelfDisclosureCategory(
          "notifications",
          "Benachrichtigungen",
          List.of(
              new Section(
                  "notifications",
                  "Ihre Benachrichtigungen",
                  List.of(Field.of("title", "Titel", "notifications.title")))));

  private static String render(long records, List<OverviewRow> rows) {
    return render(records, rows, List.of());
  }

  private static String render(long records, List<OverviewRow> rows, List<String> missing) {
    return SelfDisclosureOverviewRenderer.render(
        Instant.parse("2026-10-23T12:00:00Z"),
        ZoneId.of("Europe/Berlin"),
        List.of(
            new CategoryResult(
                NOTIFICATIONS,
                records,
                Map.of("notifications", records),
                rows,
                records == 0 ? null : "notifications.json")),
        FACTS,
        missing);
  }

  @Test
  void namesTheCategoriesThisVersionDoesNotDeliverYetAndOnlyThen() {
    assertThat(render(1, List.of(), List.of("Chats", "Benutzerkonten")))
        .contains("Diese Version enthält noch nicht alle Kategorien")
        .contains("Noch nicht enthalten: Chats, Benutzerkonten.");
    assertThat(render(1, List.of())).doesNotContain("noch nicht alle Kategorien");
  }

  @Test
  void isOneGermanPageWithoutScriptOrExternalResourceAndCarriesTheNotices() {
    String html = render(1, List.of(new OverviewRow("notifications", List.of("Hallo"))));

    assertThat(html)
        .startsWith("<!DOCTYPE html>")
        .contains("<html lang=\"de\">")
        .contains(SelfDisclosureOverviewRenderer.NOTICE)
        .contains("Erstellt am 23.10.2026 um 14:00 Uhr")
        .contains("<th scope=\"col\">Titel</th>")
        .contains("Art. 15 DSGVO")
        .contains("nacheinander gelesen")
        .contains("Datenschutzbeauftragte")
        .contains("Sicherungen")
        .doesNotContainIgnoringCase("<script")
        .doesNotContain("src=")
        .doesNotContain("href=")
        .doesNotContain("http://")
        .doesNotContain("https://");
    assertThat(html.indexOf("<h1>")).isLessThan(html.indexOf("<h2>"));
  }

  @Test
  void escapesEveryValue() {
    String html =
        render(
            1,
            List.of(
                new OverviewRow(
                    "notifications", List.of("<script>alert('x')</script> & \"zitiert\""))));

    assertThat(html)
        .contains("&lt;script&gt;alert(&#39;x&#39;)&lt;/script&gt; &amp; &quot;zitiert&quot;")
        .doesNotContain("<script>");
  }

  @Test
  void pointsToTheJsonFileWhenTheOverviewShowsOnlyPartAndNamesWhollyExcludedData() {
    String html = render(250, List.of(new OverviewRow("notifications", List.of("a"))));

    assertThat(html)
        .contains("Gezeigt werden 1 von 250 Datensätzen")
        .contains("notifications.json")
        .contains("Warteschlange ausgehender E-Mails")
        .contains("Technisches Anwendungslog");
  }
}
