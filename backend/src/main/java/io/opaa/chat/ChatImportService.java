package io.opaa.chat;

import io.opaa.api.types.ChatRole;
import io.opaa.common.AccessDeniedException;
import io.opaa.common.ConflictException;
import io.opaa.common.NotFoundException;
import io.opaa.common.ValidationException;
import io.opaa.indexing.source.SourceConnectorRegistry;
import io.opaa.knowledge.Document;
import io.opaa.knowledge.DocumentRepository;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.KnowledgeLibraryRepository;
import io.opaa.knowledge.LibraryAccessService;
import io.opaa.metadata.CitationFieldValue;
import io.opaa.metadata.CitationMetadataReader;
import io.opaa.metadata.CoreMetadata;
import io.opaa.metadata.DocumentMetadataService;
import io.opaa.space.Space;
import io.opaa.space.SpaceAccessPolicy;
import io.opaa.space.SpaceRepository;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
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
 * read, every citation marker must name a source of its own turn, and what a source shows is read
 * from that document exactly as a generated answer reads it, never taken from the transcript. Each
 * chat carries the caller's import key, by which {@link #listImportedChats} finds it again.
 * Reachable over HTTP only while {@code opaa.demo.chat-import.enabled} is set.
 */
@Service
public class ChatImportService {

  static final String UNREADABLE_DOCUMENT =
      "Ein Beleg verweist auf ein Dokument, das nicht lesbar ist";

  static final String UNMATCHED_MARKER =
      "Eine Fundstellenmarke verweist auf kein Dokument der Belege ihrer Runde oder nennt einen"
          + " anderen Dateinamen oder Abschnitt";

  private static final String MARKER_OPENING = "【source";

  private final ChatRepository chatRepository;
  private final ChatMessageRepository chatMessageRepository;
  private final ChatPersonalMarkRepository chatPersonalMarkRepository;
  private final SpaceRepository spaceRepository;
  private final SpaceAccessPolicy spaceAccessPolicy;
  private final LibraryAccessService libraryAccessService;
  private final DocumentRepository documentRepository;
  private final KnowledgeLibraryRepository libraryRepository;
  private final SourceConnectorRegistry connectors;
  private final DocumentMetadataService documentMetadataService;
  private final CitationMetadataReader citationMetadataReader;
  private final ObjectMapper objectMapper;
  private final Clock clock;

  public ChatImportService(
      ChatRepository chatRepository,
      ChatMessageRepository chatMessageRepository,
      ChatPersonalMarkRepository chatPersonalMarkRepository,
      SpaceRepository spaceRepository,
      SpaceAccessPolicy spaceAccessPolicy,
      LibraryAccessService libraryAccessService,
      DocumentRepository documentRepository,
      KnowledgeLibraryRepository libraryRepository,
      SourceConnectorRegistry connectors,
      DocumentMetadataService documentMetadataService,
      CitationMetadataReader citationMetadataReader,
      ObjectMapper objectMapper,
      Clock clock) {
    this.chatRepository = chatRepository;
    this.chatMessageRepository = chatMessageRepository;
    this.chatPersonalMarkRepository = chatPersonalMarkRepository;
    this.spaceRepository = spaceRepository;
    this.spaceAccessPolicy = spaceAccessPolicy;
    this.libraryAccessService = libraryAccessService;
    this.documentRepository = documentRepository;
    this.libraryRepository = libraryRepository;
    this.connectors = connectors;
    this.documentMetadataService = documentMetadataService;
    this.citationMetadataReader = citationMetadataReader;
    this.objectMapper = objectMapper;
    this.clock = clock;
  }

