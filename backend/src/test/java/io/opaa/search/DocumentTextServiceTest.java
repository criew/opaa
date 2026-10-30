package io.opaa.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.opaa.api.types.SystemRole;
import io.opaa.auth.CurrentUser;
import io.opaa.common.NotFoundException;
import io.opaa.common.ValidationException;
import io.opaa.format.chunk.ChunkAnchor;
import io.opaa.knowledge.Document;
import io.opaa.knowledge.SourceType;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The Textfassung without a Spring context: the right is asked before anything is read, the window
 * is bounded, and the overlap chunking puts between two passages is written once.
 */
class DocumentTextServiceTest {

  private static final UUID ORGANIZATION_ID = UUID.randomUUID();
  private static final UUID DOCUMENT_ID = UUID.randomUUID();
  private static final CurrentUser CALLER =
      CurrentUser.of(UUID.randomUUID(), ORGANIZATION_ID, SystemRole.USER, "Leser", "l@b.de");

  private ChunkInspectionService chunks;
  private DocumentTextService service;

  @BeforeEach
  void setUp() {
    chunks = mock(ChunkInspectionService.class);
    service = new DocumentTextService(chunks);
    Document document =
        new Document(
            "vortrag.pptx",
            "/vortrag.pptx",
            "application/vnd.ms-powerpoint",
            10L,
            SourceType.UPLOAD);
    document.setOrganizationId(ORGANIZATION_ID);
    when(chunks.requireReadableText(CALLER, DOCUMENT_ID)).thenReturn(document);
    when(chunks.chunkIndexBounds(ORGANIZATION_ID, DOCUMENT_ID))
        .thenReturn(Optional.of(new ChunkInspectionService.IndexBounds(0, 99)));
  }

  @Test
  void aRefusedReaderReadsNoChunk() {
    when(chunks.requireReadableText(CALLER, DOCUMENT_ID))
        .thenThrow(new NotFoundException("Das Dokument wurde nicht gefunden."));

    assertThatThrownBy(() -> service.read(CALLER, DOCUMENT_ID, null, null, true))
        .isInstanceOf(NotFoundException.class);
    verify(chunks, never()).listChunkWindow(any(), any(), anyInt(), anyInt());
    verify(chunks, never()).listChunkAnchors(any(), any());
  }

  @Test
  void invalidBoundsAreRefusedBeforeTheRightIsAsked() {
    assertThatThrownBy(() -> service.read(CALLER, DOCUMENT_ID, -1, null, false))
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(() -> service.read(CALLER, DOCUMENT_ID, 5, 4, false))
        .isInstanceOf(ValidationException.class);
    verifyNoInteractions(chunks);
  }

  @Test
  void theWindowIsCutToFortyPassagesAndReadsThePredecessorAlong() {
    when(chunks.listChunkWindow(ORGANIZATION_ID, DOCUMENT_ID, 9, 49)).thenReturn(List.of());

    DocumentText text = service.read(CALLER, DOCUMENT_ID, 10, 500, false);

    verify(chunks).listChunkWindow(ORGANIZATION_ID, DOCUMENT_ID, 9, 49);
    assertThat(text.firstChunkIndex()).isZero();
    assertThat(text.lastChunkIndex()).isEqualTo(99);
    verify(chunks, never()).listChunkAnchors(any(), any());
  }

  @Test
  void withoutFromTheWindowStartsAtTheFirstIndexedPassage() {
    when(chunks.chunkIndexBounds(ORGANIZATION_ID, DOCUMENT_ID))
        .thenReturn(Optional.of(new ChunkInspectionService.IndexBounds(3, 99)));
    when(chunks.listChunkWindow(ORGANIZATION_ID, DOCUMENT_ID, 2, 42)).thenReturn(List.of());

    service.read(CALLER, DOCUMENT_ID, null, null, false);

    verify(chunks).listChunkWindow(ORGANIZATION_ID, DOCUMENT_ID, 2, 42);
  }

  @Test
  void theSharedBoundaryIsWrittenOnceAndThePredecessorIsNotReturned() {
    String shared = "Die Frist beträgt vier Wochen.";
    when(chunks.listChunkWindow(eq(ORGANIZATION_ID), eq(DOCUMENT_ID), eq(4), anyInt()))
        .thenReturn(
            List.of(
                chunk(4, "Einleitung. " + shared, Map.of()),
                chunk(
                    5,
                    shared + " Danach entscheidet die Behörde.",
                    Map.of("location", "Folie 2", "anchor", Map.of("slide", 2))),
                // a gap in the indices: nothing is shared across it
                chunk(7, "Danach entscheidet die Behörde erneut.", Map.of())));

    DocumentText text = service.read(CALLER, DOCUMENT_ID, 5, null, false);

    assertThat(text.passages()).extracting(DocumentText.Passage::chunkIndex).containsExactly(5, 7);
    assertThat(text.passages().get(0).text()).isEqualTo("Danach entscheidet die Behörde.");
    assertThat(text.passages().get(0).location()).isEqualTo("Folie 2");
    assertThat(text.passages().get(0).anchor().slide()).isEqualTo(2);
    assertThat(text.passages().get(1).text()).isEqualTo("Danach entscheidet die Behörde erneut.");
    assertThat(text.passages().get(1).anchor()).isNull();
  }

  @Test
  void aCoincidentallySharedCharacterIsNoOverlap() {
    assertThat(DocumentTextService.withoutOverlap("Siehe Anlage (A)", ") folgt"))
        .isEqualTo(") folgt");
    assertThat(DocumentTextService.withoutOverlap(null, "Text")).isEqualTo("Text");
  }

  @Test
  void aPassageThatIsOnlyItsPredecessorsTailKeepsItsText() {
    assertThat(
            DocumentTextService.withoutOverlap("Einleitung. Die Frist endet.", "Die Frist endet."))
        .isEqualTo("Die Frist endet.");
  }

  @Test
  void aDocumentWithoutIndexedPassagesHasNoBoundsAndNoPassages() {
    when(chunks.chunkIndexBounds(ORGANIZATION_ID, DOCUMENT_ID)).thenReturn(Optional.empty());

    DocumentText text = service.read(CALLER, DOCUMENT_ID, null, null, true);

    assertThat(text.firstChunkIndex()).isNull();
    assertThat(text.passages()).isEmpty();
    assertThat(text.sections()).isEmpty();
    verify(chunks, never()).listChunkWindow(any(), any(), anyInt(), anyInt());
  }

  @Test
  void sectionsAreReadOnlyWhenAskedFor() {
    when(chunks.listChunkWindow(any(), any(), anyInt(), anyInt())).thenReturn(List.of());
    when(chunks.listChunkAnchors(ORGANIZATION_ID, DOCUMENT_ID))
        .thenReturn(
            List.of(
                new ChunkInspectionService.IndexedAnchor(
                    0, ChunkAnchor.EMPTY.withSlide(1).withQuotes("Titel", null))));

    DocumentText text = service.read(CALLER, DOCUMENT_ID, null, null, true);

    assertThat(text.sections()).containsExactly(new DocumentOutline.Section(0, "Folie 1", 1));
    assertThat(text.sectionsTruncated()).isFalse();
  }

  private static ChunkInspection chunk(int index, String content, Map<String, Object> metadata) {
    return new ChunkInspection(
        UUID.randomUUID().toString(),
        DOCUMENT_ID,
        "vortrag.pptx",
        UUID.randomUUID(),
        "Vorträge",
        index,
        content,
        metadata);
  }
}
