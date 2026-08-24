package io.opaa.chat;

import java.util.List;
import java.util.UUID;

/**
 * Domain counterpart of the generated {@code ChatCreateRequest} at the {@link
 * ChatService#createChat} boundary (#860 Teil 4) - mirrors its fluent {@code withX}-style setters
 * for a low-friction test call site. {@code ChatController} builds one of these from the (possibly
 * absent, see {@code POST /spaces/{spaceId}/chats}) generated request DTO; {@link
 * ChatService#createChat} is where {@code referencedLibraryIds} actually turns from this record's
 * {@code List} into the {@code Set} the entity works with.
 */
public final class ChatCreation {

  private String title;
  private Boolean useKnowledge;
  private List<UUID> referencedLibraryIds;

  public ChatCreation() {}

  public ChatCreation title(String title) {
    this.title = title;
    return this;
  }

  public ChatCreation useKnowledge(Boolean useKnowledge) {
    this.useKnowledge = useKnowledge;
    return this;
  }

  public ChatCreation referencedLibraryIds(List<UUID> referencedLibraryIds) {
    this.referencedLibraryIds = referencedLibraryIds;
    return this;
  }

  public String getTitle() {
    return title;
  }

  public Boolean getUseKnowledge() {
    return useKnowledge;
  }

  public List<UUID> getReferencedLibraryIds() {
    return referencedLibraryIds;
  }
}
