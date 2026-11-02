package io.opaa.chat;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.ai.document.Document;

/**
 * What the attachments of a chat contribute to one answer: the passages, in the form of retrieved
 * chunks; the ids of every attachment they may come from, to mark the sources; the attachments this
 * question newly names, to bind them with the turn; and whether the passages are excerpts.
 */
public record ChatAttachmentContext(
    List<Document> passages,
    Set<UUID> attachmentIds,
    List<UUID> newAttachmentIds,
    boolean excerpts) {

  public static final ChatAttachmentContext NONE =
      new ChatAttachmentContext(List.of(), Set.of(), List.of(), false);

  public boolean isEmpty() {
    return passages.isEmpty();
  }
}
