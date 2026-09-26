package io.opaa.chat;

import io.opaa.space.SpaceChatDirectory;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** Answers {@link SpaceChatDirectory} from this package's repository. */
@Component
class SpaceChatDirectoryAdapter implements SpaceChatDirectory {

  private final ChatRepository chatRepository;

  SpaceChatDirectoryAdapter(ChatRepository chatRepository) {
    this.chatRepository = chatRepository;
  }

  @Override
  public boolean existsInSpace(UUID spaceId) {
    return chatRepository.existsBySpaceId(spaceId);
  }

  @Override
  public Map<UUID, Long> countByAuthorPerSpace(Collection<UUID> spaceIds, UUID authorId) {
    return chatRepository.countBySpaceIdInAndAuthorId(spaceIds, authorId).stream()
        .collect(
            Collectors.toMap(
                ChatRepository.SpaceChatCount::getSpaceId,
                ChatRepository.SpaceChatCount::getChatCount));
  }
}
