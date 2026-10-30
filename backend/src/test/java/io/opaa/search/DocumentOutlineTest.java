package io.opaa.search;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.format.chunk.ChunkAnchor;
import java.util.List;
import org.junit.jupiter.api.Test;

class DocumentOutlineTest {

  @Test
  void aSlideEntersOnceAtItsFirstPassage() {
    DocumentOutline.Outline outline =
        DocumentOutline.of(
            List.of(
                anchored(0, ChunkAnchor.EMPTY.withSlide(1)),
                anchored(1, ChunkAnchor.EMPTY.withSlide(1)),
                anchored(2, ChunkAnchor.EMPTY),
                anchored(3, ChunkAnchor.EMPTY.withSlide(2))),
            100);

    assertThat(outline.sections())
        .containsExactly(
            new DocumentOutline.Section(0, "Folie 1", 1),
            new DocumentOutline.Section(3, "Folie 2", 1));
    assertThat(outline.truncated()).isFalse();
  }

  @Test
  void aHeadingPathEntersEveryHeadingThatChanged() {
    DocumentOutline.Outline outline =
        DocumentOutline.of(
            List.of(
                anchored(0, ChunkAnchor.EMPTY.withPath(List.of("1 Ziel", "1.1 Umfang"))),
                anchored(1, ChunkAnchor.EMPTY.withPath(List.of("1 Ziel", "1.2 Fristen"))),
                anchored(2, ChunkAnchor.EMPTY.withPath(List.of("1 Ziel"))),
                anchored(3, ChunkAnchor.EMPTY.withPath(List.of("2 Verfahren")))),
            100);

    assertThat(outline.sections())
        .containsExactly(
            new DocumentOutline.Section(0, "1 Ziel", 1),
            new DocumentOutline.Section(0, "1.1 Umfang", 2),
            new DocumentOutline.Section(1, "1.2 Fristen", 2),
            new DocumentOutline.Section(3, "2 Verfahren", 1));
  }

  @Test
  void sheetsMessagesAndPagesNameTheirSection() {
    DocumentOutline.Outline outline =
        DocumentOutline.of(
            List.of(
                anchored(0, ChunkAnchor.EMPTY.withRows("Haushalt", 1, 40)),
                anchored(1, ChunkAnchor.EMPTY.withMessage(2)),
                anchored(2, ChunkAnchor.EMPTY.withPages(5, 6))),
            100);

    assertThat(outline.sections())
        .extracting(DocumentOutline.Section::label)
        .containsExactly("Haushalt", "Nachricht 2", "Seite 5");
  }

  @Test
  void theLimitCutsTheOutlineAndMarksIt() {
    DocumentOutline.Outline outline =
        DocumentOutline.of(
            List.of(
                anchored(0, ChunkAnchor.EMPTY.withSlide(1)),
                anchored(1, ChunkAnchor.EMPTY.withSlide(2)),
                anchored(2, ChunkAnchor.EMPTY.withSlide(3))),
            2);

    assertThat(outline.sections()).hasSize(2);
    assertThat(outline.truncated()).isTrue();
  }

  private static ChunkInspectionService.IndexedAnchor anchored(int index, ChunkAnchor anchor) {
    return new ChunkInspectionService.IndexedAnchor(index, anchor);
  }
}
