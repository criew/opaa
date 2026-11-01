package io.opaa.chat.web;

import io.opaa.api.dto.ChatAttachmentLibraryCopyRequest;
import io.opaa.api.dto.LibraryDocumentResponse;
import io.opaa.auth.Caller;
import io.opaa.auth.CurrentUser;
import io.opaa.chat.ChatAttachmentService;
import io.opaa.common.ConditionalOnFeature;
import io.opaa.common.Feature;
import io.opaa.library.web.InlineContentResponses;
import io.opaa.library.web.LibraryDocumentResponseMapper;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * The chat attachments of the calling person; every operation reaches only the caller's own. Absent
 * while the chat-upload feature is switched off, so its routes answer 404 like unknown ones.
 */
@RestController
@RequestMapping("/api/v1")
@ConditionalOnFeature(Feature.CHAT_UPLOAD)
public class ChatAttachmentController {

  private final ChatAttachmentService attachmentService;

  public ChatAttachmentController(ChatAttachmentService attachmentService) {
    this.attachmentService = attachmentService;
  }

  @PostMapping("/spaces/{spaceId}/chat-attachments")
  public ResponseEntity<io.opaa.api.dto.ChatAttachment> uploadChatAttachment(
      @PathVariable UUID spaceId,
      @RequestParam("file") MultipartFile file,
      @Caller CurrentUser caller) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(
            ChatAttachmentResponseMapper.toResponse(
                attachmentService.upload(spaceId, file, caller)));
  }

  @GetMapping("/chat-attachments")
  public List<io.opaa.api.dto.ChatAttachment> listMyChatAttachments(@Caller CurrentUser caller) {
    return attachmentService.listOwn(caller.id()).stream()
        .map(ChatAttachmentResponseMapper::toResponse)
        .toList();
  }

  @GetMapping("/chat-attachments/{attachmentId}")
  public io.opaa.api.dto.ChatAttachment getChatAttachment(
      @PathVariable UUID attachmentId, @Caller CurrentUser caller) {
    return ChatAttachmentResponseMapper.toResponse(
        attachmentService.get(attachmentId, caller.id()));
  }

  @DeleteMapping("/chat-attachments/{attachmentId}")
  public ResponseEntity<Void> deleteChatAttachment(
      @PathVariable UUID attachmentId, @Caller CurrentUser caller) {
    attachmentService.delete(attachmentId, caller.id());
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/chat-attachments/{attachmentId}/content")
  public ResponseEntity<Resource> getChatAttachmentContent(
      @PathVariable UUID attachmentId, @Caller CurrentUser caller) {
    return InlineContentResponses.of(attachmentService.content(attachmentId, caller.id()));
  }

  @PostMapping("/chat-attachments/{attachmentId}/library-copy")
  public ResponseEntity<LibraryDocumentResponse> copyChatAttachmentToLibrary(
      @PathVariable UUID attachmentId,
      @Valid @RequestBody ChatAttachmentLibraryCopyRequest request,
      @Caller CurrentUser caller) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(
            LibraryDocumentResponseMapper.toResponse(
                attachmentService.copyToLibrary(attachmentId, request.getLibraryId(), caller)));
  }
}
