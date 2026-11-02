package io.opaa.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

/**
 * Small attachments go in whole, large ones as the chunks sharing the question's terms, always in
 * document order and marked as attachment passages under the attachment's id.
 */
class ChatAttachmentPassagesTest {

  private final ChatAttachment first = attachment("Vertrag.pdf");
  private final ChatAttachment second = attachment("Anlage.docx");

  @Test
  void attachmentsBelowTheThresholdGoInWholeInDocumentOrder() {
    var selection =
        ChatAttachmentPassages.select(
            List.of(first, second),
            Map.of(
                first.getId(), List.of(chunk(first, 0, "Erster Teil"), chunk(first, 1, "Zweiter")),
                second.getId(), List.of(chunk(second, 0, "Anlage eins"))),
            "Fasse zusammen",
            1_000,
            100);

    assertThat(selection.excerpts()).isFalse();
    assertThat(selection.passages())
        .extracting(Document::getText)
        .containsExactly("Erster Teil", "Zweiter", "Anlage eins");
    Document passage = selection.passages().getFirst();
    assertThat(passage.getMetadata())
        .containsEntry("document_id", first.getId().toString())
        .containsEntry("chunk_index", 0)
        .containsEntry("file_name", "Vertrag.pdf")
        .containsEntry(ChatAttachmentPassages.ATTACHMENT_METADATA_KEY, true);
  }

  @Test
  void aboveTheThresholdOnlyMatchingChunksGoInStillInDocumentOrder() {
    List<ChatAttachmentChunkStore.Chunk> chunks =
        List.of(
            chunk(first, 0, "Die Kündigungsfrist beträgt drei Monate zum Quartalsende."),
            chunk(first, 1, "Der Mietzins wird monatlich im Voraus gezahlt."),
            chunk(first, 2, "Eine Kündigung bedarf der Schriftform."),
            chunk(first, 3, "Haustiere sind nach Absprache erlaubt."));

    var selection =
        ChatAttachmentPassages.select(
            List.of(first), Map.of(first.getId(), chunks), "Was gilt zur Kündigung?", 50, 200);

    assertThat(selection.excerpts()).isTrue();
    assertThat(selection.passages())
        .extracting(passage -> passage.getMetadata().get("chunk_index"))
        .containsExactly(0, 2);
  }

  @Test
  void theExcerptBudgetCapsThePassagesButNeverLeavesThemEmpty() {
    String longText = "Frist ".repeat(50);
    var selection =
        ChatAttachmentPassages.select(
            List.of(first),
            Map.of(first.getId(), List.of(chunk(first, 0, longText), chunk(first, 1, longText))),
            "Frist",
            10,
            20);

    assertThat(selection.passages()).hasSize(1);
  }

  @Test
  void aQuestionWithoutUsableTermsGetsTheOpeningChunksOfEachAttachmentInTurn() {
    var selection =
        ChatAttachmentPassages.select(
            List.of(first, second),
            Map.of(
                first.getId(), List.of(chunk(first, 0, "aaaa"), chunk(first, 1, "bbbb")),
                second.getId(), List.of(chunk(second, 0, "cccc"), chunk(second, 1, "dddd"))),
            "Was ist das?",
            10,
            8);

    assertThat(selection.passages()).extracting(Document::getText).containsExactly("aaaa", "cccc");
  }

  @Test
  void stopWordsAndShortWordsAreNoTerms() {
    assertThat(ChatAttachmentPassages.terms("Was steht in der Anlage zu Fristen?"))
        .containsExactlyInAnyOrder("anlage", "fristen");
  }

  @Test
  void noChunksMeansNoPassages() {
    assertThat(ChatAttachmentPassages.select(List.of(first), Map.of(), "Frage", 10, 10).passages())
        .isEmpty();
  }

  private static ChatAttachment attachment(String fileName) {
    return new ChatAttachment(
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        fileName,
        "application/pdf",
        1,
        Instant.parse("2026-11-01T10:00:00Z"));
  }

  private static ChatAttachmentChunkStore.Chunk chunk(ChatAttachment of, int index, String text) {
    return new ChatAttachmentChunkStore.Chunk(of.getId(), index, text, Map.of());
  }
}
