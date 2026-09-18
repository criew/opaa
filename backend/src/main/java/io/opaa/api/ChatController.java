package io.opaa.api;

import io.opaa.api.dto.ChatBulkActionRequest;
import io.opaa.api.dto.ChatBulkActionResult;
import io.opaa.api.dto.ChatCreateRequest;
import io.opaa.api.dto.ChatDetail;
import io.opaa.api.dto.ChatSearchRequest;
import io.opaa.api.dto.ChatSearchResponse;
import io.opaa.api.dto.ChatSummary;
import io.opaa.api.dto.ChatSummaryPage;
import io.opaa.api.dto.ChatUpdateRequest;
import io.opaa.auth.Caller;
import io.opaa.auth.CurrentUser;
import io.opaa.chat.ChatConversation;
import io.opaa.chat.ChatCreation;
import io.opaa.chat.ChatPatch;
import io.opaa.chat.ChatService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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
      @Caller CurrentUser caller) {
    ChatConversation created =
        chatService.createChat(spaceId, caller.id(), toChatCreation(request));
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(ChatResponseMapper.toDetailResponse(created));
  }

  @GetMapping("/spaces/{spaceId}/chats")
  public List<ChatSummary> listSpaceChats(@PathVariable UUID spaceId, @Caller CurrentUser caller) {
    return ChatResponseMapper.toSummaryResponses(chatService.listChats(spaceId, caller.id()));
  }

  @GetMapping("/spaces/{spaceId}/chats/archived")
  public ChatSummaryPage listArchivedSpaceChats(
      @PathVariable UUID spaceId,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "25") int size,
      @Caller CurrentUser caller) {
    // Checked here so the 400 carries a German message rather than PageRequest's own.
    if (page < 0) {
      throw new IllegalArgumentException("page darf nicht negativ sein, war " + page);
    }
    if (size < 1 || size > 100) {
      throw new IllegalArgumentException("size muss zwischen 1 und 100 liegen, war " + size);
    }
    return ChatResponseMapper.toSummaryPage(
        chatService.listArchivedChats(spaceId, caller.id(), PageRequest.of(page, size)));
  }

  @PostMapping("/spaces/{spaceId}/chats/bulk-actions")
  public ChatBulkActionResult applyChatBulkAction(
      @PathVariable UUID spaceId,
      @Valid @RequestBody ChatBulkActionRequest request,
      @Caller CurrentUser caller) {
    List<UUID> applied =
        switch (request.getAction()) {
          case ARCHIVE -> chatService.archiveChats(spaceId, caller.id(), request.getChatIds());
          case UNARCHIVE -> chatService.unarchiveChats(spaceId, caller.id(), request.getChatIds());
          case DELETE -> chatService.deleteChats(spaceId, caller.id(), request.getChatIds());
        };
    return new ChatBulkActionResult(applied);
  }

  /** The term travels in the body, never in the URL, so no access log records it. */
  @PostMapping("/spaces/{spaceId}/chats/search")
  public ChatSearchResponse searchSpaceChats(
      @PathVariable UUID spaceId,
      @Valid @RequestBody ChatSearchRequest request,
      @Caller CurrentUser caller) {
    return ChatSearchResponseMapper.toResponse(
        chatService.searchChats(
            spaceId, caller.id(), request.getQuery(), request.getPage(), request.getPageSize()));
  }

  @GetMapping("/chats/{chatId}")
  public ChatDetail getChat(@PathVariable UUID chatId, @Caller CurrentUser caller) {
    return ChatResponseMapper.toDetailResponse(chatService.getChat(chatId, caller.id()));
  }

  @PatchMapping("/chats/{chatId}")
  public ChatDetail updateChat(
      @PathVariable UUID chatId,
      @Valid @RequestBody ChatUpdateRequest request,
      @Caller CurrentUser caller) {
    ChatConversation updated = chatService.updateChat(chatId, caller.id(), toChatPatch(request));
    return ChatResponseMapper.toDetailResponse(updated);
  }

  @DeleteMapping("/chats/{chatId}")
  public ResponseEntity<Void> deleteChat(@PathVariable UUID chatId, @Caller CurrentUser caller) {
    chatService.deleteChat(chatId, caller.id());
    return ResponseEntity.noContent().build();
  }

  @PutMapping("/chats/{chatId}/pin")
  public ChatSummary pinChat(@PathVariable UUID chatId, @Caller CurrentUser caller) {
    return ChatResponseMapper.toSummaryResponse(chatService.pinChat(chatId, caller.id()));
  }

  @DeleteMapping("/chats/{chatId}/pin")
  public ResponseEntity<Void> unpinChat(@PathVariable UUID chatId, @Caller CurrentUser caller) {
    chatService.unpinChat(chatId, caller.id());
    return ResponseEntity.noContent().build();
  }

  @PutMapping("/chats/{chatId}/archive")
  public ChatSummary archiveChat(@PathVariable UUID chatId, @Caller CurrentUser caller) {
    return ChatResponseMapper.toSummaryResponse(chatService.archiveChat(chatId, caller.id()));
  }

  @DeleteMapping("/chats/{chatId}/archive")
  public ChatSummary unarchiveChat(@PathVariable UUID chatId, @Caller CurrentUser caller) {
    return ChatResponseMapper.toSummaryResponse(chatService.unarchiveChat(chatId, caller.id()));
  }

  @DeleteMapping("/chats/{chatId}/note-items/{itemId}")
  public ResponseEntity<Void> deleteChatNoteItem(
      @PathVariable UUID chatId, @PathVariable UUID itemId, @Caller CurrentUser caller) {
    chatService.deleteNoteItem(chatId, itemId, caller.id());
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
        .referencedLibraryIds(request.getReferencedLibraryIds())
        .metadataFilter(MetadataFilterMapper.toPatchDomain(request.getMetadataFilter()));
  }

  private ChatPatch toChatPatch(ChatUpdateRequest request) {
    return new ChatPatch()
        .title(request.getTitle())
        .useKnowledge(request.getUseKnowledge())
        .referencedLibraryIds(request.getReferencedLibraryIds())
        .metadataFilter(MetadataFilterMapper.toPatchDomain(request.getMetadataFilter()));
  }
}
