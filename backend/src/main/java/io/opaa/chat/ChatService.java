package io.opaa.chat;

import io.opaa.api.dto.ChatCreateRequest;
import io.opaa.api.dto.ChatDetail;
import io.opaa.api.dto.ChatMessageResponse;
import io.opaa.api.dto.ChatSummary;
import io.opaa.api.dto.ChatUpdateRequest;
import io.opaa.api.dto.SourceReference;
import io.opaa.library.LibraryAccessService;
import io.opaa.space.Space;
import io.opaa.space.SpaceMembershipRepository;
import io.opaa.space.SpaceRepository;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Chats as persistent, space-owned objects (#525, docs/features/spaces-and-assets.md#chats).
 * Chatting requires space membership; a chat itself is visible only to its author - not even a
 * space or system admin, see {@link ChatRepository#findByIdAndAuthorId}'s Javadoc, which every
 * chat-scoped read/write in this class goes through.
 *
 * <p>The public, DTO-returning methods ({@link #createChat}, {@link #listChats}, {@link #getChat},
 * {@link #updateChat}) follow the same convention as {@code SpaceService}: they accept the
 * generated request DTO directly and return the generated response DTO, so {@code ChatController}
 * stays a thin translation from HTTP to this service. The entity-returning methods below them
 * ({@link #findOwnedChat}, {@link #requireStillSpaceMember}, {@link #effectiveLibraryScope}, {@link
 * #historyAsSpringAiMessages}, {@link #appendTurn}) exist for {@code QueryService}, which needs the
 * {@link Chat} entity itself, not a response shape.
 *
 * <p><b>{@link #getChat}/{@link #updateChat}/{@link #deleteChat} deliberately stay author-exclusive
 * even without space membership</b> (#525 review, finding 4) - a chat's private content belongs to
 * its author per docs/features/spaces-and-assets.md#private-inhalte-sind-nicht-teil-der-akte
 * regardless of whether that author is still a space member, mirroring how a departed employee's
 * private mail is not thrown away the moment their department changes. {@link
 * #requireStillSpaceMember} is the one exception: <b>querying is chatting</b>, and chatting
 * requires membership - a departed author must not be able to keep running new questions through a
 * space's knowledge scope after losing access to it, even though their existing chat and its
 * history remain theirs to read.
 */
@Service
@Transactional(readOnly = true)
public class ChatService {

  private static final Logger log = LoggerFactory.getLogger(ChatService.class);
  private static final int DERIVED_TITLE_MAX_LENGTH = 80;

  private final ChatRepository chatRepository;
  private final ChatMessageRepository chatMessageRepository;
  private final SpaceRepository spaceRepository;
  private final SpaceMembershipRepository spaceMembershipRepository;
  private final LibraryAccessService libraryAccessService;
  private final ObjectMapper objectMapper;

  public ChatService(
      ChatRepository chatRepository,
      ChatMessageRepository chatMessageRepository,
      SpaceRepository spaceRepository,
      SpaceMembershipRepository spaceMembershipRepository,
      LibraryAccessService libraryAccessService,
      ObjectMapper objectMapper) {
    this.chatRepository = chatRepository;
    this.chatMessageRepository = chatMessageRepository;
    this.spaceRepository = spaceRepository;
    this.spaceMembershipRepository = spaceMembershipRepository;
    this.libraryAccessService = libraryAccessService;
    this.objectMapper = objectMapper;
  }

  @Transactional
  public ChatDetail createChat(UUID spaceId, UUID authorId, ChatCreateRequest request) {
    Space space = requireMembership(spaceId, authorId);
    Boolean useKnowledge = request == null ? null : request.getUseKnowledge();
    String title = request == null ? null : request.getTitle();
    Set<UUID> referencedLibraryIds =
        request == null || request.getReferencedLibraryIds() == null
            ? Set.of()
            : new LinkedHashSet<>(request.getReferencedLibraryIds());
    requireReadableLibraries(referencedLibraryIds, authorId, space.getOrganizationId());

    Chat chat =
        new Chat(
            spaceId,
            authorId,
            space.getOrganizationId(),
            title,
            useKnowledge == null || useKnowledge,
            referencedLibraryIds);
    Chat saved = chatRepository.save(chat);
    return toDetail(saved);
  }

  public List<ChatSummary> listChats(UUID spaceId, UUID authorId) {
    requireMembership(spaceId, authorId);
    return chatRepository.findBySpaceIdAndAuthorIdOrderByUpdatedAtDesc(spaceId, authorId).stream()
        .map(this::toSummary)
        .toList();
  }

  public ChatDetail getChat(UUID chatId, UUID authorId) {
    return toDetail(getOwnedChat(chatId, authorId));
  }

  @Transactional
  public ChatDetail updateChat(UUID chatId, UUID authorId, ChatUpdateRequest request) {
    Chat chat = getOwnedChat(chatId, authorId);
    Set<UUID> referencedLibraryIds =
        request.getReferencedLibraryIds() == null
            ? null
            : new LinkedHashSet<>(request.getReferencedLibraryIds());
    if (referencedLibraryIds != null) {
      requireReadableLibraries(referencedLibraryIds, authorId, chat.getOrganizationId());
    }
    chat.applyUpdate(request.getTitle(), request.getUseKnowledge(), referencedLibraryIds);
    return toDetail(chatRepository.save(chat));
  }

  @Transactional
  public void deleteChat(UUID chatId, UUID authorId) {
    chatRepository.delete(getOwnedChat(chatId, authorId));
  }

  /**
   * Loads a chat the caller authored, or throws 404 - deliberately the same status for "no such
   * chat" and "exists but belongs to someone else" (#525 acceptance criterion: a foreign user,
   * including a space or system admin, gets 404, not 403, which would confirm the chat's
   * existence).
   */
  private Chat getOwnedChat(UUID chatId, UUID authorId) {
    return chatRepository
        .findByIdAndAuthorId(chatId, authorId)
        .orElseThrow(
            () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Chat nicht gefunden"));
  }

  /**
   * Same lookup as {@link #getOwnedChat}, but returning empty instead of throwing - used by {@code
   * QueryService#query} for the optional {@code chatId} on a query request, where "no such owned
   * chat" and "no chatId given" are handled identically (an ephemeral, unpersisted query).
   */
  public Optional<Chat> findOwnedChat(UUID chatId, UUID authorId) {
    if (chatId == null) {
      return Optional.empty();
    }
    return chatRepository.findByIdAndAuthorId(chatId, authorId);
  }

  /**
   * Verifies the chat's author still belongs to the chat's space - see this class's Javadoc for why
   * only the query path requires this and {@link #getChat}/{@link #updateChat}/{@link #deleteChat}
   * deliberately do not (#525 review, finding 4).
   */
  public void requireStillSpaceMember(Chat chat) {
    boolean member =
        spaceMembershipRepository
            .findByUserIdAndSpaceId(chat.getAuthorId(), chat.getSpaceId())
            .isPresent();
    if (!member) {
      throw new ResponseStatusException(
          HttpStatus.FORBIDDEN, "Sie sind kein Mitglied dieses Space mehr");
    }
  }

  /**
   * The persisted history as Spring AI messages, ordered by the application-assigned {@code
   * sequence} (see {@link ChatMessage}'s Javadoc), not {@code created_at}.
   */
  public List<Message> historyAsSpringAiMessages(UUID chatId) {
    return chatMessageRepository.findByChatIdOrderBySequenceAsc(chatId).stream()
        .<Message>map(
            m ->
                m.getRole() == ChatRole.USER
                    ? new UserMessage(m.getContent())
                    : new AssistantMessage(m.getContent()))
        .toList();
  }

  /**
   * The search scope for a query run in this chat (epic #523 "Entschiedene Semantik"): every
   * readable library when {@code useKnowledge} is on, or the intersection of the
   * sticky @-references with the readable libraries when it is off - never wider than {@code
   * readableLibraryIds}, regardless of what the chat references.
   */
  public Set<UUID> effectiveLibraryScope(Chat chat, Set<UUID> readableLibraryIds) {
    if (chat.isUseKnowledge()) {
      return readableLibraryIds;
    }
    Set<UUID> scoped = new LinkedHashSet<>(chat.getReferencedLibraryIds());
    scoped.retainAll(readableLibraryIds);
    return scoped;
  }

  /**
   * Persists one question/answer turn and, if the chat never had a title set explicitly, derives
   * one from the question (#525's "Titel-Default aus der ersten Frage ableiten"). Called from
   * {@code QueryService#query} after generating the answer - the caller has already verified {@code
   * chat} belongs to the requesting user via {@link #findOwnedChat}.
   *
   * <p><b>{@code REQUIRES_NEW} (#525 review, finding 1 - critical):</b> {@code QueryService#query}
   * runs inside a class-level {@code @Transactional(readOnly = true)} transaction. A caller in a
   * read-only transaction that merely joined this method's writes would never actually flush them -
   * Hibernate silently drops modifications made under a read-only transaction, so every persisted
   * turn and title would vanish without error the moment the outer transaction committed. {@code
   * REQUIRES_NEW} opens an independent, writable transaction that commits on its own, exactly like
   * {@code SpaceService#ensureDefaultSpace} already does for the same reason (see that method's
   * Javadoc). Reproduced empirically: with plain {@code @Transactional}, {@code chat_messages}
   * contains zero rows after a real {@code QueryService#query} call against Postgres - see {@code
   * QueryServiceIntegrationTest#queryPersistsTheTurnForARealChat} and its Javadoc for the exact
   * failure.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void appendTurn(Chat chat, String question, String answer, List<SourceReference> sources) {
    int nextSequence = chatMessageRepository.countByChatId(chat.getId());
    chatMessageRepository.save(
        new ChatMessage(chat.getId(), nextSequence, ChatRole.USER, question, null));
    chatMessageRepository.save(
        new ChatMessage(
            chat.getId(), nextSequence + 1, ChatRole.ASSISTANT, answer, serializeSources(sources)));
    chat.deriveTitleFromFirstQuestionIfAbsent(deriveTitle(question));
    // #525 review, finding/nit d: touch() forces updated_at even when neither the title nor any
    // other chat field actually changed this turn - without it, the chat list's "sorted by last
    // use" ordering (findBySpaceIdAndAuthorIdOrderByUpdatedAtDesc) goes stale after the first turn.
    chat.touch();
    chatRepository.save(chat);
  }

  private ChatSummary toSummary(Chat chat) {
    return new ChatSummary(
            chat.getId(),
            chat.getSpaceId(),
            chat.getAuthorId(),
            chat.isUseKnowledge(),
            chat.getStatus(),
            chat.getCreatedAt(),
            chat.getUpdatedAt())
        .title(chat.getTitle())
        .referencedLibraryIds(List.copyOf(chat.getReferencedLibraryIds()));
  }

  private ChatDetail toDetail(Chat chat) {
    List<ChatMessageResponse> messages =
        chatMessageRepository.findByChatIdOrderBySequenceAsc(chat.getId()).stream()
            .map(this::toMessageResponse)
            .toList();
    return new ChatDetail(
            chat.getId(),
            chat.getSpaceId(),
            chat.getAuthorId(),
            chat.isUseKnowledge(),
            chat.getStatus(),
            messages,
            chat.getCreatedAt(),
            chat.getUpdatedAt())
        .title(chat.getTitle())
        .referencedLibraryIds(List.copyOf(chat.getReferencedLibraryIds()));
  }

  private ChatMessageResponse toMessageResponse(ChatMessage message) {
    return new ChatMessageResponse(
            message.getId(),
            message.getChatId(),
            message.getRole(),
            message.getContent(),
            message.getCreatedAt())
        .sources(parseSources(message.getSources()));
  }

  private List<SourceReference> parseSources(String sourcesJson) {
    if (sourcesJson == null || sourcesJson.isBlank()) {
      return null;
    }
    try {
      return objectMapper.readValue(sourcesJson, new TypeReference<List<SourceReference>>() {});
    } catch (JacksonException e) {
      log.warn("Failed to parse persisted chat message sources, treating as absent", e);
      return null;
    }
  }

  private String serializeSources(List<SourceReference> sources) {
    if (sources == null || sources.isEmpty()) {
      return null;
    }
    try {
      return objectMapper.writeValueAsString(sources);
    } catch (JacksonException e) {
      log.warn("Failed to serialize chat message sources, storing none", e);
      return null;
    }
  }

  private String deriveTitle(String question) {
    if (question == null) {
      return null;
    }
    String trimmed = question.trim();
    if (trimmed.length() <= DERIVED_TITLE_MAX_LENGTH) {
      return trimmed;
    }
    return trimmed.substring(0, DERIVED_TITLE_MAX_LENGTH - 1).stripTrailing() + "…";
  }

  private Space requireMembership(UUID spaceId, UUID userId) {
    Space space =
        spaceRepository
            .findById(spaceId)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Space nicht gefunden"));
    boolean member = spaceMembershipRepository.findByUserIdAndSpaceId(userId, spaceId).isPresent();
    if (!member) {
      throw new ResponseStatusException(
          HttpStatus.FORBIDDEN, "Sie sind kein Mitglied dieses Space");
    }
    return space;
  }

  /**
   * Rejects any id in {@code referencedLibraryIds} the caller may not read - including one that
   * does not exist at all, with the identical message for both cases (#525 review, finding/nit b):
   * distinguishing "not readable" from "does not exist" would let a caller probe for library ids
   * they have no rights on, and a bare foreign-key violation from {@code chat_library_references}
   * would otherwise surface as an opaque 500 instead of a 400.
   */
  private void requireReadableLibraries(
      Set<UUID> referencedLibraryIds, UUID authorId, UUID organizationId) {
    if (referencedLibraryIds.isEmpty()) {
      return;
    }
    Set<UUID> readable = libraryAccessService.readableLibraryIds(authorId, organizationId);
    if (!readable.containsAll(referencedLibraryIds)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "referencedLibraryIds enthält eine Bibliothek, die nicht lesbar ist");
    }
  }
}
