package io.opaa.chat;

import io.opaa.branding.BrandingSettingsService;
import io.opaa.branding.EffectiveBranding;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * The PDF export of a chat (docs/features/chat-list.md, "Export"). Whoever may read the chat may
 * export it - {@link ChatService#getChat}, the author only, 404 for everyone else; the export
 * closes with the branding's AI notice and product name, as a copied answer does. Timestamps are
 * printed in the zone of the application's clock.
 */
@Service
public class ChatExportService {

  /** Characters a file name must not carry on common file systems, plus control characters. */
  private static final String UNSAFE_FILE_NAME_CHARACTERS = "[\\\\/:*?\"<>|\\p{Cntrl}]";

  private static final int FILE_NAME_MAX_LENGTH = 80;

  private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

  private final ChatService chatService;
  private final BrandingSettingsService brandingSettingsService;
  private final Clock clock;

  public ChatExportService(
      ChatService chatService, BrandingSettingsService brandingSettingsService, Clock clock) {
    this.chatService = chatService;
    this.brandingSettingsService = brandingSettingsService;
    this.clock = clock;
  }

  public ChatPdf exportPdf(UUID chatId, UUID userId) {
    ChatTranscript transcript = ChatTranscript.of(chatService.getChat(chatId, userId));
    EffectiveBranding branding = brandingSettingsService.currentBranding();
    Instant now = Instant.now(clock);
    String note =
        oneLine(branding.aiNotice())
            + " – "
            + oneLine(branding.productName())
            + ", "
            + DATE.format(now.atZone(clock.getZone()));
    byte[] pdf = new ChatPdfRenderer(clock.getZone()).render(transcript, note, now);
    return new ChatPdf(fileName(transcript.title()) + ".pdf", pdf);
  }

  /**
   * The title without characters a file system rejects and without invisible format characters, at
   * most {@value #FILE_NAME_MAX_LENGTH} code points.
   */
  static String fileName(String title) {
    String cleaned =
        title
            .replaceAll("\\p{Cf}", "")
            .replaceAll(UNSAFE_FILE_NAME_CHARACTERS, " ")
            .replaceAll("(?U)\\s+", " ");
    cleaned = cleaned.strip().replaceAll("^\\.+", "").strip();
    if (cleaned.codePointCount(0, cleaned.length()) > FILE_NAME_MAX_LENGTH) {
      cleaned = cleaned.substring(0, cleaned.offsetByCodePoints(0, FILE_NAME_MAX_LENGTH)).strip();
    }
    return cleaned.isEmpty() ? "Chat" : cleaned;
  }

  /**
   * Notice and product name on one line, by the rule of the copied note in the frontend ({@code
   * plainNoteText}): line breaks become blanks; control characters, direction controls and marks
   * (U+202A-202E, U+2066-2069, U+200E/F, U+061C) and tag characters are dropped, so nothing
   * reorders or hides text in the note.
   */
  private static String oneLine(String text) {
    return text.replaceAll("[\\u2028\\u2029]", "\n")
        .lines()
        .map(line -> withoutDroppedCharacters(line).strip())
        .filter(line -> !line.isEmpty())
        .collect(Collectors.joining(" "));
  }

  private static String withoutDroppedCharacters(String line) {
    StringBuilder kept = new StringBuilder(line.length());
    line.codePoints().filter(point -> !isDroppedFromNote(point)).forEach(kept::appendCodePoint);
    return kept.toString();
  }

  private static boolean isDroppedFromNote(int point) {
    return Character.isISOControl(point)
        || point == 0x061C
        || point == 0x200E
        || point == 0x200F
        || (point >= 0x202A && point <= 0x202E)
        || (point >= 0x2066 && point <= 0x2069)
        || (point >= 0xE0000 && point <= 0xE007F);
  }
}
