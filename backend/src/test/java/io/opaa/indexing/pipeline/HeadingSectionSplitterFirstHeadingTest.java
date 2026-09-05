package io.opaa.indexing.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The first-heading reading DOCX, ODT and Markdown share (ADR-0024) - the heading may sit anywhere
 * in the document, which is what distinguishes it from the title line.
 */
class HeadingSectionSplitterFirstHeadingTest {

  @Test
  void theFirstLevelOneHeadingIsReturnedEvenBelowBodyText() {
    List<HeadingSectionSplitter.Event> events =
        List.of(
            new HeadingSectionSplitter.Paragraph("Ein Vorspann vor jeder Ueberschrift."),
            new HeadingSectionSplitter.Heading(2, "Ein Unterabschnitt"),
            new HeadingSectionSplitter.Heading(1, "Satzung ueber die Erhebung von Gebuehren"),
            new HeadingSectionSplitter.Heading(1, "Zweite Ueberschrift"));

    assertThat(HeadingSectionSplitter.firstTopLevelHeading(events))
        .isEqualTo("Satzung ueber die Erhebung von Gebuehren");
  }

  @Test
  void aBlankHeadingIsSkipped() {
    List<HeadingSectionSplitter.Event> events =
        List.of(
            new HeadingSectionSplitter.Heading(1, "   "),
            new HeadingSectionSplitter.Heading(1, "Gebuehrenordnung"));

    assertThat(HeadingSectionSplitter.firstTopLevelHeading(events)).isEqualTo("Gebuehrenordnung");
  }

  @Test
  void aDocumentWithoutALevelOneHeadingHasNoFirstHeading() {
    List<HeadingSectionSplitter.Event> events =
        List.of(
            new HeadingSectionSplitter.Heading(2, "Nur ein Unterabschnitt"),
            new HeadingSectionSplitter.Paragraph("Text"));

    assertThat(HeadingSectionSplitter.firstTopLevelHeading(events)).isNull();
    assertThat(HeadingSectionSplitter.firstTopLevelHeading(List.of())).isNull();
  }
}
