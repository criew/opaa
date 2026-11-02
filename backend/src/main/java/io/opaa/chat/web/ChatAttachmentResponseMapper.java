package io.opaa.chat.web;

import io.opaa.api.dto.ChatAttachmentRef;
import io.opaa.chat.ChatAttachment;
import io.opaa.chat.ChatAttachmentView;
import java.util.List;

/** Maps chat attachments onto their generated response counterparts. */
final class ChatAttachmentResponseMapper {

  private ChatAttachmentResponseMapper() {}

  static io.opaa.api.dto.ChatAttachment toResponse(ChatAttachmentView view) {
    ChatAttachment attachment = view.attachment();
    return new io.opaa.api.dto.ChatAttachment(
            attachment.getId(),
            attachment.getSpaceId(),
            attachment.getFileName(),
            attachment.getSizeBytes(),
            attachment.getStatus(),
            attachment.getCreatedAt())
        .chatId(attachment.getChatId())
        .chatTitle(view.chatTitle())
        .contentType(attachment.getContentType())
        .failureReason(attachment.getFailureReason())
        .mode(view.mode())
        .deletionDueAt(view.deletionDueAt());
  }

  static List<ChatAttachmentRef> toRefs(List<ChatAttachment> attachments) {
    return attachments == null || attachments.isEmpty()
        ? null
        : attachments.stream()
            .map(
                attachment ->
                    new ChatAttachmentRef(attachment.getId(), attachment.getFileName())
                        .sizeBytes(attachment.getSizeBytes()))
            .toList();
  }
}
