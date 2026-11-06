package io.opaa.indexing.document;

import io.opaa.api.types.AssetRole;
import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditObjectType;
import io.opaa.api.types.AuditOutcome;
import io.opaa.audit.AuditEvent;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.auth.CurrentUser;
import io.opaa.common.NotFoundException;
import io.opaa.format.file.pdf.PdfOcrDocumentFormat;
import io.opaa.knowledge.Document;
import io.opaa.knowledge.DocumentRepository;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.KnowledgeLibraryRepository;
import io.opaa.knowledge.LibraryAccessService;
import io.opaa.llm.TextRecognitionModelRole.ActiveTextRecognition;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The text recognition state of a library as its views show it: how many scans wait, which are
 * locked and why, and the release of one lock (ADR-0069). Reading needs the read grant, like the
 * document list showing a document's error; releasing needs {@link AssetRole#EDITOR}, like
 * triggering a run. Nothing here names an account.
 */
public class TextRecognitionBlocks {

  private final DocumentTextRecognitionStore store;
  private final TextRecognitionIndexing textRecognition;
  private final KnowledgeLibraryRepository libraryRepository;
  private final DocumentRepository documentRepository;
  private final LibraryAccessService libraryAccessService;
  private final AuditEventRecorder auditEventRecorder;

  public TextRecognitionBlocks(
      DocumentTextRecognitionStore store,
      TextRecognitionIndexing textRecognition,
      KnowledgeLibraryRepository libraryRepository,
      DocumentRepository documentRepository,
      LibraryAccessService libraryAccessService,
      AuditEventRecorder auditEventRecorder) {
    this.store = store;
    this.textRecognition = textRecognition;
    this.libraryRepository = libraryRepository;
    this.documentRepository = documentRepository;
    this.libraryAccessService = libraryAccessService;
    this.auditEventRecorder = auditEventRecorder;
  }

  /**
   * Waiting and locked scans of a library, both {@code null} while text recognition is not in
   * force. The caller's access to the library is checked by the status it is part of.
   */
  public record Counts(Integer pending, Integer blocked) {
    public static final Counts NOT_IN_FORCE = new Counts(null, null);
  }

  public Counts counts(UUID libraryId) {
    Optional<ActiveTextRecognition> active = textRecognition.active();
    if (active.isEmpty()) {
      return Counts.NOT_IN_FORCE;
    }
    String configurationId = active.get().configurationId();
    return new Counts(
        store.pendingCount(libraryId, PdfOcrDocumentFormat.VERSION, configurationId),
        store.blocked(libraryId, PdfOcrDocumentFormat.VERSION, configurationId).size());
  }

  /** The locks that hold in {@code libraryId}, for a reader of the library. */
  public List<DocumentTextRecognitionStore.BlockedDocument> list(
      UUID libraryId, CurrentUser caller) {
    KnowledgeLibrary library = require(libraryId, caller, AssetRole.VIEWER);
    return store.blocked(
        library.getId(),
        PdfOcrDocumentFormat.VERSION,
        textRecognition.active().map(ActiveTextRecognition::configurationId).orElse(null));
  }

  /**
   * Releases the lock of {@code documentId} for a new attempt; a document without a lock is left as
   * it is. Only a released lock is recorded in the audit log.
   *
   * @throws NotFoundException when the document is not in the library
   */
  public void release(UUID libraryId, UUID documentId, CurrentUser caller) {
    KnowledgeLibrary library = require(libraryId, caller, AssetRole.EDITOR);
    Document document =
        documentRepository
            .findById(documentId)
            .filter(found -> library.getId().equals(found.getLibraryId()))
            .orElseThrow(() -> new NotFoundException("Dokument nicht gefunden"));
    Optional<DocumentTextRecognitionStore.Entry> entry = store.find(document.getId());
    if (entry.isEmpty() || !entry.get().blocked() || !store.release(document.getId())) {
      return;
    }
    Map<String, Object> before = new LinkedHashMap<>();
    before.put("documentId", document.getId().toString());
    before.put("blocked", true);
    before.put("connectionType", entry.get().connectionType());
    Map<String, Object> after = new LinkedHashMap<>();
    after.put("documentId", document.getId().toString());
    after.put("blocked", false);
    after.put("connectionType", entry.get().connectionType());
    auditEventRecorder.recordUserAction(
        AuditEvent.builder()
            .organizationId(library.getOrganizationId())
            .actor(caller.id())
            .type(AuditEventType.TEXT_RECOGNITION_BLOCK_RELEASED)
            .object(AuditObjectType.KNOWLEDGE_LIBRARY, library.getId(), library.auditName())
            .before(library.auditPayload(before))
            .after(library.auditPayload(after))
            .outcome(AuditOutcome.SUCCESS)
            .build());
  }

  private KnowledgeLibrary require(UUID libraryId, CurrentUser caller, AssetRole role) {
    KnowledgeLibrary library =
        libraryRepository.requireInOrganization(libraryId, caller.organizationId());
    libraryAccessService.requireRole(library, caller.id(), caller.isSystemAdmin(), role);
    return library;
  }
}
