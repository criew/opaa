package io.opaa.chat;

import io.opaa.api.types.ChatRole;
import io.opaa.common.AccessDeniedException;
import io.opaa.common.ConflictException;
import io.opaa.common.NotFoundException;
import io.opaa.common.ValidationException;
import io.opaa.knowledge.LibraryAccessService;
import io.opaa.metadata.MetadataFilter;
import io.opaa.metadata.MetadataFilterValidator;
import io.opaa.observability.ChatMetrics;
import io.opaa.space.ChatAutoCleanupProperties;
import io.opaa.space.Space;
import io.opaa.space.SpaceAccessPolicy;
import io.opaa.space.SpaceAssetAssociationRepository;
import io.opaa.space.SpaceAssetAssociationService;
import io.opaa.space.SpaceRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Chats as persistent, space-owned objects (docs/features/spaces-and-assets.md#chats). A chat is
 * visible only to its author, never to an admin ({@link ChatRepository#findByIdAndAuthorId}); the
 * author keeps reading, renaming, exporting and deleting it after leaving the space, but querying
 * needs membership ({@link #requireStillSpaceMember}). Public methods take and return domain types
 * only. {@link #appendTurn} runs without a transaction; only {@link
 * ChatMessageWriter#writeTurnOnce} opens one per attempt, so no caller's transaction holds a second
 * connection.
 */
@Service
public class ChatService {

  private static final Logger log = LoggerFactory.getLogger(ChatService.class);
  private static final int DERIVED_TITLE_MAX_LENGTH = 80;

  static final int SEARCH_MIN_TERM_LENGTH = 3;
  static final int SEARCH_MAX_TERM_LENGTH = 200;
  static final int SEARCH_DEFAULT_PAGE_SIZE = 20;
  static final int SEARCH_MAX_PAGE_SIZE = 50;

  /**
   * Upper bound on {@link #appendTurn}'s retry loop - see that method's Javadoc. Three is generous
   * for a two-row insert colliding on a per-chat sequence: the realistic contention is "one other
   * concurrent question in the very same chat", never a herd.
   */
  private static final int APPEND_TURN_MAX_ATTEMPTS = 3;

  private final ChatRepository chatRepository;
  private final ChatMessageRepository chatMessageRepository;
  private final SpaceRepository spaceRepository;
  private final SpaceAccessPolicy spaceAccessPolicy;
  private final SpaceAssetAssociationRepository spaceAssetAssociationRepository;
  private final SpaceAssetAssociationService spaceAssetAssociationService;
  private final LibraryAccessService libraryAccessService;
  private final ObjectMapper objectMapper;
  private final ChatMessageWriter chatMessageWriter;
  private final ChatTitleGenerationService chatTitleGenerationService;
  private final MetadataFilterValidator metadataFilterValidator;
  private final ChatNoteService chatNoteService;
  private final ChatPersonalMarkRepository chatPersonalMarkRepository;
  private final ChatFullTextSearch chatFullTextSearch;
  private final ChatMetrics chatMetrics;
  private final ChatAutoCleanupProperties chatAutoCleanup;
  private final Clock clock;

  public ChatService(
      ChatRepository chatRepository,
      ChatMessageRepository chatMessageRepository,
      SpaceRepository spaceRepository,
      SpaceAccessPolicy spaceAccessPolicy,
      SpaceAssetAssociationRepository spaceAssetAssociationRepository,
      SpaceAssetAssociationService spaceAssetAssociationService,
      LibraryAccessService libraryAccessService,
      ObjectMapper objectMapper,
      ChatMessageWriter chatMessageWriter,
      ChatTitleGenerationService chatTitleGenerationService,
      MetadataFilterValidator metadataFilterValidator,
      ChatNoteService chatNoteService,
      ChatPersonalMarkRepository chatPersonalMarkRepository,
      ChatFullTextSearch chatFullTextSearch,
      ChatMetrics chatMetrics,
      ChatAutoCleanupProperties chatAutoCleanup,
      Clock clock) {
    this.chatAutoCleanup = chatAutoCleanup;
    this.chatPersonalMarkRepository = chatPersonalMarkRepository;
    this.chatFullTextSearch = chatFullTextSearch;
    this.chatMetrics = chatMetrics;
    this.metadataFilterValidator = metadataFilterValidator;
    this.chatNoteService = chatNoteService;
    this.chatRepository = chatRepository;
    this.chatMessageRepository = chatMessageRepository;
    this.spaceRepository = spaceRepository;
    this.spaceAccessPolicy = spaceAccessPolicy;
    this.spaceAssetAssociationRepository = spaceAssetAssociationRepository;
    this.spaceAssetAssociationService = spaceAssetAssociationService;
    this.libraryAccessService = libraryAccessService;
    this.objectMapper = objectMapper;
    this.chatMessageWriter = chatMessageWriter;
    this.chatTitleGenerationService = chatTitleGenerationService;
    this.clock = clock;
  }

  @Transactional
  public ChatConversation createChat(UUID spaceId, UUID authorId, ChatCreation creation) {
    Space space = requireMembership(spaceId, authorId);
    // An archived space accepts no new content - see docs/features/spaces-and-assets.md#
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
    requireUsableReferences(referencedLibraryIds, spaceId, authorId, space.getOrganizationId());

    Chat chat =
        new Chat(
            spaceId,
            authorId,
            space.getOrganizationId(),
            title,
            useKnowledge == null || useKnowledge,
            referencedLibraryIds);
    MetadataFilter metadataFilter =
        creation.getMetadataFilter() == null
            ? null
            : validatedMetadataFilter(
                creation.getMetadataFilter(), authorId, space.getOrganizationId());
    if (metadataFilter != null) {
      chat.applyMetadataFilter(metadataFilter);
    }
    Chat saved = chatRepository.save(chat);
    return toConversation(saved);
  }

  /**
   * A chat's filter is checked against the schema when it is set, not when it is applied - a code
   * no document can carry would otherwise sit silently on every later question. A library field is
   * resolved within the author's readable libraries only ({@link MetadataFilterValidator}). {@code
   * null} with the metadata feature off: the chat's stored filter then stays as it is.
   */
  private MetadataFilter validatedMetadataFilter(
      MetadataFilter filter, UUID authorId, UUID organizationId) {
    return metadataFilterValidator.validateToStore(
        filter, libraryAccessService.readableLibraryIds(authorId, organizationId));
  }

  /**
   * The caller's active chats in the space - those not in their chat archive - most recently active
   * first, each with the caller's marks.
   */
  @Transactional(readOnly = true)
  public List<ChatListEntry> listChats(UUID spaceId, UUID authorId) {
    requireMembership(spaceId, authorId);
    Map<UUID, Instant> pinnedAtByChat = new HashMap<>();
    for (ChatPersonalMark mark :
        chatPersonalMarkRepository.findOwnMarksInSpace(spaceId, authorId)) {
      if (mark.getPinnedAt() != null) {
        pinnedAtByChat.put(mark.getChatId(), mark.getPinnedAt());
      }
    }
    return chatRepository.findActiveInSpace(spaceId, authorId).stream()
        .map(chat -> new ChatListEntry(chat, pinnedAtByChat.get(chat.getId()), null))
        .toList();
  }

  /** The caller's chat archive of one space, most recently archived first. */
  @Transactional(readOnly = true)
  public Page<ChatListEntry> listArchivedChats(UUID spaceId, UUID userId, Pageable pageable) {
    Space space = requireMembership(spaceId, userId);
    return chatPersonalMarkRepository
        .findArchivedInSpace(spaceId, userId, pageable)
        .map(
            entry ->
                new ChatListEntry(
                    entry.chat(),
                    entry.pinnedAt(),
                    entry.archivedAt(),
                    chatAutoCleanup.deletionDueAt(
                        entry.archivedAt(), space.getChatAutoCleanupEnabledAt())));
  }

  /**
   * Searches the titles, questions and answers of the chats the caller may see in the space,
   * archived ones included (docs/features/chat-list.md, "Chatsuche"). Membership is required as for
   * {@link #listChats}; no role widens the search beyond the caller's own chats. The term goes into
   * the query and nowhere else - no log line, no audit entry, no metric tag.
   *
   * @param page zero-based; {@code null} is the first page
   * @param pageSize {@code null} is {@link #SEARCH_DEFAULT_PAGE_SIZE}; above {@link
   *     #SEARCH_MAX_PAGE_SIZE} it is reduced to it
   */
  @Transactional(readOnly = true)
  public ChatSearchPage searchChats(
      UUID spaceId, UUID userId, String term, Integer page, Integer pageSize) {
    requireMembership(spaceId, userId);
    String normalizedTerm = requireSearchTerm(term);
    int effectivePage = page == null ? 0 : page;
    if (effectivePage < 0) {
      throw new ValidationException("Die Seitennummer darf nicht negativ sein");
    }
    int effectivePageSize = pageSize == null ? SEARCH_DEFAULT_PAGE_SIZE : pageSize;
    if (effectivePageSize < 1) {
      throw new ValidationException("Die Seitengröße muss mindestens 1 betragen");
    }
    int boundedPageSize = Math.min(effectivePageSize, SEARCH_MAX_PAGE_SIZE);
    return chatMetrics
        .searchTimer()
        .record(
            () ->
                chatFullTextSearch.search(
                    spaceId, userId, normalizedTerm, effectivePage, boundedPageSize));
  }

  /**
   * The stripped term with every control character read as a blank - PostgreSQL text holds no NUL -
   * or a 400 if it is shorter or longer than the search accepts.
   */
  private static String requireSearchTerm(String term) {
    String stripped =
        term == null
            ? ""
            : term.codePoints()
                .map(c -> Character.isISOControl(c) ? ' ' : c)
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                .toString()
                .strip();
    int length = stripped.codePointCount(0, stripped.length());
    if (length < SEARCH_MIN_TERM_LENGTH) {
      throw new ValidationException(
          "Der Suchbegriff muss mindestens " + SEARCH_MIN_TERM_LENGTH + " Zeichen lang sein");
    }
    if (length > SEARCH_MAX_TERM_LENGTH) {
      throw new ValidationException(
          "Der Suchbegriff darf höchstens " + SEARCH_MAX_TERM_LENGTH + " Zeichen lang sein");
    }
    return stripped;
  }

  /**
   * Pins the chat for the caller - a personal mark, not a change to the chat, so it leaves {@code
   * updatedAt} untouched and is allowed in an archived space. A chat the caller cannot see is
   * reported as not found.
   */
  @Transactional
  public ChatListEntry pinChat(UUID chatId, UUID userId) {
    Chat chat = getOwnedChat(chatId, userId);
    chatPersonalMarkRepository.pin(chatId, userId, Instant.now(clock));
    return new ChatListEntry(
        chat, chatPersonalMarkRepository.findPinnedAt(chatId, userId).orElseThrow(), null);
  }

  /**
   * Counterpart of {@link #pinChat}, under the same rules; unpinning an unpinned chat is a no-op.
   */
  @Transactional
  public void unpinChat(UUID chatId, UUID userId) {
    getOwnedChat(chatId, userId);
    chatPersonalMarkRepository.clearPin(chatId, userId);
    chatPersonalMarkRepository.deleteIfUnmarked(chatId, userId);
  }

  /**
   * Moves the chat into the caller's chat archive (docs/features/chat-list.md, "Archivieren") and
   * unpins it. Like pinning a personal mark under the same rules: the chat itself stays untouched,
   * an archived space allows it, and a chat the caller cannot see is reported as not found.
   */
  @Transactional
  public ChatListEntry archiveChat(UUID chatId, UUID userId) {
    Chat chat = getOwnedChat(chatId, userId);
    chatPersonalMarkRepository.archive(chatId, userId, Instant.now(clock));
    Instant archivedAt = chatPersonalMarkRepository.findArchivedAt(chatId, userId).orElseThrow();
    return new ChatListEntry(chat, null, archivedAt, deletionDueAt(chat, archivedAt));
  }

  /** Counterpart of {@link #archiveChat}; the chat comes back unpinned. Idempotent. */
  @Transactional
  public ChatListEntry unarchiveChat(UUID chatId, UUID userId) {
    Chat chat = getOwnedChat(chatId, userId);
    clearArchive(chat, userId, Instant.now(clock));
    return new ChatListEntry(chat, null, null);
  }

  /**
   * {@link #archiveChat} for the caller's own chats of one space among {@code chatIds}; every other
   * id is skipped without distinction.
   *
   * @return the ids the action was applied to
   */
  @Transactional
  public List<UUID> archiveChats(UUID spaceId, UUID userId, Collection<UUID> chatIds) {
    requireMembership(spaceId, userId);
    Instant now = Instant.now(clock);
    List<Chat> chats = chatRepository.findByIdInAndSpaceIdAndAuthorId(chatIds, spaceId, userId);
    chats.forEach(chat -> chatPersonalMarkRepository.archive(chat.getId(), userId, now));
    return chats.stream().map(Chat::getId).toList();
  }

  /** {@link #unarchiveChat} under the rules of {@link #archiveChats}. */
  @Transactional
  public List<UUID> unarchiveChats(UUID spaceId, UUID userId, Collection<UUID> chatIds) {
    requireMembership(spaceId, userId);
    List<Chat> chats = chatRepository.findByIdInAndSpaceIdAndAuthorId(chatIds, spaceId, userId);
    Instant now = Instant.now(clock);
    chats.forEach(chat -> clearArchive(chat, userId, now));
    return chats.stream().map(Chat::getId).toList();
  }

  /** {@link #deleteChat} under the rules of {@link #archiveChats}. */
  @Transactional
  public List<UUID> deleteChats(UUID spaceId, UUID userId, Collection<UUID> chatIds) {
    requireMembership(spaceId, userId);
    List<Chat> chats = chatRepository.findByIdInAndSpaceIdAndAuthorId(chatIds, spaceId, userId);
    chatRepository.deleteAll(chats);
    return chats.stream().map(Chat::getId).toList();
  }

  /**
   * Brings the chat back from the person's archive. In a space with automatic chat cleanup the
   * moment is kept, so the archive period starts anew; elsewhere nothing of it is stored.
   */
  private void clearArchive(Chat chat, UUID userId, Instant now) {
    boolean cleanupOn =
        spaceRepository
            .findById(chat.getSpaceId())
            .map(Space::isChatAutoCleanupEnabled)
            .orElse(false);
    if (cleanupOn) {
      chatPersonalMarkRepository.clearArchiveRecordingReturn(chat.getId(), userId, now);
    } else {
      chatPersonalMarkRepository.clearArchive(chat.getId(), userId);
    }
    chatPersonalMarkRepository.deleteIfUnmarked(chat.getId(), userId);
  }

  @Transactional(readOnly = true)
  public ChatConversation getChat(UUID chatId, UUID authorId) {
    return toConversation(getOwnedChat(chatId, authorId), authorId);
  }

  @Transactional
  public ChatConversation updateChat(UUID chatId, UUID authorId, ChatPatch patch) {
    Chat chat = getOwnedChat(chatId, authorId);
    // An archived space accepts no new content - not only no new chats, but also no renaming,
    // useKnowledge toggling or reference changes on an existing one. The chat itself stays readable
    // (getChat/deleteChat are deliberately not gated here - reading and withdrawing are not "new
    // content"), just frozen.
    requireSpaceNotArchived(chat.getSpaceId());
    Set<UUID> referencedLibraryIds =
        patch.getReferencedLibraryIds() == null
            ? null
            : new LinkedHashSet<>(patch.getReferencedLibraryIds());
    if (referencedLibraryIds != null) {
      // Only what this change adds is checked: a stored reference that has since lost its
      // association or its read right stays harmless, effectiveLibraryScope leaves it out.
      Set<UUID> added = new LinkedHashSet<>(referencedLibraryIds);
      added.removeAll(chat.getReferencedLibraryIds());
      requireUsableReferences(added, chat.getSpaceId(), authorId, chat.getOrganizationId());
    }
    MetadataFilter metadataFilter =
        patch.getMetadataFilter() == null
            ? null
            : validatedMetadataFilter(
                patch.getMetadataFilter(), authorId, chat.getOrganizationId());
    chat.applyUpdate(
        patch.getTitle(), patch.getUseKnowledge(), referencedLibraryIds, metadataFilter);
    return toConversation(chatRepository.save(chat), authorId);
  }

  @Transactional
  public void deleteChat(UUID chatId, UUID authorId) {
    chatRepository.delete(getOwnedChat(chatId, authorId));
  }

  /**
   * Loads a chat the caller authored, or throws 404 - deliberately the same status for "no such
   * chat" and "exists but belongs to someone else" (a foreign user, including a space or system
   * admin, gets 404, not 403, which would confirm the chat's existence).
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
   * - and with {@link #getChat} the export of a chat ({@link ChatExportService}) - deliberately do
   * not.
   */
  @Transactional(readOnly = true)
  public void requireStillSpaceMember(Chat chat) {
    if (!spaceAccessPolicy.isMember(chat.getSpaceId(), chat.getAuthorId())) {
      throw new AccessDeniedException("Sie sind kein Mitglied dieses Space mehr");
    }
  }

  /**
   * The last {@code maxMessages} persisted messages as Spring AI messages, in turn order - ordered
   * by the application-assigned {@code sequence} (see {@link ChatMessage}'s Javadoc), not {@code
   * created_at}. The content is the persisted text verbatim; the conversation window's own
   * normalization happens at its entrance, in {@code QueryService}.
   */
  @Transactional(readOnly = true)
  public List<Message> historyAsSpringAiMessages(UUID chatId, int maxMessages) {
    if (maxMessages <= 0) {
      return List.of();
    }
    List<ChatMessage> newestFirst =
        chatMessageRepository.findByChatIdOrderBySequenceDesc(chatId, Limit.of(maxMessages));
    return newestFirst.reversed().stream()
        .<Message>map(
            m ->
                m.getRole() == ChatRole.USER
                    ? new UserMessage(m.getContent())
                    : new AssistantMessage(m.getContent()))
        .toList();
  }

  /**
   * The search scope for a query run in this chat: the space's associated knowledge libraries are a
   * hard boundary. With {@code useKnowledge} on (@Space-Wissen) the scope is associated ∩ readable;
   * with it off, the sticky @-references ∩ associated ∩ readable. A space without an associated
   * library searches nothing - there is no fallback to the readable set.
   */
  @Transactional(readOnly = true)
  public Set<UUID> effectiveLibraryScope(Chat chat, Set<UUID> readableLibraryIds) {
    Set<UUID> scoped =
        new LinkedHashSet<>(
            spaceAssetAssociationRepository.findLibraryIdsBySpaceId(chat.getSpaceId()));
    if (!chat.isUseKnowledge()) {
      scoped.retainAll(chat.getReferencedLibraryIds());
    }
    scoped.retainAll(readableLibraryIds);
    return scoped;
  }

  /**
   * The scope the first question of a chat not yet created in {@code spaceId} would search - the
   * same rule as {@link #effectiveLibraryScope}, applied to the chip bar's settings. The user must
   * be a member of the space.
   */
  @Transactional(readOnly = true)
  public Set<UUID> draftLibraryScope(
      UUID spaceId,
      UUID userId,
      boolean useKnowledge,
      Collection<UUID> referencedLibraryIds,
      Set<UUID> readableLibraryIds) {
    requireMembership(spaceId, userId);
    Set<UUID> scoped =
        new LinkedHashSet<>(spaceAssetAssociationRepository.findLibraryIdsBySpaceId(spaceId));
    if (!useKnowledge) {
      scoped.retainAll(referencedLibraryIds);
    }
    scoped.retainAll(readableLibraryIds);
    return scoped;
  }

  /**
   * Whether {@code spaceId} has a knowledge library associated as a caller who reads {@code
   * readableLibraryIds} sees it, readable or not - another person's private library does not count,
   * as in the space's content list. An empty {@link #effectiveLibraryScope} means "nothing
   * associated" when this is false and "associated, but nothing the caller may read" when it is
   * true - two separate signals.
   */
  @Transactional(readOnly = true)
  public boolean spaceHasLibraryAssociations(
      UUID spaceId, Set<UUID> readableLibraryIds, UUID organizationId) {
    return spaceAssetAssociationService.hasKnowledgeFor(
        spaceId, readableLibraryIds, organizationId);
  }

  /**
   * Persists one question/answer turn of a chat the caller owns, deriving a title from the question
   * while none was set explicitly. A {@code uk_chat_messages_chat_sequence} collision is retried
   * with a fresh sequence, so a generated answer is never discarded. After the first turn committed
   * and a fresh read still shows {@link TitleSource#GENERATED}, {@link
   * ChatTitleGenerationService#generateTitleAsync} is triggered.
   *
   * @return the chat's current title after this turn, or {@code null} if it no longer exists
   */
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public String appendTurn(Chat chat, String question, String answer, List<ChatSource> sources) {
    return appendTurn(chat, question, null, answer, sources);
  }

  /**
   * {@link #appendTurn(Chat, String, String, List)} with the prompt the question was built from,
   * kept as a snapshot on the question's message; {@code null} for none. The caller has checked
   * that the author may read the prompt.
   */
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public String appendTurn(
      Chat chat, String question, UsedPrompt usedPrompt, String answer, List<ChatSource> sources) {
    // An archived space accepts no new turn; QueryService#query checks this early, this call
    // guards a space archived in between.
    requireSpaceNotArchived(chat.getSpaceId());
    String serializedSources = serializeSources(sources);
    String derivedTitle = deriveTitle(question);
    boolean firstTurn = false;
    for (int attempt = 1; attempt <= APPEND_TURN_MAX_ATTEMPTS; attempt++) {
      try {
        // Only the author can ask in a chat (QueryService goes through findOwnedChat), so the
        // author is the sender whose chat archive this turn empties.
        firstTurn =
            chatMessageWriter.writeTurnOnce(
                chat.getId(),
                chat.getAuthorId(),
                question,
                usedPrompt,
                answer,
                serializedSources,
                derivedTitle);
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
   * Removes one point of a chat's Gesprächsnotiz - author only, like every other chat-scoped
   * operation, and refused while the chat's space is archived, like every other change to an
   * existing chat. A point of another chat, or of a chat the caller does not author, is a 404
   * either way: the same lookup that hides a foreign chat hides its note.
   */
  @Transactional
  public void deleteNoteItem(UUID chatId, UUID itemId, UUID authorId) {
    Chat chat = getOwnedChat(chatId, authorId);
    requireSpaceNotArchived(chat.getSpaceId());
    if (!chatNoteService.deletePoint(chatId, itemId)) {
      throw new NotFoundException("Notizpunkt nicht gefunden");
    }
  }

  private ChatConversation toConversation(Chat chat) {
    List<ChatTurn> messages =
        chatMessageRepository.findByChatIdOrderBySequenceAsc(chat.getId()).stream()
            .map(this::toTurn)
            .toList();
    return new ChatConversation(chat, messages, chatNoteService.points(chat.getId()));
  }

  /** {@link #toConversation(Chat)} carrying the requesting person's own archive mark. */
  private ChatConversation toConversation(Chat chat, UUID userId) {
    ChatConversation conversation = toConversation(chat);
    Instant archivedAt =
        chatPersonalMarkRepository.findArchivedAt(chat.getId(), userId).orElse(null);
    return new ChatConversation(
        chat,
        conversation.getMessages(),
        conversation.getNoteItems(),
        archivedAt,
        deletionDueAt(chat, archivedAt));
  }

  /** When the automatic chat cleanup deletes the chat archived at {@code archivedAt}, if ever. */
  private Instant deletionDueAt(Chat chat, Instant archivedAt) {
    if (archivedAt == null) {
      return null;
    }
    return spaceRepository
        .findById(chat.getSpaceId())
        .map(
            space -> chatAutoCleanup.deletionDueAt(archivedAt, space.getChatAutoCleanupEnabledAt()))
        .orElse(null);
  }

  private ChatTurn toTurn(ChatMessage message) {
    return new ChatTurn(
        message.getId(),
        message.getChatId(),
        message.getRole(),
        message.getContent(),
        parseSources(message.getSources()),
        message.getUsedPrompt(),
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
    if (!spaceAccessPolicy.isMember(spaceId, userId)) {
      throw new AccessDeniedException("Sie sind kein Mitglied dieses Space");
    }
    return space;
  }

  /**
   * The current instruction of the space to the model, {@code null} while it has none. Read anew
   * for every question, so a change reaches chats created before it from their next question on.
   */
  @Transactional(readOnly = true)
  public String spaceModelInstruction(UUID spaceId) {
    return spaceRepository.findById(spaceId).map(Space::getModelInstruction).orElse(null);
  }

  /**
   * Rejects new content in an archived space (409). {@code QueryService#query} calls it before the
   * model call, so an archived space never costs one.
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
   * Rejects (400) an id in {@code referencedLibraryIds} that the caller may not read or that does
   * not exist, with one message for both, so no foreign id can be probed. Only then is a readable
   * library outside the space's associations rejected with its own message.
   */
  private void requireUsableReferences(
      Set<UUID> referencedLibraryIds, UUID spaceId, UUID authorId, UUID organizationId) {
    if (referencedLibraryIds.isEmpty()) {
      return;
    }
    Set<UUID> readable = libraryAccessService.readableLibraryIds(authorId, organizationId);
    if (!readable.containsAll(referencedLibraryIds)) {
      throw new ValidationException(
          "referencedLibraryIds enthält eine Bibliothek, die nicht lesbar ist");
    }
    if (!spaceAssetAssociationRepository
        .findLibraryIdsBySpaceId(spaceId)
        .containsAll(referencedLibraryIds)) {
      throw new ValidationException(
          "referencedLibraryIds enthält eine Bibliothek, die diesem Space nicht zugeordnet ist");
    }
  }
}
