package io.opaa.format.file.pdf;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.format.DocumentFormatResult;
import io.opaa.format.DocumentFormatSource;
import io.opaa.format.chunk.ChunkMetadataKeys;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageXYZDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.document.Document;

/**
 * A synthetic Haushaltsplan whose outline reads "Kapitel › Ausgaben › Titelgruppe › Titel", with a
 * single line under every Titel: the Titel of one Titelgruppe are indexed together, not one tiny
 * chunk each.
 */
class PdfDocumentFormatSmallSectionTest {

  private static final String KAPITEL = "Kapitel 0801 Finanzaemter";
  private static final String AUSGABEN = "Ausgaben";
  private static final List<String> GRUPPEN = List.of("51", "52");
  private static final int TITEL_JE_GRUPPE = 10;

  @TempDir Path tempDir;

  @Test
  void theTitelOfOneTitelgruppeShareAChunkUnderTheTitelgruppesHeadingPath() throws IOException {
    // regression guard for #2331: each Titel became a chunk of its own, most below 200 chars.
    Path file = tempDir.resolve("haushaltsplan-kapitel-finanzaemter.pdf");
    writeHaushaltsplan(file);

    DocumentFormatResult result =
        new PdfDocumentFormat()
            .run(DocumentFormatSource.ofFile(file, file.getFileName().toString(), ".pdf"));

    assertThat(result.outcome()).isEqualTo(DocumentFormatResult.Outcome.CHUNKED);
    List<Document> chunks = result.chunks();
    assertThat(chunks).hasSize(GRUPPEN.size());
    for (int g = 0; g < GRUPPEN.size(); g++) {
      String gruppe = GRUPPEN.get(g);
      String path = KAPITEL + " › " + AUSGABEN + " › " + titelgruppe(gruppe);
      Document chunk = chunks.get(g);
      assertThat(chunk.getMetadata().get(ChunkMetadataKeys.LOCATION_METADATA_KEY))
          .isEqualTo("Abschn. " + path);
      assertThat(chunk.getText()).startsWith(path + "\n\n");
      for (int n = 1; n <= TITEL_JE_GRUPPE; n++) {
        assertThat(chunk.getText()).contains(titel(gruppe, n) + "\n\n" + zweck(gruppe, n));
      }
    }
  }

  private static void writeHaushaltsplan(Path file) throws IOException {
    try (PDDocument doc = new PDDocument()) {
      PDDocumentOutline outline = new PDDocumentOutline();
      doc.getDocumentCatalog().setDocumentOutline(outline);
      List<String> firstLines = new ArrayList<>(List.of(KAPITEL, AUSGABEN));
      PDOutlineItem kapitel = null;
      PDOutlineItem ausgaben = null;
      for (String gruppe : GRUPPEN) {
        List<String> lines = new ArrayList<>(firstLines);
        firstLines = new ArrayList<>();
        lines.add(titelgruppe(gruppe));
        for (int n = 1; n <= TITEL_JE_GRUPPE; n++) {
          lines.add(titel(gruppe, n));
          lines.add(zweck(gruppe, n));
        }
        PDPage page = addPage(doc, lines);
        if (kapitel == null) {
          kapitel = outlineItem(KAPITEL, page);
          outline.addLast(kapitel);
          ausgaben = outlineItem(AUSGABEN, page);
          kapitel.addLast(ausgaben);
        }
        PDOutlineItem titelgruppe = outlineItem(titelgruppe(gruppe), page);
        ausgaben.addLast(titelgruppe);
        for (int n = 1; n <= TITEL_JE_GRUPPE; n++) {
          titelgruppe.addLast(outlineItem(titel(gruppe, n), page));
        }
      }
      doc.save(file.toFile());
    }
  }

  private static String titelgruppe(String gruppe) {
    return "Titelgruppe " + gruppe + " Steuerverwaltung";
  }

  private static String titel(String gruppe, int n) {
    return String.format("Titel 5%s %02d", gruppe, n);
  }

  private static String zweck(String gruppe, int n) {
    return String.format("Sachverstaendige Gruppe %s Nr. %d: %d.000 Euro", gruppe, n, n * 37);
  }

  private static PDPage addPage(PDDocument doc, List<String> lines) throws IOException {
    PDPage page = new PDPage(PDRectangle.A4);
    doc.addPage(page);
    try (PDPageContentStream stream = new PDPageContentStream(doc, page)) {
      stream.beginText();
      stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 11);
      stream.newLineAtOffset(40, PDRectangle.A4.getHeight() - 50);
      for (String line : lines) {
        stream.showText(line);
        stream.newLineAtOffset(0, -14);
      }
      stream.endText();
    }
    return page;
  }

  private static PDOutlineItem outlineItem(String title, PDPage page) {
    PDOutlineItem item = new PDOutlineItem();
    item.setTitle(title);
    PDPageXYZDestination destination = new PDPageXYZDestination();
    destination.setPage(page);
    item.setDestination(destination);
    return item;
  }
}
