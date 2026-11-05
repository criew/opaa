package io.opaa.chat;

import io.opaa.api.types.ChatAttachmentMode;
import io.opaa.api.types.ChatAttachmentStatus;
import io.opaa.auth.CurrentUser;
import io.opaa.common.ConflictException;
import io.opaa.common.Feature;
import io.opaa.common.FeatureFlags;
import io.opaa.common.NotFoundException;
import io.opaa.common.PayloadTooLargeException;
import io.opaa.common.ServiceUnavailableException;
import io.opaa.common.TooManyRequestsException;
import io.opaa.common.ValidationException;
import io.opaa.format.DocumentFormatRegistry;
import io.opaa.format.DocumentFormatResult;
import io.opaa.format.DocumentFormatRunner;
import io.opaa.format.DocumentFormatSource;
import io.opaa.format.SupportedDocumentFormats;
import io.opaa.knowledge.DocumentContent;
import io.opaa.knowledge.MalwareScanService;
import io.opaa.knowledge.UploadProperties;
import io.opaa.knowledge.UploadedOriginalRef;
import io.opaa.knowledge.UploadedOriginalStore;
import io.opaa.library.LibraryDocumentEntry;
import io.opaa.library.LibraryDocumentService;
import io.opaa.space.Space;
import io.opaa.space.SpaceAccessPolicy;
import io.opaa.space.SpaceRepository;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * Documents attached to a chat question (docs/handbuch/benutzer/anhaengen-und-diktieren.md). An
 * attachment is checked like a library upload, its original goes into the upload storage ({@link
 * UploadedOriginalStore#CHAT_ATTACHMENT_AREA}) and its text is read on {@code
 * chatAttachmentExecutor} - parsed and chunked, never embedded, so it is usable seconds after the
 * drop. It belongs to its author alone: every lookup goes through the author, and neither space nor
 * system administration reaches it.
 *
 * <p>Its life: unbound after the upload, bound to a chat with the first question that names it
 * ({@link ChatMessageWriter}), deleted with that chat by {@code ON DELETE CASCADE}. One never bound
 * is deleted after {@code opaa.chat.attachments.unbound-retention}; originals whose row is gone are
 * removed by {@link ChatAttachmentCleanup}.
 */
@Service
public class ChatAttachmentService {

  private static final Logger log = LoggerFactory.getLogger(ChatAttachmentService.class);

  static final String NOT_FOUND_MESSAGE = "Anhang nicht gefunden";

  static final String NO_TEXT_MESSAGE =
      "Die Datei enthält keinen lesbaren Text - ein eingescanntes Dokument ohne Texterkennung"
          + " lässt sich nicht anhängen.";

  static final String EMPTY_MESSAGE = "Die Datei ist leer.";

  static final String UNREADABLE_MESSAGE = "Die Datei konnte nicht gelesen werden.";

  static final String QUEUE_FULL_MESSAGE =
      "Das Lesen von Anhängen ist gerade ausgelastet - bitte in wenigen Sekunden erneut versuchen.";

  private final ChatAttachmentRepository attachmentRepository;
  private final ChatAttachmentChunkStore chunkStore;
  private final ChatRepository chatRepository;
  private final SpaceRepository spaceRepository;
  private final SpaceAccessPolicy spaceAccessPolicy;
  private final UploadedOriginalStore originalStore;
  private final UploadProperties uploadProperties;
  private final SupportedDocumentFormats supportedFormats;
  private final DocumentFormatRegistry formatRegistry;
  private final MalwareScanService malwareScanService;
  private final ChatAttachmentReadingSlots readingSlots;
  private final ChatAttachmentProperties properties;
  private final TaskExecutor executor;
  private final FeatureFlags featureFlags;
  private final LibraryDocumentService libraryDocumentService;
  private final Clock clock;

  public ChatAttachmentService(
      ChatAttachmentRepository attachmentRepository,
      ChatAttachmentChunkStore chunkStore,
      ChatRepository chatRepository,
      SpaceRepository spaceRepository,
      SpaceAccessPolicy spaceAccessPolicy,
      UploadedOriginalStore originalStore,
      UploadProperties uploadProperties,
      SupportedDocumentFormats supportedFormats,
      DocumentFormatRegistry formatRegistry,
      MalwareScanService malwareScanService,
      ChatAttachmentReadingSlots readingSlots,
      ChatAttachmentProperties properties,
      @Qualifier("chatAttachmentExecutor") TaskExecutor executor,
      FeatureFlags featureFlags,
      LibraryDocumentService libraryDocumentService,
      Clock clock) {
    this.attachmentRepository = attachmentRepository;
    this.chunkStore = chunkStore;
    this.chatRepository = chatRepository;
    this.spaceRepository = spaceRepository;
    this.spaceAccessPolicy = spaceAccessPolicy;
    this.originalStore = originalStore;
    this.uploadProperties = uploadProperties;
    this.supportedFormats = supportedFormats;
    this.formatRegistry = formatRegistry;
    this.malwareScanService = malwareScanService;
    this.readingSlots = readingSlots;
    this.properties = properties;
    this.executor = executor;
    this.featureFlags = featureFlags;
    this.libraryDocumentService = libraryDocumentService;
    this.clock = clock;
  }

  /**
   * Takes {@code file} as an attachment of {@code caller} in {@code spaceId} and waits up to {@code
   * opaa.chat.attachments.read-wait} for its text. Every check that rejects the file runs before
   * anything stays behind: membership (404 like an unknown space), archive (409), size (413),
   * format and content (400), malware (400, or 503 when the scanner could not judge it), the
   * reading slots of the account and the pool (429).
   */
  public ChatAttachmentView upload(UUID spaceId, MultipartFile file, CurrentUser caller) {
    return upload(spaceId, file, caller.id());
  }

  /**
   * Takes {@code content}, a file a tool of the agent loop produced for {@code authorId} in {@code
   * chatId}, as an unbound attachment of that person in {@code spaceId} - through every check of
   * {@link #upload(UUID, MultipartFile, CurrentUser)}, the file name included, which is untrusted.
   * {@code pendingInTurn} files of the same turn are not bound yet but count against {@code
   * opaa.chat.attachments.max-per-chat}, so the chat never ends up above it. With the feature
   * switched off, nothing is taken (400).
   */
  public ChatAttachmentView uploadGenerated(
      UUID spaceId,
      UUID chatId,
      int pendingInTurn,
      String fileName,
      byte[] content,
      UUID authorId) {
    if (!featureFlags.isEnabled(Feature.CHAT_UPLOAD)) {
      throw new ValidationException("Anhänge sind in dieser Installation abgeschaltet");
    }
    if (attachmentRepository.findByChatIdOrderByCreatedAtAsc(chatId).size() + pendingInTurn
        >= properties.maxPerChat()) {
      throw new ValidationException(
          "Der Chat hat bereits " + properties.maxPerChat() + " Anhänge, mehr nimmt er nicht auf");
    }
    Path temp = null;
    try {
      temp = Files.createTempFile("opaa-generated-", ".bin");
      Files.write(temp, content);
      return upload(
          spaceId,
          new StoredFileMultipartFile(temp, fileName, "application/octet-stream"),
          authorId);
    } catch (IOException e) {
      throw new UncheckedIOException("Erzeugte Datei konnte nicht gespeichert werden", e);
    } finally {
      if (temp != null) {
        try {
          Files.deleteIfExists(temp);
        } catch (IOException e) {
          log.warn("Temporary generated file {} could not be deleted", temp, e);
        }
      }
    }
  }

  private ChatAttachmentView upload(UUID spaceId, MultipartFile file, UUID authorId) {
    Space space = requireMembership(spaceId, authorId);
    if (space.isArchived()) {
      throw new ConflictException("Der Space ist archiviert und lässt keine neuen Inhalte mehr zu");
    }
    if (file == null || file.isEmpty()) {
      throw new ValidationException("Datei ist erforderlich");
    }
    if (file.getSize() > uploadProperties.maxFileSize()) {
      throw new PayloadTooLargeException(
          "Die Datei ist zu groß. Erlaubt sind höchstens "
              + (uploadProperties.maxFileSize() / (1024 * 1024))
              + " MB");
    }
    String fileName = LibraryDocumentService.sanitizeDisplayFileName(file.getOriginalFilename());
    if (fileName.isEmpty()) {
      throw new ValidationException("Der Dateiname darf nicht leer sein");
    }
    if (!supportedFormats.isSupported(fileName)) {
      throw new ValidationException(
          "Das Dateiformat wird nicht unterstützt. Erlaubt sind: "
              + String.join(", ", supportedFormats.extensions()));
    }
    String extension = matchedExtension(fileName);

    ChatAttachmentReadingSlots.Slot slot = readingSlots.reserve(authorId);
    boolean handedOver = false;
    UploadedOriginalStore.AcceptedUpload accepted = null;
    ChatAttachment saved = null;
    try {
      accepted = accept(space.getOrganizationId(), extension, file);
      Path workingFile = accepted.workingFile();
      requireContentMatchesExtension(workingFile, extension);
      requireNoMalware(workingFile, space.getOrganizationId());
      ChatAttachment attachment =
          new ChatAttachment(
              space.getOrganizationId(),
              spaceId,
              authorId,
              fileName,
              probeContentType(workingFile),
              Files.size(workingFile),
              Instant.now(clock));
      attachment.setOriginalLocator(accepted.store().locator());
      saved = attachmentRepository.save(attachment);

      UploadedOriginalStore.AcceptedUpload handOver = accepted;
      UUID attachmentId = saved.getId();
      CompletableFuture<Void> reading = new CompletableFuture<>();
      try {
        executor.execute(
            () -> {
              try {
                read(attachmentId, workingFile, fileName);
              } finally {
                try {
                  handOver.release();
                } finally {
                  slot.release();
                  reading.complete(null);
                }
              }
            });
        handedOver = true;
      } catch (TaskRejectedException e) {
        throw new TooManyRequestsException(
            QUEUE_FULL_MESSAGE, ChatAttachmentReadingSlots.RETRY_AFTER_SECONDS);
      }
      awaitReading(reading);
      return view(attachmentRepository.findById(attachmentId).orElse(saved));
    } catch (IOException e) {
      throw new UncheckedIOException("Datei konnte nicht gespeichert werden", e);
    } finally {
      if (!handedOver) {
        if (saved != null) {
          attachmentRepository.deleteById(saved.getId());
        }
        if (accepted != null) {
          accepted.discard();
        }
        slot.release();
      }
    }
  }

  /** One of the author's own attachments; a foreign or unknown id is a 404 alike. */
  @Transactional(readOnly = true)
  public ChatAttachmentView get(UUID attachmentId, UUID authorId) {
    return view(requireOwn(attachmentId, authorId));
  }

  /** The author's own attachments, newest first, each with the title of its chat. */
  @Transactional(readOnly = true)
  public List<ChatAttachmentView> listOwn(UUID authorId) {
    List<ChatAttachment> attachments =
        attachmentRepository.findByAuthorIdOrderByCreatedAtDesc(authorId);
    Set<UUID> chatIds = new LinkedHashSet<>();
    attachments.forEach(
        attachment -> {
          if (attachment.getChatId() != null) {
            chatIds.add(attachment.getChatId());
          }
        });
    Map<UUID, String> titles = new HashMap<>();
    chatRepository.findAllById(chatIds).forEach(chat -> titles.put(chat.getId(), chat.getTitle()));
    return attachments.stream()
        .map(attachment -> view(attachment, titles.get(attachment.getChatId())))
        .toList();
  }

  /**
   * Deletes one of the author's own attachments with its text and original. The original goes after
   * the row; one the store cannot remove right now stays for {@link ChatAttachmentCleanup}.
   */
  public void delete(UUID attachmentId, UUID authorId) {
    ChatAttachment attachment = requireOwn(attachmentId, authorId);
    attachmentRepository.delete(attachment);
    removeOriginal(attachment);
  }

  /** The original of one of the author's own attachments, for an inline response. */
  public DocumentContent content(UUID attachmentId, UUID authorId) {
    ChatAttachment attachment = requireOwn(attachmentId, authorId);
    return originalRef(attachment)
        .flatMap(
            ref ->
                originalStore.openForDownload(
                    ref, attachment.getFileName(), attachment.getContentType()))
        .orElseThrow(() -> new NotFoundException(NOT_FOUND_MESSAGE));
  }

  /**
   * Copies one of the author's own attachments into {@code libraryId} through the library upload,
   * which applies every check of its own (editor role, quota, format, malware, duplicates).
   */
  public LibraryDocumentEntry copyToLibrary(UUID attachmentId, UUID libraryId, CurrentUser caller) {
    ChatAttachment attachment = requireOwn(attachmentId, caller.id());
    UploadedOriginalRef ref =
        originalRef(attachment).orElseThrow(() -> new NotFoundException(NOT_FOUND_MESSAGE));
    return originalStore
        .withLocalFile(
            ref,
            file ->
                libraryDocumentService.uploadDocument(
                    libraryId,
                    new StoredFileMultipartFile(
                        file, attachment.getFileName(), attachment.getContentType()),
                    null,
                    caller))
        .orElseThrow(() -> new NotFoundException(NOT_FOUND_MESSAGE));
  }

  /**
   * The passages the attachments of {@code chat} contribute to an answer to {@code question}: the
   * chat's own ready attachments and {@code newAttachmentIds}, which this question names and which
   * must be the author's, of the chat's space, ready and not bound elsewhere (400 otherwise). With
   * the feature switched off, naming one is a 400 and the chat's existing ones are not read.
   */
  @Transactional(readOnly = true)
  public ChatAttachmentContext contextFor(Chat chat, List<UUID> newAttachmentIds, String question) {
    List<UUID> requested =
        newAttachmentIds == null ? List.of() : List.copyOf(new LinkedHashSet<>(newAttachmentIds));
    if (!featureFlags.isEnabled(Feature.CHAT_UPLOAD)) {
      if (!requested.isEmpty()) {
        throw new ValidationException("Anhänge sind in dieser Installation abgeschaltet");
      }
      return ChatAttachmentContext.NONE;
    }
    if (requested.size() > properties.maxPerMessage()) {
      throw new ValidationException(
          "Eine Frage kann höchstens " + properties.maxPerMessage() + " Anhänge mitnehmen");
    }
    List<ChatAttachment> bound = attachmentRepository.findByChatIdOrderByCreatedAtAsc(chat.getId());
    List<ChatAttachment> fresh = requireUsable(chat, requested);
    long newOnes = fresh.stream().filter(a -> a.getChatId() == null).count();
    if (bound.size() + newOnes > properties.maxPerChat()) {
      throw new ValidationException(
          "Ein Chat kann höchstens "
              + properties.maxPerChat()
              + " Anhänge haben - für mehr Dokumente bitte eine Bibliothek anlegen");
    }
    Map<UUID, ChatAttachment> ready = new LinkedHashMap<>();
    bound.stream().filter(ChatAttachment::isReady).forEach(a -> ready.put(a.getId(), a));
    fresh.forEach(a -> ready.put(a.getId(), a));
    if (ready.isEmpty()) {
      return ChatAttachmentContext.NONE;
    }
    List<ChatAttachment> attachments = new ArrayList<>(ready.values());
    ChatAttachmentPassages.Selection selection =
        ChatAttachmentPassages.select(
            attachments,
            chunkStore.load(ready.keySet()),
            question,
            properties.inlineMaxChars(),
            properties.excerptMaxChars());
    return new ChatAttachmentContext(
        selection.passages(), Set.copyOf(ready.keySet()), requested, selection.excerpts());
  }

  /** The attachments of {@code chatId} by the question they were sent with, for the chat view. */
  @Transactional(readOnly = true)
  public Map<UUID, List<ChatAttachment>> byMessage(UUID chatId) {
    if (!featureFlags.isEnabled(Feature.CHAT_UPLOAD)) {
      return Map.of();
    }
    Map<UUID, List<ChatAttachment>> byMessage = new HashMap<>();
    for (ChatAttachment attachment : attachmentRepository.findByChatIdOrderByCreatedAtAsc(chatId)) {
      if (attachment.getMessageId() != null) {
        byMessage
            .computeIfAbsent(attachment.getMessageId(), id -> new ArrayList<>())
            .add(attachment);
      }
    }
    return byMessage;
  }

  private List<ChatAttachment> requireUsable(Chat chat, List<UUID> requested) {
    if (requested.isEmpty()) {
      return List.of();
    }
    List<ChatAttachment> found =
        attachmentRepository.findByIdInAndAuthorId(requested, chat.getAuthorId());
    if (found.size() != requested.size()) {
      throw new ValidationException("Ein Anhang ist nicht mehr vorhanden");
    }
    Map<UUID, ChatAttachment> byId = new HashMap<>();
    found.forEach(a -> byId.put(a.getId(), a));
    List<ChatAttachment> ordered = new ArrayList<>();
    for (UUID id : requested) {
      ChatAttachment attachment = byId.get(id);
      if (!attachment.getSpaceId().equals(chat.getSpaceId())
          || (attachment.getChatId() != null && !attachment.getChatId().equals(chat.getId()))
          || attachment.getMessageId() != null) {
        throw new ValidationException(
            "Der Anhang „" + attachment.getFileName() + "“ gehört zu einem anderen Chat");
      }
      if (attachment.getStatus() == ChatAttachmentStatus.PROCESSING) {
        throw new ValidationException(
            "Der Anhang „" + attachment.getFileName() + "“ wird noch gelesen");
      }
      if (attachment.getStatus() == ChatAttachmentStatus.FAILED) {
        throw new ValidationException(
            "Der Anhang „"
                + attachment.getFileName()
                + "“ ist nicht nutzbar: "
                + attachment.getFailureReason());
      }
      ordered.add(attachment);
    }
    return ordered;
  }

  /**
   * Reads the text of {@code attachmentId} from {@code file}: the routed format pipeline, never an
   * embedding. Runs on {@code chatAttachmentExecutor}; every outcome ends the row's {@code
   * PROCESSING}, and a row deleted meanwhile is left alone.
   */
  void read(UUID attachmentId, Path file, String fileName) {
    try {
      DocumentFormatRegistry.Routed routed = formatRegistry.routedPipelineFor(file, fileName);
      DocumentFormatResult result =
          DocumentFormatRunner.run(
              routed.pipeline(),
              DocumentFormatSource.ofFile(file, fileName, routed.detectedExtension()));
      switch (result.outcome()) {
        case CHUNKED -> store(attachmentId, result.chunks());
        case NO_CONTENT -> markFailed(attachmentId, EMPTY_MESSAGE);
        case NO_EXTRACTABLE_TEXT -> markFailed(attachmentId, NO_TEXT_MESSAGE);
        case PARSE_FAILED -> markFailed(attachmentId, UNREADABLE_MESSAGE);
      }
    } catch (RuntimeException e) {
      log.warn("Reading chat attachment {} failed", attachmentId, e);
      markFailed(attachmentId, UNREADABLE_MESSAGE);
    }
  }

  private void store(UUID attachmentId, List<Document> chunks) {
    List<ChatAttachmentChunkStore.Chunk> rows = new ArrayList<>();
    int chars = 0;
    for (int index = 0; index < chunks.size(); index++) {
      Document chunk = chunks.get(index);
      String text = chunk.getText() == null ? "" : chunk.getText();
      chars += text.length();
      rows.add(new ChatAttachmentChunkStore.Chunk(attachmentId, index, text, chunk.getMetadata()));
    }
    if (!attachmentRepository.existsById(attachmentId)) {
      return;
    }
    try {
      chunkStore.save(attachmentId, rows);
    } catch (RuntimeException e) {
      // a row deleted meanwhile is not PROCESSING anymore, so marking it failed changes nothing
      if (attachmentRepository.existsById(attachmentId)) {
        log.warn("Storing the text of chat attachment {} failed", attachmentId, e);
      }
      markFailed(attachmentId, UNREADABLE_MESSAGE);
      return;
    }
    attachmentRepository.markReady(attachmentId, chars, Instant.now(clock));
  }

  private void markFailed(UUID attachmentId, String reason) {
    attachmentRepository.markFailed(attachmentId, reason, Instant.now(clock));
  }

  private void awaitReading(CompletableFuture<Void> reading) {
    try {
      reading.get(properties.readWait().toMillis(), TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      // answered PROCESSING; the client follows the reading with getChatAttachment
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (ExecutionException e) {
      log.warn("Reading a chat attachment ended with an error", e.getCause());
    }
  }

  ChatAttachmentView view(ChatAttachment attachment) {
    String title =
        attachment.getChatId() == null
            ? null
            : chatRepository.findById(attachment.getChatId()).map(Chat::getTitle).orElse(null);
    return view(attachment, title);
  }

  private ChatAttachmentView view(ChatAttachment attachment, String chatTitle) {
    ChatAttachmentMode mode = null;
    if (attachment.isReady() && attachment.getTextChars() != null) {
      mode =
          attachment.getTextChars() > properties.inlineMaxChars()
              ? ChatAttachmentMode.EXCERPTS
              : ChatAttachmentMode.FULL_TEXT;
    }
    Instant deletionDueAt =
        attachment.getChatId() == null
            ? attachment.getCreatedAt().plus(properties.unboundRetention())
            : null;
    return new ChatAttachmentView(attachment, chatTitle, mode, deletionDueAt);
  }

  private ChatAttachment requireOwn(UUID attachmentId, UUID authorId) {
    return attachmentRepository
        .findByIdAndAuthorId(attachmentId, authorId)
        .orElseThrow(() -> new NotFoundException(NOT_FOUND_MESSAGE));
  }

  void removeOriginal(ChatAttachment attachment) {
    originalRef(attachment)
        .ifPresent(
            ref -> {
              try {
                originalStore.delete(ref);
              } catch (RuntimeException e) {
                log.warn(
                    "Could not remove the original of chat attachment {}; the cleanup takes it",
                    attachment.getId(),
                    e);
              }
            });
  }

  private static java.util.Optional<UploadedOriginalRef> originalRef(ChatAttachment attachment) {
    if (attachment.getOriginalLocator() == null) {
      return java.util.Optional.empty();
    }
    return java.util.Optional.of(
        new UploadedOriginalRef(
            attachment.getOrganizationId(),
            UploadedOriginalStore.CHAT_ATTACHMENT_AREA,
            attachment.getOriginalLocator()));
  }

  private Space requireMembership(UUID spaceId, UUID userId) {
    Space space =
        spaceRepository
            .findById(spaceId)
            .orElseThrow(() -> new NotFoundException("Space nicht gefunden"));
    if (!spaceAccessPolicy.isMember(spaceId, userId)) {
      throw new NotFoundException("Space nicht gefunden");
    }
    return space;
  }

  private UploadedOriginalStore.AcceptedUpload accept(
      UUID organizationId, String extension, MultipartFile file) throws IOException {
    try (InputStream in = file.getInputStream()) {
      return originalStore.accept(
          organizationId, UploadedOriginalStore.CHAT_ATTACHMENT_AREA, extension, in);
    }
  }

  private void requireContentMatchesExtension(Path file, String extension) {
    String detected;
    try {
      detected = SupportedDocumentFormats.detectMediaType(file);
    } catch (IOException e) {
      throw new UncheckedIOException("Datei konnte nicht auf ihr Format geprüft werden", e);
    }
    if (!supportedFormats.contentMatchesExtension(extension, detected)) {
      throw new ValidationException(
          "Der Inhalt der Datei entspricht nicht dem Format " + extension);
    }
  }

  private void requireNoMalware(Path file, UUID organizationId) {
    switch (malwareScanService.checkPersonal(file, organizationId)) {
      case MalwareScanService.Verdict.Clean clean -> {}
      case MalwareScanService.Verdict.AcceptedUnscanned accepted -> {}
      case MalwareScanService.Verdict.Infected infected ->
          throw new ValidationException(
              MalwareScanService.INFECTED_MESSAGE + " (" + infected.signature() + ")");
      case MalwareScanService.Verdict.Unscanned unscanned ->
          throw new ServiceUnavailableException(MalwareScanService.UNSCANNED_MESSAGE);
    }
  }

  private String matchedExtension(String fileName) {
    String lower = fileName.toLowerCase(Locale.ROOT);
    return supportedFormats.extensions().stream()
        .filter(lower::endsWith)
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("No supported extension matched"));
  }

  private static String probeContentType(Path file) {
    try {
      return Files.probeContentType(file);
    } catch (IOException e) {
      return null;
    }
  }
}
