package io.opaa.api;

import io.opaa.api.dto.ChatCreateRequest;
import io.opaa.api.dto.ChatDetail;
import io.opaa.api.dto.ChatSummary;
import io.opaa.api.dto.ChatUpdateRequest;
import io.opaa.auth.User;
import io.opaa.auth.UserService;
import io.opaa.chat.ChatConversation;
import io.opaa.chat.ChatCreation;
import io.opaa.chat.ChatPatch;
import io.opaa.chat.ChatService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1")
public class ChatController {

  private static final String UNKNOWN_ISSUER = "unknown";

  private final ChatService chatService;
  private final UserService userService;

  public ChatController(ChatService chatService, UserService userService) {
    this.chatService = chatService;
    this.userService = userService;
  }

  @PostMapping("/spaces/{spaceId}/chats")
  public ResponseEntity<ChatDetail> createChat(
      @PathVariable UUID spaceId,
      @Valid @RequestBody(required = false) ChatCreateRequest request,
      @AuthenticationPrincipal Jwt jwt) {
    User currentUser = currentUser(jwt);
    ChatConversation created =
        chatService.createChat(spaceId, currentUser.getId(), toChatCreation(request));
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(ChatResponseMapper.toDetailResponse(created));
  }

  @GetMapping("/spaces/{spaceId}/chats")
  public List<ChatSummary> listSpaceChats(
      @PathVariable UUID spaceId, @AuthenticationPrincipal Jwt jwt) {
    User currentUser = currentUser(jwt);
    return ChatResponseMapper.toSummaryResponses(
        chatService.listChats(spaceId, currentUser.getId()));
  }

  @GetMapping("/chats/{chatId}")
  public ChatDetail getChat(@PathVariable UUID chatId, @AuthenticationPrincipal Jwt jwt) {
    User currentUser = currentUser(jwt);
    return ChatResponseMapper.toDetailResponse(chatService.getChat(chatId, currentUser.getId()));
  }

  @PatchMapping("/chats/{chatId}")
  public ChatDetail updateChat(
      @PathVariable UUID chatId,
      @Valid @RequestBody ChatUpdateRequest request,
      @AuthenticationPrincipal Jwt jwt) {
    User currentUser = currentUser(jwt);
    ChatConversation updated =
        chatService.updateChat(chatId, currentUser.getId(), toChatPatch(request));
    return ChatResponseMapper.toDetailResponse(updated);
  }

  @DeleteMapping("/chats/{chatId}")
  public ResponseEntity<Void> deleteChat(
      @PathVariable UUID chatId, @AuthenticationPrincipal Jwt jwt) {
    User currentUser = currentUser(jwt);
    chatService.deleteChat(chatId, currentUser.getId());
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

  private User currentUser(Jwt jwt) {
    String issuer = jwt.getClaimAsString("iss");
    if (issuer == null || issuer.isBlank()) {
      issuer = UNKNOWN_ISSUER;
    }

    return userService
        .findBySubjectAndIssuer(jwt.getSubject(), issuer)
        .orElseThrow(
            () -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Benutzer nicht gefunden"));
  }
}
