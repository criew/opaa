package io.opaa.chat;

import java.util.Objects;
import java.util.UUID;

/**
 * The prompt a question was built from, as it stood when the question was sent
 * (docs/features/spaces-and-assets.md#prompt-im-chat): id and title are a snapshot on the
 * question's message and are never resolved again, so the hint survives a renamed, deleted or no
 * longer readable prompt.
 */
public record UsedPrompt(UUID id, String title) {

  public UsedPrompt {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(title, "title");
  }
}
