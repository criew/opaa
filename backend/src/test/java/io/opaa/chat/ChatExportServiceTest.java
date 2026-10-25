package io.opaa.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.opaa.api.types.ChatNoteItemKind;
import io.opaa.api.types.ChatRole;
import io.opaa.api.types.ColorScheme;
import io.opaa.branding.BrandingSettingsService;
import io.opaa.branding.EffectiveBranding;
import io.opaa.common.NotFoundException;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ChatExportServiceTest {

  private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
  private static final Instant NOW = Instant.parse("2026-03-06T07:00:00Z");

  private final ChatService chatService = mock(ChatService.class);
  private final BrandingSettingsService branding = mock(BrandingSettingsService.class);
  private final ChatExportService exportService =
      new ChatExportService(chatService, branding, Clock.fixed(NOW, BERLIN));

  private final UUID userId = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    when(branding.currentBranding())
        .thenReturn(
            new EffectiveBranding(
                "Amtsassistent",
                "Claim",
                "Antworten werden\nvon einer KI erzeugt.",
                "#004b87",
                ColorScheme.LIGHT,
                Map.of()));
  }

  private ChatConversation conversation(String title, List<ChatTurn> turns) {
    Chat chat = new Chat(UUID.randomUUID(), userId, UUID.randomUUID(), title, true, Set.of());
    return new ChatConversation(chat, turns, List.of());
  }

  private static String text(byte[] pdf) throws IOException {
    try (PDDocument document = Loader.loadPDF(pdf)) {
      return new PDFTextStripper().getText(document);
    }
  }

  @Test
  void thePdfCarriesTitleQuestionsAnswersSourcesTimestampsAndTheAiNotice() throws IOException {
    UUID document = UUID.randomUUID();
    ChatSource source =
        new ChatSource("Bescheid.pdf", 0.9, 1, true)
            .documentId(document)
            .chunkLocations(List.of(new ChatSourceLocation(2).location("S. 4")));
    List<ChatTurn> turns =
        List.of(
            new ChatTurn(
                UUID.randomUUID(),
                UUID.randomUUID(),
                ChatRole.USER,
                "Wie lange läuft die Widerspruchsfrist?",
                List.of(),
                Instant.parse("2026-03-05T09:15:00Z")),
            new ChatTurn(
                UUID.randomUUID(),
                UUID.randomUUID(),
                ChatRole.ASSISTANT,
                "## Ergebnis\n\nDie Frist beträgt **einen Monat** "
                    + CitationMarker.render(document.toString(), 2, "Bescheid.pdf")
                    + ".\n\n- Zugang zählt",
                List.of(source),
                Instant.parse("2026-03-05T09:15:30Z")));
    UUID chatId = UUID.randomUUID();
    when(chatService.getChat(chatId, userId))
        .thenReturn(conversation("Widerspruchsfrist Bauamt", turns));

    ChatPdf pdf = exportService.exportPdf(chatId, userId);

    assertThat(pdf.fileName()).isEqualTo("Widerspruchsfrist Bauamt.pdf");
    assertThat(new String(pdf.content(), 0, 5, java.nio.charset.StandardCharsets.US_ASCII))
        .isEqualTo("%PDF-");
    assertThat(text(pdf.content()))
        .contains("Widerspruchsfrist Bauamt")
        .contains("exportiert am 06.03.2026, 08:00 Uhr")
        .contains("Frage · 05.03.2026, 10:15 Uhr")
        .contains("Wie lange läuft die Widerspruchsfrist?")
        .contains("Antwort · 05.03.2026, 10:15 Uhr")
        .contains("Ergebnis")
        .contains("Die Frist beträgt einen Monat[1].")
        .contains("• Zugang zählt")
        .contains("Quellen")
        .contains("[1] Bescheid.pdf, S. 4")
        .contains("Antworten werden von einer KI erzeugt. – Amtsassistent, 06.03.2026")
        .contains("Seite 1 von 1")
        .doesNotContain("【")
        .doesNotContain("**")
        .doesNotContain("##");
  }

  @Test
  void charactersOutsideTheFontsArePrintedAsQuestionMarksInsteadOfFailing() throws IOException {
    UUID chatId = UUID.randomUUID();
    List<ChatTurn> turns =
        List.of(
            new ChatTurn(
                UUID.randomUUID(),
                UUID.randomUUID(),
                ChatRole.USER,
                "Was heißt 契約 auf Deutsch?",
                List.of(),
                Instant.parse("2026-03-05T09:15:00Z")));
    when(chatService.getChat(chatId, userId)).thenReturn(conversation("Wörter", turns));

    assertThat(text(exportService.exportPdf(chatId, userId).content()))
        .contains("Was heißt ?? auf Deutsch?");
  }

  @Test
  void aLongChatContinuesOnFurtherPages() throws IOException {
    UUID chatId = UUID.randomUUID();
    String longAnswer = "Absatz mit etwas Text, der umbrochen werden muss. ".repeat(400);
    List<ChatTurn> turns =
        List.of(
            new ChatTurn(
                UUID.randomUUID(),
                UUID.randomUUID(),
                ChatRole.ASSISTANT,
                longAnswer,
                List.of(),
                Instant.parse("2026-03-05T09:15:00Z")));
    when(chatService.getChat(chatId, userId)).thenReturn(conversation("Lang", turns));

    byte[] pdf = exportService.exportPdf(chatId, userId).content();

    try (PDDocument document = Loader.loadPDF(pdf)) {
      assertThat(document.getNumberOfPages()).isGreaterThan(1);
    }
    assertThat(text(pdf)).contains("Seite 2 von");
  }

  @Test
  void anEmptyChatSaysSo() throws IOException {
    UUID chatId = UUID.randomUUID();
    when(chatService.getChat(chatId, userId)).thenReturn(conversation(null, List.of()));

    ChatPdf pdf = exportService.exportPdf(chatId, userId);

    assertThat(pdf.fileName()).isEqualTo("Unbenannter Chat.pdf");
    assertThat(text(pdf.content())).contains("Dieser Chat enthält noch keine Nachrichten.");
  }

  @Test
  void aChatTheCallerMayNotReadIsNotFound() {
    UUID foreign = UUID.randomUUID();
    when(chatService.getChat(foreign, userId))
        .thenThrow(new NotFoundException("Chat nicht gefunden"));

    assertThatThrownBy(() -> exportService.exportPdf(foreign, userId))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void theGesprächsnotizIsPartOfThePdf() throws IOException {
    Chat chat = new Chat(UUID.randomUUID(), userId, UUID.randomUUID(), "T", true, Set.of());
    List<ChatNotePoint> points =
        List.of(
            new ChatNotePoint(
                UUID.randomUUID(), "Arbeitet im Bürgerbüro", ChatNoteItemKind.RAHMEN, NOW));
    UUID chatId = UUID.randomUUID();
    when(chatService.getChat(chatId, userId))
        .thenReturn(new ChatConversation(chat, List.of(), points));

    String text = text(exportService.exportPdf(chatId, userId).content());

    assertThat(text).containsSubsequence("Gesprächsnotiz", "• Arbeitet im Bürgerbüro");
  }

  @Test
  void theFileNameDropsWhatAFileSystemRejects() {
    assertThat(ChatExportService.fileName("Antrag: 12/4-2026 \"neu\"?"))
        .isEqualTo("Antrag 12 4-2026 neu");
    assertThat(ChatExportService.fileName("..")).isEqualTo("Chat");
    assertThat(ChatExportService.fileName("x".repeat(200))).hasSize(80);
    assertThat(ChatExportService.fileName("Bescheid\u200B\u202E neu")).isEqualTo("Bescheid neu");
    assertThat(ChatExportService.fileName("Frist\u2003\u00A0 Bauamt")).isEqualTo("Frist Bauamt");
    String emoji = "\uD83D\uDE00";
    String cut = ChatExportService.fileName(emoji.repeat(100));
    assertThat(cut.codePointCount(0, cut.length())).isEqualTo(80);
    assertThat(Character.isHighSurrogate(cut.charAt(cut.length() - 1))).isFalse();
  }
}
