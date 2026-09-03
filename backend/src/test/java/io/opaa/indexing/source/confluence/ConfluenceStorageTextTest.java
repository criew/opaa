package io.opaa.indexing.source.confluence;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ConfluenceStorageTextTest {

  @Test
  void reducesHeadingsParagraphsAndListsToLinesInDocumentOrder() {
    String text =
        ConfluenceStorageText.toPlainText(
            "<h1>Zuständigkeiten</h1><p>Das Bauamt bearbeitet <strong>Anträge</strong> innerhalb"
                + " von 14 Tagen.</p><ul><li>Bauantrag</li><li>Nutzungsänderung</li></ul>");

    assertThat(text)
        .isEqualTo(
            "Zuständigkeiten\nDas Bauamt bearbeitet Anträge innerhalb von 14 Tagen.\nBauantrag\n"
                + "Nutzungsänderung");
  }

  @Test
  void keepsTableRowsAsLinesWithSeparatedCells() {
    String text =
        ConfluenceStorageText.toPlainText(
            "<table><tbody><tr><th>Vorgang</th><th>Frist</th></tr>"
                + "<tr><td>Bauantrag</td><td>14 Tage</td></tr></tbody></table>");

    assertThat(text).isEqualTo("Vorgang | Frist |\nBauantrag | 14 Tage |");
  }

  @Test
  void dropsMacroParametersAndResourceIdentifiersButKeepsMacroBodies() {
    String text =
        ConfluenceStorageText.toPlainText(
            "<ac:structured-macro ac:name=\"info\"><ac:parameter ac:name=\"title\">Hinweis"
                + "</ac:parameter><ac:rich-text-body><p>Die Frist beginnt mit dem Eingang.</p>"
                + "</ac:rich-text-body></ac:structured-macro>"
                + "<p><ac:link><ri:page ri:content-title=\"Kapitel 2\"/>"
                + "<ac:plain-text-link-body><![CDATA[weiter]]></ac:plain-text-link-body></ac:link>"
                + " Siehe auch <ac:image><ri:attachment ri:filename=\"plan.pdf\"/></ac:image></p>");

    assertThat(text).isEqualTo("Die Frist beginnt mit dem Eingang.\nSiehe auch");
  }

  @Test
  void anEmptyOrBlankBodyYieldsNoText() {
    assertThat(ConfluenceStorageText.toPlainText(null)).isEmpty();
    assertThat(ConfluenceStorageText.toPlainText("   ")).isEmpty();
    assertThat(ConfluenceStorageText.toPlainText("<p> </p><ac:structured-macro ac:name=\"toc\"/>"))
        .isEmpty();
  }
}
