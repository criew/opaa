package io.opaa.api;

import io.opaa.api.dto.ChatCreateRequest;
import io.opaa.api.dto.ChatDetail;
import io.opaa.api.dto.ChatSummary;
import io.opaa.api.dto.ChatUpdateRequest;
import io.opaa.auth.CurrentUser;
import io.opaa.chat.ChatConversation;
import io.opaa.chat.ChatCreation;
import io.opaa.chat.ChatPatch;
import io.opaa.chat.ChatService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class ChatController {

  private final ChatService chatService;

  public ChatController(ChatService chatService) {
    this.chatService = chatService;
  }

  @PostMapping("/spaces/{spaceId}/chats")
  public ResponseEntity<ChatDetail> createChat(
      @PathVariable UUID spaceId,
      @Valid @RequestBody(required = false) ChatCreateRequest request,
      CurrentUser caller) {
    ChatConversation created =
        chatService.createChat(spaceId, caller.id(), toChatCreation(request));
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(ChatResponseMapper.toDetailResponse(created));
  }

  @GetMapping("/spaces/{spaceId}/chats")
  public List<ChatSummary> listSpaceChats(@PathVariable UUID spaceId, CurrentUser caller) {
    return ChatResponseMapper.toSummaryResponses(chatService.listChats(spaceId, caller.id()));
  }

  @GetMapping("/chats/{chatId}")
  public ChatDetail getChat(@PathVariable UUID chatId, CurrentUser caller) {
    return ChatResponseMapper.toDetailResponse(chatService.getChat(chatId, caller.id()));
  }

  @PatchMapping("/chats/{chatId}")
  public ChatDetail updateChat(
      @PathVariable UUID chatId,
      @Valid @RequestBody ChatUpdateRequest request,
      CurrentUser caller) {
    ChatConversation updated = chatService.updateChat(chatId, caller.id(), toChatPatch(request));
    return ChatResponseMapper.toDetailResponse(updated);
  }

  @DeleteMapping("/chats/{chatId}")
  public ResponseEntity<Void> deleteChat(@PathVariable UUID chatId, CurrentUser caller) {
    chatService.deleteChat(chatId, caller.id());
    return ResponseEntity.noContent().build();
  }

  private ChatCreation toChatCreation(ChatCreateRequest request) {
    ChatCreation creation = new ChatCreation();
    if (request == null) {
      return creation;
    }
    return creation
        .title(request.getTitle())
        .useKnowledge(request.getUseKnowledge())
        .referencedLibraryIds(request.getReferencedLibraryIds());
  }

  private ChatPatch toChatPatch(ChatUpdateRequest request) {
    return new ChatPatch()
        .title(request.getTitle())
        .useKnowledge(request.getUseKnowledge())
        .referencedLibraryIds(request.getReferencedLibraryIds());
  }
}
