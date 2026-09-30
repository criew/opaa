package io.opaa.chat;

import io.opaa.api.types.ChatRole;
import io.opaa.common.AccessDeniedException;
import io.opaa.common.ConflictException;
import io.opaa.common.NotFoundException;
import io.opaa.common.ValidationException;
import io.opaa.indexing.source.SourceConnectorRegistry;
import io.opaa.knowledge.Document;
import io.opaa.knowledge.DocumentRepository;
import io.opaa.knowledge.LibraryAccessService;
import io.opaa.metadata.CoreMetadata;
import io.opaa.metadata.DocumentMetadataService;
import io.opaa.space.Space;
import io.opaa.space.SpaceAccessPolicy;
import io.opaa.space.SpaceRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Writes a prepared transcript as one chat of its author, without any model call - the demo seed's
 * way to a space with many realistic chats. The caller is the author and needs the same space
 * membership as for {@link ChatService#createChat}; every source must be a document the author may
 * read, and what a source shows is read from that document, never taken from the transcript.
 * Reachable over HTTP only while {@code opaa.demo.chat-import.enabled} is set.
 */
@Service
public class ChatImportService {

  static final String UNREADABLE_DOCUMENT =
      "Ein Beleg verweist auf ein Dokument, das nicht lesbar ist";

  private final ChatRepository chatRepository;
  private final ChatMessageRepository chatMessageRepository;
  private final SpaceRepository spaceRepository;
  private final SpaceAccessPolicy spaceAccessPolicy;
  private final LibraryAccessService libraryAccessService;
  private final DocumentRepository documentRepository;
  private final SourceConnectorRegistry connectors;
  private final DocumentMetadataService documentMetadataService;
  private final ObjectMapper objectMapper;
  private final Clock clock;

  public ChatImportService(
      ChatRepository chatRepository,
      ChatMessageRepository chatMessageRepository,
      SpaceRepository spaceRepository,
      SpaceAccessPolicy spaceAccessPolicy,
      LibraryAccessService libraryAccessService,
      DocumentRepository documentRepository,
      SourceConnectorRegistry connectors,
      DocumentMetadataService documentMetadataService,
      ObjectMapper objectMapper,
      Clock clock) {
    this.chatRepository = chatRepository;
    this.chatMessageRepository = chatMessageRepository;
    this.spaceRepository = spaceRepository;
    this.spaceAccessPolicy = spaceAccessPolicy;
    this.libraryAccessService = libraryAccessService;
    this.documentRepository = documentRepository;
    this.connectors = connectors;
    this.documentMetadataService = documentMetadataService;
    this.objectMapper = objectMapper;
    this.clock = clock;
  }

  /**
   * Creates the chat with the transcript's instants: it was created when its first question was
   * asked and last used when its last answer came. All or nothing - one transaction.
   *
   * @return the new chat's id
   */
  @Transactional
  public UUID importChat(UUID spaceId, UUID authorId, ChatImport transcript) {
    Space space =
        spaceRepository
            .findById(spaceId)
            .orElseThrow(() -> new NotFoundException("Space nicht gefunden"));
    if (!spaceAccessPolicy.isMember(spaceId, authorId)) {
      throw new AccessDeniedException("Sie sind kein Mitglied dieses Space");
    }
    if (space.isArchived()) {
      throw new ConflictException("Der Space ist archiviert und lässt keine neuen Chats mehr zu");
    }
    List<ChatImport.Turn> turns = transcript.turns();
    requireText(transcript);
    requireChronologicalAndPast(turns);
    Map<UUID, Document> documents = readableDocuments(turns, authorId, space.getOrganizationId());
    Map<UUID, CoreMetadata> coreMetadata =
        documentMetadataService.coreMetadataFor(documents.keySet());

    Chat chat =
        new Chat(spaceId, authorId, space.getOrganizationId(), transcript.title(), true, Set.of());
    chat.backdate(turns.getFirst().askedAt(), turns.getLast().answeredAt());
    UUID chatId = chatRepository.save(chat).getId();
    int sequence = 0;
    for (ChatImport.Turn turn : turns) {
      chatMessageRepository.save(
          ChatMessage.imported(
              chatId, sequence++, ChatRole.USER, turn.question(), null, turn.askedAt()));
      chatMessageRepository.save(
          ChatMessage.imported(
              chatId,
              sequence++,
              ChatRole.ASSISTANT,
              turn.answer(),
              serialize(sourcesOf(turn, documents, coreMetadata)),
              turn.answeredAt()));
    }
    return chatId;
  }

  private static void requireText(ChatImport transcript) {
    boolean blank =
        isBlank(transcript.title())
            || transcript.turns().stream()
                .anyMatch(turn -> isBlank(turn.question()) || isBlank(turn.answer()));
    if (blank) {
      throw new ValidationException("Titel, Fragen und Antworten dürfen nicht leer sein");
    }
  }

  private static boolean isBlank(String text) {
    return text == null || text.isBlank();
  }

  private void requireChronologicalAndPast(List<ChatImport.Turn> turns) {
    if (turns.isEmpty()) {
      throw new ValidationException("Ein Verlauf braucht mindestens eine Runde");
    }
    Instant previous = Instant.MIN;
    for (ChatImport.Turn turn : turns) {
      if (turn.askedAt().isBefore(previous) || turn.answeredAt().isBefore(turn.askedAt())) {
        throw new ValidationException("Die Zeitpunkte des Verlaufs müssen aufsteigend sein");
      }
      previous = turn.answeredAt();
    }
    if (previous.isAfter(clock.instant())) {
      throw new ValidationException("Ein Zeitpunkt des Verlaufs liegt in der Zukunft");
    }
  }

  /**
   * The documents every source names, each within a library the author may read in the chat's
   * organization - one message for an unknown and an unreadable document, so the import cannot
   * probe for ids.
   */
  private Map<UUID, Document> readableDocuments(
      List<ChatImport.Turn> turns, UUID authorId, UUID organizationId) {
    Set<UUID> ids = new LinkedHashSet<>();
    turns.forEach(turn -> turn.sources().forEach(source -> ids.add(source.documentId())));
    if (ids.isEmpty()) {
      return Map.of();
    }
    Set<UUID> readableLibraries = libraryAccessService.readableLibraryIds(authorId, organizationId);
    Map<UUID, Document> found =
        documentRepository.findAllById(ids).stream()
            .filter(document -> organizationId.equals(document.getOrganizationId()))
            .filter(document -> readableLibraries.contains(document.getLibraryId()))
            .collect(Collectors.toMap(Document::getId, Function.identity()));
    if (!found.keySet().containsAll(ids)) {
      throw new ValidationException(UNREADABLE_DOCUMENT);
    }
    return found;
  }

  private List<ChatSource> sourcesOf(
      ChatImport.Turn turn, Map<UUID, Document> documents, Map<UUID, CoreMetadata> coreMetadata) {
    List<ChatSource> sources = new ArrayList<>();
    for (ChatImport.Source source : turn.sources()) {
      Document document = documents.get(source.documentId());
      List<ChatSourceMetadataEntry> metadata =
          ChatSourceMetadataEntry.from(
              coreMetadata.getOrDefault(document.getId(), CoreMetadata.EMPTY), List.of());
      sources.add(
          new ChatSource(document.getFileName(), 1.0 / (sources.size() + 1), 1, source.cited())
              .indexedAt(document.getIndexedAt())
              .documentId(document.getId())
              .sourceType(document.getSourceType() == null ? null : document.getSourceType().key())
              .sourceUrl(connectors.deepLink(document))
              .sourceEntryUrl(document.getSourceEntryUrl())
              .citationValid(true)
              .metadata(metadata.isEmpty() ? null : metadata));
    }
    return sources;
  }

  private String serialize(List<ChatSource> sources) {
    return sources.isEmpty() ? null : objectMapper.writeValueAsString(sources);
  }
}