  /**
   * Creates the chat with the transcript's instants: it was created when its first question was
   * asked and last used when its last answer came. All or nothing - one transaction. A key the
   * author already used in this space is a conflict.
   *
   * @return the new chat's id
   */
  @Transactional
  public UUID importChat(UUID spaceId, UUID authorId, ChatImport transcript) {
    Space space = requireMembership(spaceId, authorId);
    if (space.isArchived()) {
      throw new ConflictException("Der Space ist archiviert und lässt keine neuen Chats mehr zu");
    }
    List<ChatImport.Turn> turns = transcript.turns();
    requireText(transcript);
    if (chatRepository.existsBySpaceIdAndAuthorIdAndImportKey(
        spaceId, authorId, transcript.importKey())) {
      throw new ConflictException(
          "Ein Chat mit diesem Importschlüssel besteht in diesem Space bereits");
    }
    requireChronologicalAndPast(turns);
    Map<UUID, Document> documents = readableDocuments(turns, authorId, space.getOrganizationId());
    requireMarkersOfOwnSources(turns, documents);
    Map<UUID, CoreMetadata> coreMetadata =
        documentMetadataService.coreMetadataFor(documents.keySet());
    Map<UUID, List<CitationFieldValue>> citationFields =
        citationMetadataReader.forDocuments(documents.values());
    Set<UUID> privateLibraries = privateLibrariesOf(documents.values());

    Chat chat =
        new Chat(spaceId, authorId, space.getOrganizationId(), transcript.title(), true, Set.of());
    chat.markImported(transcript.importKey());
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
              serialize(sourcesOf(turn, documents, coreMetadata, citationFields, privateLibraries)),
              turn.answeredAt()));
    }
    return chatId;
  }

  /**
   * The author's imported chats of the space under their import keys, active and archived, with the
   * author's own marks - whatever their current title.
   */
  @Transactional(readOnly = true)
  public List<ImportedChatEntry> listImportedChats(UUID spaceId, UUID authorId) {
    requireMembership(spaceId, authorId);
    Map<UUID, ChatPersonalMark> marks =
        chatPersonalMarkRepository.findOwnMarksInSpace(spaceId, authorId).stream()
            .collect(Collectors.toMap(ChatPersonalMark::getChatId, Function.identity()));
    return chatRepository.findBySpaceIdAndAuthorIdAndImportKeyNotNull(spaceId, authorId).stream()
        .map(
            chat -> {
              ChatPersonalMark mark = marks.get(chat.getId());
              return new ImportedChatEntry(
                  chat.getImportKey(),
                  new ChatListEntry(
                      chat,
                      mark == null ? null : mark.getPinnedAt(),
                      mark == null ? null : mark.getArchivedAt()));
            })
        .toList();
  }

  private Space requireMembership(UUID spaceId, UUID authorId) {
    Space space =
        spaceRepository
            .findById(spaceId)
            .orElseThrow(() -> new NotFoundException("Space nicht gefunden"));
    if (!spaceAccessPolicy.isMember(spaceId, authorId)) {
      throw new AccessDeniedException("Sie sind kein Mitglied dieses Space");
    }
    return space;
  }

  /**
   * Every citation marker of an answer names a source of its own turn, one of that document's
   * sections and its file name, compared as the answer pipeline compares it (NFC, case-insensitive)
   * - so a marker can neither add a row to the Belegfenster nor attach itself to another source by
   * its name. Text that still opens a marker once every parsed marker is gone is one this parser
   * does not read but the Belegfenster's might, and is refused as well.
   */
  private static void requireMarkersOfOwnSources(
      List<ChatImport.Turn> turns, Map<UUID, Document> documents) {
    for (ChatImport.Turn turn : turns) {
      Map<String, Document> sourceById = new HashMap<>();
      for (ChatImport.Source source : turn.sources()) {
        Document document = documents.get(source.documentId());
        sourceById.put(document.getId().toString(), document);
      }
      for (CitationMarker marker : CitationMarker.parse(turn.answer())) {
        Document document = sourceById.get(marker.documentId());
        boolean matches =
            document != null
                && marker.chunkIndex() >= 0
                && marker.chunkIndex() < document.getChunkCount()
                && normalizeFileName(document.getFileName())
                    .equals(normalizeFileName(marker.fileName()));
        if (!matches) {
          throw new ValidationException(UNMATCHED_MARKER);
        }
      }
      String unparsed = CitationMarker.PATTERN.matcher(turn.answer()).replaceAll("");
      if (unparsed.contains(MARKER_OPENING)) {
        throw new ValidationException(UNMATCHED_MARKER);
      }
    }
  }

  private static String normalizeFileName(String fileName) {
    return fileName == null
        ? ""
        : Normalizer.normalize(fileName.strip(), Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
  }

  private static void requireText(ChatImport transcript) {
    boolean blank =
        isBlank(transcript.importKey())
            || isBlank(transcript.title())
            || transcript.turns().stream()
                .anyMatch(turn -> isBlank(turn.question()) || isBlank(turn.answer()));
    if (blank) {
      throw new ValidationException(
          "Importschlüssel, Titel, Fragen und Antworten dürfen nicht leer sein");
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
      ChatImport.Turn turn,
      Map<UUID, Document> documents,
      Map<UUID, CoreMetadata> coreMetadata,
      Map<UUID, List<CitationFieldValue>> citationFields,
      Set<UUID> privateLibraries) {
    List<ChatSource> sources = new ArrayList<>();
    for (ChatImport.Source source : turn.sources()) {
      Document document = documents.get(source.documentId());
      List<ChatSourceMetadataEntry> metadata =
          ChatSourceMetadataEntry.from(
              coreMetadata.getOrDefault(document.getId(), CoreMetadata.EMPTY),
              citationFields.getOrDefault(document.getId(), List.of()));
      sources.add(
          new ChatSource(document.getFileName(), 1.0 / (sources.size() + 1), 1, source.cited())
              .indexedAt(document.getIndexedAt())
              .documentId(document.getId())
              .sourceType(document.getSourceType() == null ? null : document.getSourceType().key())
              .sourceUrl(connectors.deepLink(document))
              .sourceEntryUrl(document.getSourceEntryUrl())
              .citationValid(true)
              .metadata(metadata.isEmpty() ? null : metadata)
              .privateSource(privateLibraries.contains(document.getLibraryId())));
    }
    return sources;
  }

  /** The private libraries among those of {@code documents}. */
  private Set<UUID> privateLibrariesOf(Collection<Document> documents) {
    Set<UUID> libraryIds =
        documents.stream()
            .map(Document::getLibraryId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
    if (libraryIds.isEmpty()) {
      return Set.of();
    }
    return libraryRepository.findAllById(libraryIds).stream()
        .filter(KnowledgeLibrary::isOwnerOnly)
        .map(KnowledgeLibrary::getId)
        .collect(Collectors.toSet());
  }

  private String serialize(List<ChatSource> sources) {
    return sources.isEmpty() ? null : objectMapper.writeValueAsString(sources);
  }
}
