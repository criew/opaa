package io.opaa.indexing.pipeline.confluence;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.indexing.ChunkingService;
import io.opaa.indexing.pipeline.DocumentPipelineResult;
import io.opaa.indexing.pipeline.DocumentPipelineSource;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

/**
 * The macro rule set and the structure preservation of {@link ConfluenceDocumentPipeline} (#1137),
 * against a representative storage-format page: headings, a table, lists, a code block, panels with
 * titles, an expand, a status lozenge, a task list, a link, an image - and the dynamic macros that
 * must leave no trace.
 */
class ConfluenceDocumentPipelineTest {

  private final ConfluenceDocumentPipeline pipeline = new ConfluenceDocumentPipeline();

  static final String REPRESENTATIVE_PAGE =
      """
      <ac:structured-macro ac:name="toc"><ac:parameter ac:name="maxLevel">3</ac:parameter></ac:structured-macro>
      <p>Diese Seite beschreibt das <strong>Bauantragsverfahren</strong> der Stadt.</p>
      <h1>Zuständigkeiten</h1>
      <p>Das Bauamt bearbeitet Anträge innerhalb von 14 Tagen.</p>
      <table><tbody>
        <tr><th>Vorgang</th><th>Frist</th></tr>
        <tr><td>Bauantrag</td><td>14 Tage</td></tr>
        <tr><td>Nutzungsänderung</td><td><ac:structured-macro ac:name="status"><ac:parameter ac:name="colour">Green</ac:parameter><ac:parameter ac:name="title">4 Wochen</ac:parameter></ac:structured-macro></td></tr>
      </tbody></table>
      <h2>Unterlagen</h2>
      <ul><li>Lageplan</li><li>Bauzeichnungen<ul><li>Grundriss</li><li>Schnitt</li></ul></li></ul>
      <ac:structured-macro ac:name="info"><ac:parameter ac:name="title">Hinweis</ac:parameter>
        <ac:rich-text-body><p>Die Frist beginnt mit dem Eingang <ac:link><ri:page ri:content-title="Vollständigkeit"/><ac:plain-text-link-body><![CDATA[vollständiger Unterlagen]]></ac:plain-text-link-body></ac:link>.</p></ac:rich-text-body>
      </ac:structured-macro>
      <ac:structured-macro ac:name="expand"><ac:parameter ac:name="title">Rechtsgrundlage</ac:parameter>
        <ac:rich-text-body><p>§ 68 LBO</p></ac:rich-text-body>
      </ac:structured-macro>
      <ac:structured-macro ac:name="code"><ac:parameter ac:name="language">bash</ac:parameter><ac:plain-text-body><![CDATA[curl -X POST /antrag
        -d @bauantrag.json]]></ac:plain-text-body></ac:structured-macro>
      <ac:task-list><ac:task><ac:task-status>complete</ac:task-status><ac:task-body>Formular aktualisieren</ac:task-body></ac:task>
        <ac:task><ac:task-status>incomplete</ac:task-status><ac:task-body>Gebührensatz prüfen</ac:task-body></ac:task></ac:task-list>
      <p><ac:image><ri:attachment ri:filename="plan.png"/></ac:image><ac:emoticon ac:name="smile"/></p>
      <h3>Kontakt</h3>
      <p>Telefon 0123 456</p>
      <ac:structured-macro ac:name="children"><ac:parameter ac:name="all">true</ac:parameter></ac:structured-macro>
      <ac:structured-macro ac:name="jira"><ac:parameter ac:name="jqlQuery">project = BAU</ac:parameter></ac:structured-macro>
      <ac:structured-macro ac:name="excerpt-include"><ac:parameter ac:name="">Andere Seite</ac:parameter></ac:structured-macro>
      """;

