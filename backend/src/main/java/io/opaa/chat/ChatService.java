package io.opaa.chat;

import io.opaa.api.dto.ChatCreateRequest;
import io.opaa.api.dto.ChatDetail;
import io.opaa.api.dto.ChatMessageResponse;
import io.opaa.api.dto.ChatSummary;
import io.opaa.api.dto.ChatUpdateRequest;
import io.opaa.api.dto.SourceReference;
import io.opaa.library.LibraryAccessService;
import io.opaa.space.Space;
import io.opaa.space.SpaceAssetAssociationRepository;
import io.opaa.space.SpaceMembershipRepository;
import io.opaa.space.SpaceRepository;
import java.time.Instant;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
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

  /**
   * Upper bound on {@link #appendTurn}'s retry loop - see that method's Javadoc (#525 review round
   * 2, finding/nit 2). Three is generous for a two-row insert colliding on a per-chat sequence: the
   * realistic contention is "one other concurrent question in the very same chat", never a herd.
   */
  private static final int APPEND_TURN_MAX_ATTEMPTS = 3;

  private final ChatRepository chatRepository;
  private final ChatMessageRepository chatMessageRepository;
  private final SpaceRepository spaceRepository;
  private final SpaceMembershipRepository spaceMembershipRepository;
  private final SpaceAssetAssociationRepository spaceAssetAssociationRepository;
  private final LibraryAccessService libraryAccessService;
  private final ObjectMapper objectMapper;
  private final TransactionTemplate requiresNewTransactionTemplate;
  private final ChatTitleGenerationService chatTitleGenerationService;

  public ChatService(
      ChatRepository chatRepository,
      ChatMessageRepository chatMessageRepository,
      SpaceRepository spaceRepository,
      SpaceMembershipRepository spaceMembershipRepository,
      SpaceAssetAssociationRepository spaceAssetAssociationRepository,
      LibraryAccessService libraryAccessService,
      ObjectMapper objectMapper,
      PlatformTransactionManager transactionManager,
      ChatTitleGenerationService chatTitleGenerationService) {
    this.chatRepository = chatRepository;
    this.chatMessageRepository = chatMessageRepository;
    this.spaceRepository = spaceRepository;
    this.spaceMembershipRepository = spaceMembershipRepository;
    this.spaceAssetAssociationRepository = spaceAssetAssociationRepository;
    this.libraryAccessService = libraryAccessService;
    this.objectMapper = objectMapper;
    this.requiresNewTransactionTemplate = new TransactionTemplate(transactionManager);
    this.requiresNewTransactionTemplate.setPropagationBehavior(
        TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.chatTitleGenerationService = chatTitleGenerationService;
  }

  @Transactional
  public ChatDetail createChat(UUID spaceId, UUID authorId, ChatCreateRequest request) {
    Space space = requireMembership(spaceId, authorId);
    // #543: an archived space accepts no new content - see docs/features/spaces-and-assets.md#
    // einen-space-stilllegen-archivieren-statt-löschen.
    if (space.isArchived()) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Der Space ist archiviert und lässt keine neuen Chats mehr zu");
    }
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
    // #613 review, finding 2: an archived space accepts no new content - not only no new chats,
    // but also no renaming, useKnowledge toggling or reference changes on an existing one. The
    // chat itself stays readable (getChat/deleteChat are deliberately not gated here - reading and
    // withdrawing are not "new content"), just frozen.
    requireSpaceNotArchived(chat.getSpaceId());
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
   * The search scope for a query run in this chat (epic #523 "Entschiedene Semantik", narrowed by
   * #203's space↔library association): when {@code useKnowledge} is on (@Alles-Wissen), the scope
   * is the space's associated libraries intersected with the readable libraries - or, if the space
   * has no associations at all, every readable library (the permanent transition rule, see
   * docs/features/spaces-and-assets.md#suchbereich-je-chatart: "Ein Space ohne Assoziationen
   * verengt nie"). When {@code useKnowledge} is off, the scope is the intersection of the
   * sticky @-references with the readable libraries. Neither branch is ever wider than {@code
   * readableLibraryIds}, regardless of what the chat references or the space associates.
   */
  public Set<UUID> effectiveLibraryScope(Chat chat, Set<UUID> readableLibraryIds) {
    if (chat.isUseKnowledge()) {
      Set<UUID> associatedLibraryIds =
          spaceAssetAssociationRepository.findLibraryIdsBySpaceId(chat.getSpaceId());
      if (associatedLibraryIds.isEmpty()) {
        return readableLibraryIds;
      }
      Set<UUID> scoped = new LinkedHashSet<>(associatedLibraryIds);
      scoped.retainAll(readableLibraryIds);
      return scoped;
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
   * <p><b>{@code Propagation.NOT_SUPPORTED}, overriding the class-level
   * {@code @Transactional(readOnly = true)}</b> (#525 review round 2, finding A): {@code
   * QueryService#query} deliberately runs with no ambient transaction of its own (see that method's
   * Javadoc) precisely so that this method's writes never share a connection with the read-only
   * work that precedes them - but without an explicit override here, calling this public method
   * through the Spring proxy would still open an ambient read-only transaction for this method's
   * entire duration (the class-level annotation applies to every public method unless overridden),
   * and {@link #requiresNewTransactionTemplate} below would then need a <em>second</em>,
   * independent connection for its own transaction - two connections held by one caller at once,
   * the same class of bug #299 fixed in {@code UserService.findOrCreateUser} and {@code
   * SpaceService#ensureDefaultSpace} (see that method's Javadoc for the identical pattern this one
   * mirrors). {@code NOT_SUPPORTED} suspends any ambient transaction for this method's duration and
   * leaves only the one connection each retry attempt below actually needs.
   *
   * <p><b>Retries on a {@code sequence} collision</b> (#525 review round 2, finding/nit 2): {@link
   * #nextSequenceFor} is a plain {@code COUNT(*)}, not a locking read - two turns appended to the
   * same chat at nearly the same instant (e.g. a user double-submitting, or two browser tabs on the
   * same chat) can both compute the same next sequence and race to insert it, and {@code
   * uk_chat_messages_chat_sequence} (migration 032) then rejects the loser with a {@link
   * DataIntegrityViolationException} - after its answer had already been generated by the LLM call
   * in {@code QueryService#query}, so simply failing the request would discard a real answer over a
   * retriable persistence collision. Each attempt runs in its own fresh {@code REQUIRES_NEW}
   * transaction (via {@link #requiresNewTransactionTemplate}, never a plain {@code @Transactional}
   * on this method, precisely so a failed attempt's rollback cannot poison a subsequent one) and
   * recomputes the sequence from scratch, so a retry after losing the race simply picks the number
   * the winner just took.
   *
   * <p><b>#557 - asynchronous LLM title generation.</b> Once the retry loop above has committed the
   * turn and the synchronous prefix-derived fallback title, this method triggers {@link
   * ChatTitleGenerationService#generateTitleAsync} - but only when this turn was the chat's very
   * first ({@code nextSequence == 0} on the attempt that finally succeeded) and a fresh read (see
   * below) confirms the title is still {@link TitleSource#GENERATED} <em>right now</em>, not merely
   * trusted from the possibly seconds-stale {@code chat} parameter (#561 review, finding 1/2 - see
   * {@link ChatRepository}'s Javadoc on its {@code @Modifying} methods for the full staleness
   * story). The trigger happens after the retry loop, i.e. after the writing transaction has
   * already committed - {@code @Async} dispatches to a genuinely separate thread with no ambient
   * transaction of its own regardless of where it is called from, but calling it only once the turn
   * is durably persisted keeps the ordering easy to reason about.
   *
   * <p><b>Return value</b> (#561 review, finding 2): the chat's title exactly as this method itself
   * committed it - {@code chat.getTitle()} would not do, since {@code appendTurnOnce} below no
   * longer mutates {@code chat} in place (see its Javadoc); a plain, read-only {@code findById}
   * after the write is the only way to see what was actually written, and carries none of the
   * merge-clobber risk a write via that same read would.
   *
   * @return the chat's current title after this turn, or {@code null} if it no longer exists
   */
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public String appendTurn(
      Chat chat, String question, String answer, List<SourceReference> sources) {
    // #613 review, finding 2: an archived space accepts no new content - including a new turn in
    // an existing chat, or the space could keep gaining fresh content forever and never actually
    // empty out into a state deleteSpace would accept. Checked here rather than only earlier in
    // QueryService#query so every appendTurn caller is covered, present and future - the cost is
    // that by this point QueryService has already spent an LLM call on the now-discarded answer;
    // restructuring the call order to check before generation is outside this fix's scope.
    requireSpaceNotArchived(chat.getSpaceId());
    boolean firstTurn = false;
    for (int attempt = 1; attempt <= APPEND_TURN_MAX_ATTEMPTS; attempt++) {
      try {
        firstTurn =
            requiresNewTransactionTemplate.execute(
                status -> appendTurnOnce(chat.getId(), question, answer, sources));
        break;
      } catch (DataIntegrityViolationException e) {
        if (attempt == APPEND_TURN_MAX_ATTEMPTS) {
          throw e;
        }
        log.warn(
            "appendTurn: sequence collision on chat {} (attempt {}/{}), retrying",
            chat.getId(),
            attempt,
            APPEND_TURN_MAX_ATTEMPTS,
            e);
      }
    }
    Chat current = chatRepository.findById(chat.getId()).orElse(null);
    if (firstTurn && current != null && current.getTitleSource() == TitleSource.GENERATED) {
      chatTitleGenerationService.generateTitleAsync(chat.getId(), question, answer);
    }
    return current != null ? current.getTitle() : null;
  }

  /**
   * @return true if this turn was the chat's very first ({@code nextSequence == 0})
   */
  private boolean appendTurnOnce(
      UUID chatId, String question, String answer, List<SourceReference> sources) {
    int nextSequence = nextSequenceFor(chatId);
    chatMessageRepository.save(
        new ChatMessage(chatId, nextSequence, ChatRole.USER, question, null));
    chatMessageRepository.save(
        new ChatMessage(
            chatId, nextSequence + 1, ChatRole.ASSISTANT, answer, serializeSources(sources)));
    // #561 review, finding 2: atomic, targeted UPDATEs (see ChatRepository's Javadoc) instead of a
    // full-entity merge save() of the Chat instance QueryService#query loaded before retrieval and
    // LLM answer generation - that would write back a stale title/title_source/every-other-column
    // snapshot and clobber a concurrent PATCH rename landing in between.
    chatRepository.deriveTitleFromFirstQuestionIfAbsent(chatId, deriveTitle(question));
    // #525 review, finding/nit d: bumps updated_at even when the title update above was a no-op
    // (title already set) - without it, the chat list's "sorted by last use" ordering
    // (findBySpaceIdAndAuthorIdOrderByUpdatedAtDesc) goes stale after the first turn.
    chatRepository.touch(chatId, Instant.now());
    return nextSequence == 0;
  }

  private int nextSequenceFor(UUID chatId) {
    return chatMessageRepository.countByChatId(chatId);
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
   * #613 review, finding 2: the shared guard {@link #updateChat} and {@link #appendTurn} both use -
   * a chat's space always exists (fk_chats_space_organization is ON DELETE RESTRICT, migration 032,
   * composite as of migration 047), so this never needs to reason about a missing space the way
   * {@link #requireMembership} does.
   */
  private void requireSpaceNotArchived(UUID spaceId) {
    Space space =
        spaceRepository
            .findById(spaceId)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Space nicht gefunden"));
    if (space.isArchived()) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Der Space ist archiviert und lässt keine neuen Inhalte mehr zu");
    }
  }

  /**
   * Rejects any id in {@code referencedLibraryIds} the caller may not read - including one that
   * does not exist at all, with the identical message for both cases (#525 review, finding/nit b):
   * distinguishing "not readable" from "does not exist" would let a caller probe for library ids
   * they have no rights on, and a bare foreign-key violation from {@code chat_library_references}
   * would otherwise surface as an opaque 500 instead of a 400.
   *
   * <p>#677 (migration 048, PR #680 review, finding 3): {@code chat_library_references} now also
   * carries organization_id, backed by a BEFORE INSERT trigger and enforced via composite foreign
   * keys - see {@link Chat#getReferencedLibraryIds()}'s Javadoc. That layer is unreachable from
   * this method: the chat itself is always persisted in the same transaction before this check's
   * result is used, so chat_id is never dangling here, and {@code readableLibraryIds} is already
   * scoped to {@code organizationId}, so a library from another organization never reaches this far
   * to begin with. The trigger's {@code SELECT ... INTO STRICT} does change which exception class a
   * raw SQL insert with a nonexistent chat_id would hit (plpgsql {@code NO_DATA_FOUND}, SQLState
   * P0002, instead of the composite foreign key's SQLState 23503) - a case this method never
   * produces.
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
