package io.opaa.chat;

import io.opaa.api.types.ChatRole;
import io.opaa.common.AccessDeniedException;
import io.opaa.common.ConflictException;
import io.opaa.common.NotFoundException;
import io.opaa.common.ValidationException;
import io.opaa.indexing.metadata.MetadataFilter;
import io.opaa.indexing.metadata.MetadataFilterValidator;
import io.opaa.library.LibraryAccessService;
import io.opaa.space.Space;
import io.opaa.space.SpaceAssetAssociationRepository;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Chats as persistent, space-owned objects (#525, docs/features/spaces-and-assets.md#chats).
 * Chatting requires space membership; a chat itself is visible only to its author - not even a
 * space or system admin, see {@link ChatRepository#findByIdAndAuthorId}'s Javadoc, which every
 * chat-scoped read/write in this class goes through.
 *
 * <p><b>#860 Teil 4 (DTO-Leak):</b> every public method below takes and returns domain types only -
 * {@link Chat} itself, the enriched {@link ChatConversation}/{@link ChatTurn} read views, and the
 * {@link ChatCreation}/{@link ChatPatch} parameter records - never a generated {@code
 * io.opaa.api.dto} type. {@code ChatController} converts to/from the generated request/response
 * DTOs via {@code ChatResponseMapper} in {@code io.opaa.api}, mirroring the convention {@code
 * SpaceService} established (AGENTS.md, "API & DTO-Konvention"). This also keeps this class usable
 * by {@link io.opaa.query.QueryService}, which needs the {@link Chat} entity itself and the {@link
 * ChatSource}/{@link ChatSourceLocation} domain shapes, never a response DTO.
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
 *
 * <p>No class-level {@code @Transactional} (#889): every reading method carries its own explicit
 * {@code @Transactional(readOnly = true)}. {@link #appendTurn} carries {@link
 * Propagation#NOT_SUPPORTED} - not because a class-level default needs overriding anymore, but to
 * structurally guarantee its retry loop never runs inside a caller's ambient transaction (the
 * #299/#525 two-connections deadlock), rather than depending on every future caller's good
 * behaviour. Only the isolated per-attempt write in {@link ChatMessageWriter#writeTurnOnce}
 * actually opens a transaction.
 */
@Service
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
  private final ChatMessageWriter chatMessageWriter;
  private final ChatTitleGenerationService chatTitleGenerationService;
  private final MetadataFilterValidator metadataFilterValidator;

  public ChatService(
      ChatRepository chatRepository,
      ChatMessageRepository chatMessageRepository,
      SpaceRepository spaceRepository,
      SpaceMembershipRepository spaceMembershipRepository,
      SpaceAssetAssociationRepository spaceAssetAssociationRepository,
      LibraryAccessService libraryAccessService,
      ObjectMapper objectMapper,
      ChatMessageWriter chatMessageWriter,
      ChatTitleGenerationService chatTitleGenerationService,
      MetadataFilterValidator metadataFilterValidator) {
    this.metadataFilterValidator = metadataFilterValidator;
    this.chatRepository = chatRepository;
    this.chatMessageRepository = chatMessageRepository;
    this.spaceRepository = spaceRepository;
    this.spaceMembershipRepository = spaceMembershipRepository;
    this.spaceAssetAssociationRepository = spaceAssetAssociationRepository;
    this.libraryAccessService = libraryAccessService;
    this.objectMapper = objectMapper;
    this.chatMessageWriter = chatMessageWriter;
    this.chatTitleGenerationService = chatTitleGenerationService;
  }

  @Transactional
  public ChatConversation createChat(UUID spaceId, UUID authorId, ChatCreation creation) {
    Space space = requireMembership(spaceId, authorId);
    // #543: an archived space accepts no new content - see docs/features/spaces-and-assets.md#
    // einen-space-stilllegen-archivieren-statt-löschen.
    if (space.isArchived()) {
      throw new ConflictException("Der Space ist archiviert und lässt keine neuen Chats mehr zu");
    }
    Boolean useKnowledge = creation.getUseKnowledge();
    String title = creation.getTitle();
    Set<UUID> referencedLibraryIds =
        creation.getReferencedLibraryIds() == null
            ? Set.of()
            : new LinkedHashSet<>(creation.getReferencedLibraryIds());
    requireReadableLibraries(referencedLibraryIds, authorId, space.getOrganizationId());

    Chat chat =
        new Chat(
            spaceId,
            authorId,
            space.getOrganizationId(),
            title,
            useKnowledge == null || useKnowledge,
            referencedLibraryIds);
    if (creation.getMetadataFilter() != null) {
      chat.applyMetadataFilter(
          validatedMetadataFilter(
              creation.getMetadataFilter(), authorId, space.getOrganizationId()));
    }
    Chat saved = chatRepository.save(chat);
    return toConversation(saved);
  }

  /**
   * A chat's filter is checked against the schema when it is set, not when it is applied - a code
   * no document can carry would otherwise sit silently on every later question. A library field is
   * resolved within the author's readable libraries only ({@link MetadataFilterValidator}).
   */
  private MetadataFilter validatedMetadataFilter(
      MetadataFilter filter, UUID authorId, UUID organizationId) {
    return metadataFilterValidator.validate(
        filter, libraryAccessService.readableLibraryIds(authorId, organizationId));
  }

  @Transactional(readOnly = true)
  public List<Chat> listChats(UUID spaceId, UUID authorId) {
    requireMembership(spaceId, authorId);
    return chatRepository.findBySpaceIdAndAuthorIdOrderByUpdatedAtDesc(spaceId, authorId);
  }

  @Transactional(readOnly = true)
  public ChatConversation getChat(UUID chatId, UUID authorId) {
    return toConversation(getOwnedChat(chatId, authorId));
  }

  @Transactional
  public ChatConversation updateChat(UUID chatId, UUID authorId, ChatPatch patch) {
    Chat chat = getOwnedChat(chatId, authorId);
    // #613 review, finding 2: an archived space accepts no new content - not only no new chats,
    // but also no renaming, useKnowledge toggling or reference changes on an existing one. The
    // chat itself stays readable (getChat/deleteChat are deliberately not gated here - reading and
    // withdrawing are not "new content"), just frozen.
    requireSpaceNotArchived(chat.getSpaceId());
    Set<UUID> referencedLibraryIds =
        patch.getReferencedLibraryIds() == null
            ? null
            : new LinkedHashSet<>(patch.getReferencedLibraryIds());
    if (referencedLibraryIds != null) {
      requireReadableLibraries(referencedLibraryIds, authorId, chat.getOrganizationId());
    }
    MetadataFilter metadataFilter =
        patch.getMetadataFilter() == null
            ? null
            : validatedMetadataFilter(
                patch.getMetadataFilter(), authorId, chat.getOrganizationId());
    chat.applyUpdate(
        patch.getTitle(), patch.getUseKnowledge(), referencedLibraryIds, metadataFilter);
    return toConversation(chatRepository.save(chat));
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
        .orElseThrow(() -> new NotFoundException("Chat nicht gefunden"));
  }

  /**
   * Same lookup as {@link #getOwnedChat}, but returning empty instead of throwing - used by {@code
   * QueryService#query} for the optional {@code chatId} on a query request, where "no such owned
   * chat" and "no chatId given" are handled identically (an ephemeral, unpersisted query).
   */
  @Transactional(readOnly = true)
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
  @Transactional(readOnly = true)
  public void requireStillSpaceMember(Chat chat) {
    boolean member =
        spaceMembershipRepository
            .findByUserIdAndSpaceId(chat.getAuthorId(), chat.getSpaceId())
            .isPresent();
    if (!member) {
      throw new AccessDeniedException("Sie sind kein Mitglied dieses Space mehr");
    }
  }

  /**
   * The persisted history as Spring AI messages, ordered by the application-assigned {@code
   * sequence} (see {@link ChatMessage}'s Javadoc), not {@code created_at}.
   */
  @Transactional(readOnly = true)
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
  @Transactional(readOnly = true)
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
   * Whether {@code spaceId} has at least one library association (#706 review) - used by {@code
   * QueryService} to distinguish, in {@link #effectiveLibraryScope}'s @Alles-Wissen branch, the
   * ordinary "no association at all" case (falls back to every readable library) from the fail-open
   * case a curated-but-nothing-readable space produces: an empty {@link #effectiveLibraryScope}
   * result together with {@code true} here means "curated, but nothing the caller may read", not
   * "no curation configured" - the two need different frontend messages
   * (docs/features/spaces-and-assets.md#suchbereich-je-chatart, "In diesem Space ist für dich
   * derzeit kein Wissen verfügbar").
   */
  @Transactional(readOnly = true)
  public boolean spaceHasLibraryAssociations(UUID spaceId) {
    return !spaceAssetAssociationRepository.findLibraryIdsBySpaceId(spaceId).isEmpty();
  }

  /**
   * Persists one question/answer turn and, if the chat never had a title set explicitly, derives
   * one from the question (#525's "Titel-Default aus der ersten Frage ableiten"). Called from
   * {@code QueryService#query} after generating the answer - the caller has already verified {@code
   * chat} belongs to the requesting user via {@link #findOwnedChat}. This is the write phase of
   * that pipeline (#889): {@link Propagation#NOT_SUPPORTED} suspends any ambient transaction of the
   * caller for this method's whole duration - {@code QueryService#query}'s read phase and LLM call
   * that precede it already run with none of their own, but this annotation makes that a structural
   * guarantee rather than one this method's behaviour merely depends on. Only {@link
   * ChatMessageWriter#writeTurnOnce}, called per retry attempt below, actually opens a transaction.
   *
   * <p><b>Retries on a {@code sequence} collision</b> (#525 review round 2, finding/nit 2; #889:
   * the sequence is now {@code MAX(sequence) + 1}, not a row count, so it tolerates a gap left by a
   * deleted message - see {@code ChatMessageWriter#nextSequenceFor}): the sequence lookup is not a
   * locking read - two turns appended to the same chat at nearly the same instant (e.g. a user
   * double-submitting, or two browser tabs on the same chat) can both compute the same next
   * sequence and race to insert it, and {@code uk_chat_messages_chat_sequence} (migration 032) then
   * rejects the loser with a {@link DataIntegrityViolationException} - after its answer had already
   * been generated by the LLM call in {@code QueryService#query}, so simply failing the request
   * would discard a real answer over a retriable persistence collision. Each attempt calls {@link
   * ChatMessageWriter#writeTurnOnce} fresh, isolated in its own transaction, and recomputes the
   * sequence from scratch, so a retry after losing the race simply picks the number the winner just
   * took.
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
   * committed it - {@code chat.getTitle()} would not do, since {@link
   * ChatMessageWriter#writeTurnOnce} no longer mutates {@code chat} in place (see its Javadoc); a
   * plain, read-only {@code findById} after the write is the only way to see what was actually
   * written, and carries none of the merge-clobber risk a write via that same read would.
   *
   * @return the chat's current title after this turn, or {@code null} if it no longer exists
   */
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public String appendTurn(Chat chat, String question, String answer, List<ChatSource> sources) {
    // #613 review, finding 2 / #840: an archived space accepts no new content - including a new
    // turn in an existing chat, or the space could keep gaining fresh content forever and never
    // actually empty out into a state deleteSpace would accept. The early check now lives in
    // QueryService#query, before retrieval/the LLM call (see requireSpaceNotArchived's Javadoc);
    // this call here is the race guard for the window between that early check and this method's
    // write - a space archived in between still must not be able to add a turn.
    requireSpaceNotArchived(chat.getSpaceId());
    String serializedSources = serializeSources(sources);
    String derivedTitle = deriveTitle(question);
    boolean firstTurn = false;
    for (int attempt = 1; attempt <= APPEND_TURN_MAX_ATTEMPTS; attempt++) {
      try {
        firstTurn =
            chatMessageWriter.writeTurnOnce(
                chat.getId(), question, answer, serializedSources, derivedTitle);
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

  private ChatConversation toConversation(Chat chat) {
    List<ChatTurn> messages =
        chatMessageRepository.findByChatIdOrderBySequenceAsc(chat.getId()).stream()
            .map(this::toTurn)
            .toList();
    return new ChatConversation(chat, messages);
  }

  private ChatTurn toTurn(ChatMessage message) {
    return new ChatTurn(
        message.getId(),
        message.getChatId(),
        message.getRole(),
        message.getContent(),
        parseSources(message.getSources()),
        message.getCreatedAt());
  }

  private List<ChatSource> parseSources(String sourcesJson) {
    if (sourcesJson == null || sourcesJson.isBlank()) {
      return null;
    }
    try {
      return objectMapper.readValue(sourcesJson, new TypeReference<List<ChatSource>>() {});
    } catch (JacksonException e) {
      log.warn("Failed to parse persisted chat message sources, treating as absent", e);
      return null;
    }
  }

  private String serializeSources(List<ChatSource> sources) {
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
            .orElseThrow(() -> new NotFoundException("Space nicht gefunden"));
    boolean member = spaceMembershipRepository.findByUserIdAndSpaceId(userId, spaceId).isPresent();
    if (!member) {
      throw new AccessDeniedException("Sie sind kein Mitglied dieses Space");
    }
    return space;
  }

  /**
   * #613 review, finding 2: the shared guard {@link #updateChat} and {@link #appendTurn} both use -
   * a chat's space always exists (fk_chats_space_organization is ON DELETE RESTRICT, migration 032,
   * composite as of migration 047), so this never needs to reason about a missing space the way
   * {@link #requireMembership} does.
   *
   * <p>#840: also called from {@code QueryService#query}, before retrieval/the LLM call, for a
   * persisted chat - so the ordinary case ("space was already archived") never pays for an LLM call
   * whose answer {@link #appendTurn} would discard anyway. Widened to public for that caller rather
   * than duplicating the rule; {@link #appendTurn}'s own call stays in place as the race guard for
   * a space archived after this early check ran but before the turn was persisted (see that
   * method's Javadoc).
   */
  @Transactional(readOnly = true)
  public void requireSpaceNotArchived(UUID spaceId) {
    Space space =
        spaceRepository
            .findById(spaceId)
            .orElseThrow(() -> new NotFoundException("Space nicht gefunden"));
    if (space.isArchived()) {
      throw new ConflictException("Der Space ist archiviert und lässt keine neuen Inhalte mehr zu");
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
      throw new ValidationException(
          "referencedLibraryIds enthält eine Bibliothek, die nicht lesbar ist");
    }
  }
}
