package io.opaa.chat;

import io.opaa.indexing.metadata.MetadataFilter;
import java.util.List;
import java.util.UUID;

/**
 * Domain counterpart of the generated {@code ChatUpdateRequest} at the {@link
 * ChatService#updateChat} boundary (#860 Teil 4) - mirrors its fluent {@code withX}-style setters.
 * Every field is optional and only overwrites the chat when non-null (PATCH semantics), matching
 * {@link Chat#applyUpdate}. {@code ChatController} builds one of these from the generated request
 * DTO; {@link ChatService#updateChat} is where {@code referencedLibraryIds} actually turns from
 * this record's {@code List} into the {@code Set} the entity works with - {@code null} means
 * "omitted", an empty set means "clear".
 */
public final class ChatPatch {

  private String title;
  private Boolean useKnowledge;
  private List<UUID> referencedLibraryIds;
  private MetadataFilter metadataFilter;

  public ChatPatch() {}

  /**
   * The chat's core-field filter (#1070): {@code null} means "omitted", an empty filter means
   * "clear" - the same omitted/cleared distinction {@code referencedLibraryIds} draws with an empty
   * list.
   */
  public ChatPatch metadataFilter(MetadataFilter metadataFilter) {
    this.metadataFilter = metadataFilter;
    return this;
  }

  public MetadataFilter getMetadataFilter() {
    return metadataFilter;
  }

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