  private List<Document> chunk(String body) {
    DocumentPipelineResult result =
        pipeline.run(DocumentPipelineSource.ofExtractedText(body, "Bauantrag"));
    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.CHUNKED);
    return result.chunks();
  }

  @Test
  void claimsNoFormatAndDeclaresItsContextKeys() {
    assertThat(pipeline.handledFormats()).isEmpty();
    assertThat(pipeline.id()).isEqualTo("confluence");
    assertThat(pipeline.version()).isEqualTo((short) 1);
    assertThat(pipeline.passthroughMetadataKeys())
        .containsExactlyInAnyOrder(
            ChunkingService.LOCATION_METADATA_KEY,
            ConfluenceDocumentPipeline.SPACE_METADATA_KEY,
            ConfluenceDocumentPipeline.HIERARCHY_METADATA_KEY);
  }

  @Test
  void cutsAlongH1ToH3AndWritesTheHeadingPathAsFundort() {
    List<Document> chunks = chunk(REPRESENTATIVE_PAGE);

    assertThat(chunks).hasSize(4);
    assertThat(chunks.get(0).getText())
        .startsWith("Diese Seite beschreibt das Bauantragsverfahren");
    assertThat(chunks.get(0).getMetadata())
        .doesNotContainKey(ChunkingService.LOCATION_METADATA_KEY);
    assertThat(chunks.get(1).getMetadata())
        .containsEntry(ChunkingService.LOCATION_METADATA_KEY, "Abschn. Zuständigkeiten");
    assertThat(chunks.get(2).getMetadata())
        .containsEntry(
            ChunkingService.LOCATION_METADATA_KEY, "Abschn. Zuständigkeiten › Unterlagen");
    assertThat(chunks.get(3).getMetadata())
        .containsEntry(
            ChunkingService.LOCATION_METADATA_KEY,
            "Abschn. Zuständigkeiten › Unterlagen › Kontakt");
    assertThat(chunks.get(3).getText())
        .isEqualTo("Zuständigkeiten › Unterlagen › Kontakt\n\nTelefon 0123 456");
  }

  @Test
  void tablesBecomeRowsWithSeparatedCellsAndStatusLozengesKeepTheirTitle() {
    String section = chunk(REPRESENTATIVE_PAGE).get(1).getText();

    assertThat(section)
        .contains("Das Bauamt bearbeitet Anträge innerhalb von 14 Tagen.")
        .contains("Vorgang | Frist")
        .contains("Bauantrag | 14 Tage")
        .contains("Nutzungsänderung | 4 Wochen")
        .doesNotContain("Green");
  }

  @Test
  void listsKeepTheirItemsAndNestingAndTaskListsKeepTheirState() {
    String section = chunk(REPRESENTATIVE_PAGE).get(2).getText();

    assertThat(section)
        .contains("• Lageplan")
        .contains("• Bauzeichnungen")
        .contains("◦ Grundriss")
        .contains("◦ Schnitt")
        .contains("[x] Formular aktualisieren")
        .contains("[ ] Gebührensatz prüfen");
  }

  @Test
  void staticMacrosKeepTitleAndBodyDynamicOnesLeaveNoTrace() {
    List<Document> chunks = chunk(REPRESENTATIVE_PAGE);
    String all = String.join("\n---\n", chunks.stream().map(Document::getText).toList());

    // static: panel/info with title and body, expand with title and body, link text, code
    assertThat(all)
        .contains("Hinweis")
        .contains("Die Frist beginnt mit dem Eingang vollständiger Unterlagen.")
        .contains("Rechtsgrundlage")
        .contains("§ 68 LBO")
        .contains("bash:\ncurl -X POST /antrag\n  -d @bauantrag.json");
    // dynamic: toc, children, jira, excerpt-include - including their parameters
    assertThat(all)
        .doesNotContain("maxLevel")
        .doesNotContain("project = BAU")
        .doesNotContain("Andere Seite")
        .doesNotContain("true");
    // never text: images, emoticons, link targets, macro parameter names
    assertThat(all).doesNotContain("plan.png").doesNotContain("smile").doesNotContain("colour");
  }

  @Test
  void anUnknownMacroKeepsItsRichTextBodyButNotItsParameters() {
    List<Document> chunks =
        chunk(
            "<ac:structured-macro ac:name=\"fancy-box\"><ac:parameter ac:name=\"style\">blue"
                + "</ac:parameter><ac:rich-text-body><p>Vom Autor geschriebener Text.</p>"
                + "</ac:rich-text-body></ac:structured-macro>");

    assertThat(chunks).hasSize(1);
    assertThat(chunks.getFirst().getText()).isEqualTo("Vom Autor geschriebener Text.");
  }

  @Test
  void aPageOfNothingButDynamicMacrosHasNoExtractableText() {
    DocumentPipelineResult result =
        pipeline.run(
            DocumentPipelineSource.ofExtractedText(
                "<ac:structured-macro ac:name=\"toc\"/><ac:structured-macro ac:name=\"children\"/>",
                "Übersicht"));
    assertThat(result.outcome()).isEqualTo(DocumentPipelineResult.Outcome.NO_EXTRACTABLE_TEXT);

    assertThat(pipeline.run(DocumentPipelineSource.ofExtractedText("  ", "Leer")).outcome())
        .isEqualTo(DocumentPipelineResult.Outcome.NO_CONTENT);
  }

  @Test
  void emptyEditorLinesWithNonBreakingSpacesLeaveNoBlock() {
    // Confluence writes <p>&nbsp;</p> for every empty editor line; U+00A0 is whitespace to
    // Jsoup but not to Java's \s - #1192 review, finding 1
    List<Document> chunks =
        chunk("<p>Frist:&nbsp;14 Tage</p><p>&nbsp;</p><p>\u00a0</p><p>Ende.</p>");

    assertThat(chunks).hasSize(1);
    assertThat(chunks.getFirst().getText()).isEqualTo("Frist: 14 Tage\n\nEnde.");
    assertThat(
            pipeline
                .run(DocumentPipelineSource.ofExtractedText("<p>&nbsp;</p><p>\u00a0</p>", "Leer"))
                .outcome())
        .isEqualTo(DocumentPipelineResult.Outcome.NO_EXTRACTABLE_TEXT);
  }

  @Test
  void macrosInsideHeadingsFollowTheSameRulesAsInTheBody() {
    // a status lozenge keeps its title, a Jira macro leaves nothing - also in the heading path
    List<Document> chunks =
        chunk(
            "<h1>Antrag <ac:structured-macro ac:name=\"status\"><ac:parameter ac:name=\"colour\">"
                + "Green</ac:parameter><ac:parameter ac:name=\"title\">Entwurf</ac:parameter>"
                + "</ac:structured-macro></h1><p>Text.</p>"
                + "<h2>Kapitel <ac:structured-macro ac:name=\"jira\"><ac:parameter ac:name=\"key\">"
                + "BAU-1</ac:parameter></ac:structured-macro></h2><p>Mehr.</p>");

    assertThat(chunks).hasSize(2);
    assertThat(chunks.get(0).getMetadata())
        .containsEntry(ChunkingService.LOCATION_METADATA_KEY, "Abschn. Antrag Entwurf");
    assertThat(chunks.get(1).getMetadata())
        .containsEntry(ChunkingService.LOCATION_METADATA_KEY, "Abschn. Antrag Entwurf › Kapitel");
    assertThat(chunks.get(0).getText()).doesNotContain("Green");
    assertThat(chunks.get(1).getText()).doesNotContain("BAU-1");
  }

  @Test
  void aNestedTableIsFlattenedIntoItsOuterCellOnce() {
    List<Document> chunks =
        chunk(
            "<table><tbody><tr><td>Aussen</td><td><table><tbody><tr><td>Innen1</td>"
                + "<td>Innen2</td></tr></tbody></table></td></tr></tbody></table>");

    assertThat(chunks.getFirst().getText()).isEqualTo("Aussen | Innen1 | Innen2");
  }

  @Test
  void cloudEditorElementsAreTakenOnceNotFromTheirFallbackCopy() {
    List<Document> chunks =
        chunk(
            "<ac:adf-extension><ac:adf-node type=\"panel\"><ac:adf-attribute key=\"panel-type\">"
                + "info</ac:adf-attribute><ac:adf-content><p>Panel Inhalt</p></ac:adf-content>"
                + "</ac:adf-node><ac:adf-fallback><ac:structured-macro ac:name=\"panel\">"
                + "<ac:rich-text-body><p>Panel Inhalt</p></ac:rich-text-body></ac:structured-macro>"
                + "</ac:adf-fallback></ac:adf-extension>");

    assertThat(chunks.getFirst().getText()).isEqualTo("Panel Inhalt");
  }

  @Test
  void aCodeMacroWithoutAPlainTextBodyKeepsItsRichText() {
    List<Document> chunks =
        chunk(
            "<ac:structured-macro ac:name=\"code\"><ac:rich-text-body><p>SELECT 1;</p>"
                + "</ac:rich-text-body></ac:structured-macro>");

    assertThat(chunks.getFirst().getText()).isEqualTo("SELECT 1;");
  }

  @Test
  void nestedOrderedListsCarryTheirLevelInTheNumber() {
    List<Document> chunks =
        chunk("<ol><li>Eins</li><li>Zwei<ol><li>Zwei-a</li><li>Zwei-b</li></ol></li></ol>");

    assertThat(chunks.getFirst().getText())
        .isEqualTo("1. Eins\n\n2. Zwei\n\n2.1. Zwei-a\n\n2.2. Zwei-b");
  }

  @Test
  void headingsDeeperThanH3FoldIntoTheSectionText() {
    List<Document> chunks =
        chunk("<h2>Verfahren</h2><p>Einleitung.</p><h4>Schritt 1</h4><p>Antrag stellen.</p>");

    assertThat(chunks).hasSize(1);
    assertThat(chunks.getFirst().getText())
        .isEqualTo("Verfahren\n\nEinleitung.\n\nSchritt 1\n\nAntrag stellen.");
  }
}
