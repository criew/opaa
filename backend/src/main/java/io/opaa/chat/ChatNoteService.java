package io.opaa.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The persistent half of the Gesprächsnotiz (#1487): reading a chat's points, appending what a
 * condensation produced, removing one point.
 *
 * <p><b>Carries no permission or archive guard of its own.</b> Those live in {@link ChatService},
 * which is the one place a chat's author and its space's state are checked, and which delegates
 * here - this class must not depend on {@code ChatService} in turn, since {@link
 * ChatService#getChat} needs the note for every chat it returns.
 *
 * <p>Which candidates enter and how many old points fall out is decided by {@link ChatNoteList},
 * shared with the multi-turn evaluation harness so the measured note and the built one cannot
 * differ.
 */
@Service
public class ChatNoteService {

  private static final Logger log = LoggerFactory.getLogger(ChatNoteService.class);

  private final ChatNoteItemRepository chatNoteItemRepository;
  private final ChatNoteProperties chatNoteProperties;

  public ChatNoteService(
      ChatNoteItemRepository chatNoteItemRepository, ChatNoteProperties chatNoteProperties) {
    this.chatNoteItemRepository = chatNoteItemRepository;
    this.chatNoteProperties = chatNoteProperties;
  }

  /** The chat's note, oldest point first; empty for a chat that has none. */
  @Transactional(readOnly = true)
  public List<ChatNotePoint> points(UUID chatId) {
    return chatNoteItemRepository.findByChatIdOrderByPositionAsc(chatId).stream()
        .map(ChatNoteItem::toPoint)
        .toList();
  }

  /**
   * Appends the candidates the note accepts and drops as many oldest points as the cap requires,
   * atomically. Returns the points actually appended, empty when every candidate duplicated a
   * current point.
   *
   * <p>Not idempotent and not retried: a {@code uk_chat_note_items_chat_position} violation from
   * two condensations racing for the same position propagates to {@link
   * ChatNoteExtractionService}'s catch-all, where it means what every other condensation failure
   * means - this turn contributes no points, and there is no second attempt.
   */
  @Transactional
  public List<ChatNotePoint> append(UUID chatId, List<ChatNoteCandidate> candidates) {
    if (candidates.isEmpty()) {
      return List.of();
    }
    List<ChatNoteItem> current = chatNoteItemRepository.findByChatIdOrderByPositionAsc(chatId);
    List<ChatNoteCandidate> accepted =
        ChatNoteList.accept(current.stream().map(ChatNoteItem::getText).toList(), candidates);
    if (accepted.isEmpty()) {
      return List.of();
    }

    int position = chatNoteItemRepository.nextPositionFor(chatId);
    List<ChatNoteItem> appended = new ArrayList<>(accepted.size());
    for (ChatNoteCandidate candidate : accepted) {
      appended.add(new ChatNoteItem(chatId, position++, candidate.text(), candidate.kind()));
    }
    chatNoteItemRepository.saveAll(appended);

    int overflow =
        ChatNoteList.overflow(current.size(), appended.size(), chatNoteProperties.maxItems());
    if (overflow > 0) {
      chatNoteItemRepository.deleteAll(current.subList(0, overflow));
      log.debug(
          "Chat note of chat {} reached its cap - dropped {} oldest point(s)", chatId, overflow);
    }
    return appended.stream().map(ChatNoteItem::toPoint).toList();
  }

  /**
   * Removes one point of this chat's note. Returns {@code false} when the chat has no such point -
   * the caller turns that into the same 404 a foreign chat produces.
   */
  @Transactional
  public boolean deletePoint(UUID chatId, UUID itemId) {
    return chatNoteItemRepository
        .findByIdAndChatId(itemId, chatId)
        .map(
            item -> {
              chatNoteItemRepository.delete(item);
              return true;
            })
        .orElse(false);
  }
}
