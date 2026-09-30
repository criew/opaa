package io.opaa.chat.web;

import io.opaa.api.dto.ChatImportRequest;
import io.opaa.api.dto.ChatImportSource;
import io.opaa.api.dto.ChatImportTurn;
import io.opaa.api.dto.ChatSummary;
import io.opaa.auth.Caller;
import io.opaa.auth.CurrentUser;
import io.opaa.chat.ChatImport;
import io.opaa.chat.ChatImportService;
import io.opaa.chat.ChatListEntry;
import io.opaa.chat.ChatService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The demo seed's import of prepared chat transcripts. The bean - and with it the route - exists
 * only while {@code opaa.demo.chat-import.enabled} is {@code true}; a regular installation answers
 * the path like any unknown route.
 */
@RestController
@RequestMapping("/api/v1")
@ConditionalOnProperty(name = "opaa.demo.chat-import.enabled", havingValue = "true")
public class ChatImportController {

  private final ChatImportService chatImportService;
  private final ChatService chatService;

  public ChatImportController(ChatImportService chatImportService, ChatService chatService) {
    this.chatImportService = chatImportService;
    this.chatService = chatService;
  }

  @PostMapping("/spaces/{spaceId}/chat-imports")
  public ResponseEntity<ChatSummary> importChat(
      @PathVariable UUID spaceId,
      @Valid @RequestBody ChatImportRequest request,
      @Caller CurrentUser caller) {
    UUID chatId = chatImportService.importChat(spaceId, caller.id(), toImport(request));
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(
            ChatResponseMapper.toSummaryResponse(
                new ChatListEntry(
                    chatService.findOwnedChat(chatId, caller.id()).orElseThrow(), null, null)));
  }

  private static ChatImport toImport(ChatImportRequest request) {
    return new ChatImport(
        request.getTitle(), request.getTurns().stream().map(ChatImportController::toTurn).toList());
  }

  private static ChatImport.Turn toTurn(ChatImportTurn turn) {
    List<ChatImportSource> sources = turn.getSources() == null ? List.of() : turn.getSources();
    return new ChatImport.Turn(
        turn.getQuestion(),
        turn.getAnswer(),
        turn.getAskedAt(),
        turn.getAnsweredAt(),
        sources.stream()
            .map(source -> new ChatImport.Source(source.getDocumentId(), source.getCited()))
            .toList());
  }
}
