package io.opaa.chat.web;

import io.opaa.auth.Caller;
import io.opaa.auth.CurrentUser;
import io.opaa.chat.ChatExportService;
import io.opaa.chat.ChatPdf;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The PDF export of a chat. No {@code produces=} on the mapping, so a refusal still answers with
 * the JSON error envelope; the PDF's type is set on the response itself.
 */
@RestController
@RequestMapping("/api/v1")
public class ChatExportController {

  private final ChatExportService chatExportService;

  public ChatExportController(ChatExportService chatExportService) {
    this.chatExportService = chatExportService;
  }

  @GetMapping("/chats/{chatId}/export/pdf")
  public ResponseEntity<byte[]> exportChatPdf(
      @PathVariable UUID chatId, @Caller CurrentUser caller) {
    ChatPdf pdf = chatExportService.exportPdf(chatId, caller.id());
    return ResponseEntity.ok()
        .contentType(MediaType.APPLICATION_PDF)
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment()
                .filename(pdf.fileName(), StandardCharsets.UTF_8)
                .build()
                .toString())
        .header(HttpHeaders.CACHE_CONTROL, "no-store")
        .body(pdf.content());
  }
}
