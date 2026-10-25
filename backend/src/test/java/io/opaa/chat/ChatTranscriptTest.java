package io.opaa.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import io.opaa.api.types.ChatRole;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ChatTranscriptTest {

  private static final Instant ASKED = Instant.parse("2026-03-05T09:15:00Z");
  private static final Instant ANSWERED = Instant.parse("2026-03-05T09:15:20Z");

  private static ChatConversation chat(String title, ChatTurn... turns) {
    Chat chat =
        new Chat(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), title, true, Set.of());
    return new ChatConversation(chat, List.of(turns), List.of());
  }

  private static ChatTurn question(String text) {
    return new ChatTurn(
        UUID.randomUUID(), UUID.randomUUID(), ChatRole.USER, text, List.of(), ASKED);
  }

  private static ChatTurn answer(String text, ChatSource... sources) {
    return new ChatTurn(
        UUID.randomUUID(), UUID.randomUUID(), ChatRole.ASSISTANT, text, List.of(sources), ANSWERED);
  }

  private static String marker(UUID documentId, int chunk, String fileName) {
    return CitationMarker.render(documentId.toString(), chunk, fileName);
  }

  @Test
  void keepsQuestionsAndAnswersInOrderWithTheirTimestamps() {
    ChatTranscript transcript =
        ChatTranscript.of(
            chat("Frist im Widerspruch", question("Wie lang?"), answer("Ein Monat.")));

    assertThat(transcript.title()).isEqualTo("Frist im Widerspruch");
    assertThat(transcript.entries())
        .extracting(
            ChatTranscript.Entry::role, ChatTranscript.Entry::text, ChatTranscript.Entry::createdAt)
        .containsExactly(
            tuple(ChatRole.USER, "Wie lang?", ASKED),
            tuple(ChatRole.ASSISTANT, "Ein Monat.", ANSWERED));
  }

  @Test
  void aChatWithoutTitleIsNamedLikeInTheChatList() {
    assertThat(ChatTranscript.of(chat(null)).title()).isEqualTo("Unbenannter Chat");
    assertThat(ChatTranscript.of(chat("   ")).title()).isEqualTo("Unbenannter Chat");
  }

  @Test
  void markersBecomeNumberedFootnotesWithFileNameAndLocation() {
    UUID bescheid = UUID.randomUUID();
    UUID merkblatt = UUID.randomUUID();
    ChatSource bescheidSource =
        new ChatSource("Bescheid.pdf", 0.9, 2, true)
            .documentId(bescheid)
            .chunkLocations(List.of(new ChatSourceLocation(3).location("S. 3")));
    ChatSource merkblattSource =
        new ChatSource("Merkblatt.docx", 0.8, 1, true).documentId(merkblatt);
    String text =
        "Die Frist beträgt einen Monat "
            + marker(bescheid, 3, "Bescheid.pdf")
            + marker(bescheid, 3, "Bescheid.pdf")
            + ". Sie beginnt mit Zugang "
            + marker(merkblatt, 0, "Merkblatt.docx")
            + " und gilt auch hier "
            + marker(bescheid, 3, "Bescheid.pdf")
            + ".";

    ChatTranscript.Entry entry =
        ChatTranscript.of(chat("T", answer(text, bescheidSource, merkblattSource)))
            .entries()
            .getFirst();

    assertThat(entry.text())
        .isEqualTo(
            "Die Frist beträgt einen Monat[1]. Sie beginnt mit Zugang[2] und gilt auch hier[1].");
    assertThat(entry.sources()).containsExactly("[1] Bescheid.pdf, S. 3", "[2] Merkblatt.docx");
  }

  @Test
  void malformedMarkersAndBracketsAroundMarkersAreDropped() {
    UUID document = UUID.randomUUID();
    ChatSource source = new ChatSource("Akte.pdf", 0.9, 1, true).documentId(document);
    String text =
        "Zulässig (" + marker(document, 1, "Akte.pdf") + "), aber 【source: kaputt】 nicht hier.";

    ChatTranscript.Entry entry =
        ChatTranscript.of(chat("T", answer(text, source))).entries().getFirst();

    assertThat(entry.text()).isEqualTo("Zulässig[1], aber nicht hier.");
    assertThat(entry.sources()).containsExactly("[1] Akte.pdf");
  }

  @Test
  void citedSourcesWithoutAMarkerFollowWithoutNumberAndUncitedOnesAreLeftOut() {
    UUID cited = UUID.randomUUID();
    ChatSource marked = new ChatSource("Erlass.pdf", 0.9, 1, true).documentId(cited);
    ChatSource citedOnly =
        new ChatSource("Vermerk.pdf", 0.7, 1, true).documentId(UUID.randomUUID());
    ChatSource checked = new ChatSource("Anlage.pdf", 0.5, 1, false).documentId(UUID.randomUUID());
    String text = "Gilt " + marker(cited, 0, "Erlass.pdf") + ".";

    ChatTranscript.Entry entry =
        ChatTranscript.of(chat("T", answer(text, marked, citedOnly, checked))).entries().getFirst();

    assertThat(entry.sources()).containsExactly("[1] Erlass.pdf", "Vermerk.pdf");
  }
}
