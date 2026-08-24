package io.opaa.chat;

import java.util.List;
import java.util.UUID;

/**
 * Domain counterpart of the generated {@code ChatUpdateRequest} at the {@link
 * ChatService#updateChat} boundary (#860 Teil 4) - mirrors its fluent {@code withX}-style setters.
 * Every field is optional and only overwrites the chat when non-null (PATCH semantics), matching
 * {@link Chat#applyUpdate}. {@link ChatController} builds one of these from the generated request
 * DTO, converting {@code referencedLibraryIds} from a {@code List} to the {@code Set} {@link
 * ChatService} works with - {@code null} means "omitted", an empty set means "clear".
 */
public final class ChatPatch {

  private String title;
  private Boolean useKnowledge;
  private List<UUID> referencedLibraryIds;

  public ChatPatch() {}

  public ChatPatch title(String title) {
    this.title = title;
    return this;
  }

  public ChatPatch useKnowledge(Boolean useKnowledge) {
    this.useKnowledge = useKnowledge;
    return this;
  }

  public ChatPatch referencedLibraryIds(List<UUID> referencedLibraryIds) {
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
