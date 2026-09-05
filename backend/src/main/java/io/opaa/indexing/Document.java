package io.opaa.indexing;

import io.opaa.api.types.DocumentSourceType;
import io.opaa.api.types.DocumentStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "documents")
public class Document {

  @Id private UUID id;

  @Column(name = "file_name", nullable = false, length = 500)
  private String fileName;

  /**
   * Polymorphic by {@link #sourceType}: a local filesystem/storage path for {@code
   * FILESYSTEM}/{@code UPLOAD}, a remote URL for {@code HTTP_DIRECTORY}/{@code RSS_FEED}/{@code
   * CONFLUENCE}. An attachment carries the synthetic path {@code
   * FileProcessingService#attachmentFilePath} builds from its parent's own (ADR-0022, Entscheidung
   * 2). Unique per library ({@code uk_documents_library_path}).
   */
  @Column(name = "file_path", nullable = false, length = 2000)
  private String filePath;

  @Column(name = "content_type")
  private String contentType;

  @Column(name = "file_size")
  private Long fileSize;

  @Column(name = "chunk_count")
  private int chunkCount;

  @Column(name = "indexed_at")
  private Instant indexedAt;

  @Column(name = "checksum", length = 64)
  private String checksum;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 20)
  private DocumentStatus status = DocumentStatus.PENDING;

  @Enumerated(EnumType.STRING)
  @Column(name = "source_type", nullable = false, length = 20)
  private DocumentSourceType sourceType = DocumentSourceType.FILESYSTEM;

  @Column(name = "last_modified_remote", length = 64)
  private String lastModifiedRemote;

  /**
   * The knowledge library this document belongs to - every document belongs to exactly one library,
   * enforced as {@code NOT NULL} with {@code fk_documents_library}. Not part of the constructors
   * (unlike {@code fileName}/{@code filePath}): callers set it explicitly after construction, the
   * same way {@code checksum} and {@code status} are set. A directory or URL indexing run always
   * targets a library the caller chose and holds at least {@code EDITOR} on ({@code
   * FileProcessingService#processFile}/{@code #processUrlFile}).
   */
  @Column(name = "library_id")
  private UUID libraryId;

  /**
   * The organization the {@link #libraryId} library belongs to, denormalized onto the document so
   * the permission-aware vector search can filter chunks by organization without a join back to
   * {@code knowledge_libraries} - see the same reasoning on {@code
   * FileProcessingService#storeChunks}. Set together with {@link #libraryId}, never independently.
   */
  @Column(name = "organization_id")
  private UUID organizationId;

  /**
   * The user who uploaded this document via the REST upload endpoint, or {@code null} for every
   * other {@link #sourceType}. Kept separate from {@link #libraryId}'s owner: a library's owner and
   * the person who happened to upload a given file into it are frequently different once a library
   * is shared.
   */
  @Column(name = "uploaded_by_user_id")
  private UUID uploadedByUserId;

  /**
   * For an attachment discovered on an RSS entry's detail page, the entry's own {@link #filePath}
   * (its detail page URL) - the trace back to the entry the origin document is found on. {@code
   * null} for every other document, including the RSS entry's own row and every {@code
   * FILESYSTEM}/{@code HTTP_DIRECTORY}/{@code UPLOAD} document.
   */
  @Column(name = "source_entry_url", length = 2000)
  private String sourceEntryUrl;

  /**
   * ADR-0023 ("Identität und Metadaten"): the container this document came from - a Confluence
   * space key - and its hierarchy path (ancestor titles, root first, " / "-joined). Both {@code
   * null} for every source type without such a notion.
   */
  @Column(name = "source_container_key", length = 255)
  private String sourceContainerKey;

  @Column(name = "source_hierarchy_path", length = 2000)
  private String sourceHierarchyPath;

  /**
   * The row this document is an attachment of (ADR-0022, Entscheidung 4), or {@code null} for a
   * document that is not an attachment. Generalizes {@link #sourceEntryUrl}'s RSS-only, path-string
   * reference into a real FK usable by every attachment source - RSS, mail, future Confluence. No
   * {@code @ManyToOne}, the same convention {@link #libraryId} follows: callers that need the
   * parent row look it up through {@code DocumentRepository} themselves. {@code
   * fk_documents_parent} carries no {@code ON DELETE CASCADE} - deleting a parent document stays
   * application code, since a DB-side cascade would orphan the parent's pgvector chunks (migration
   * 011).
   */
  @Column(name = "parent_document_id")
  private UUID parentDocumentId;

  /**
   * A German, user-facing reason {@link #status} is {@link DocumentStatus#FAILED} - set by {@code
   * FileProcessingService#processUploadedFileAsync} when parsing or embedding an uploaded file
   * fails asynchronously, after the row has already been returned to the caller with {@code
   * PENDING}. {@code null} for every other status.
   */
  @Column(name = "error_message", columnDefinition = "text")
  private String errorMessage;

  /**
   * When this row was first created - backs {@code UploadPendingRecoveryRunner}'s "how long has
   * this been PENDING" check, since {@link #indexedAt} stays {@code null} for a row's entire {@code
   * PENDING} lifetime. Set once in the constructor and never updated afterwards, the same {@code
   * updatable = false} contract {@code KnowledgeLibrary#createdAt} uses.
   */
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  /**
   * The {@code io.opaa.library.LibraryFolder} this document sits in (ADR-0020), or {@code null} for
   * the library's root - the same convention {@code LibraryFolder#getParentFolderId()} uses one
   * level up.
   */
  @Column(name = "folder_id")
  private UUID folderId;

  /**
   * The {@code CoreMetadataExtractor#EXTRACTION_VERSION} that last ran over this document (ADR-
   * 0024), or {@code null} when none ever did - the selection key of the Bestandslauf. Written only
   * through {@link DocumentRepository#updateMetadataExtractionVersion}.
   */
  @Column(name = "metadata_extraction_version", insertable = false, updatable = false)
  private Integer metadataExtractionVersion;

  protected Document() {}

  public Document(String fileName, String filePath, String contentType, Long fileSize) {
    this.id = UUID.randomUUID();
    this.fileName = fileName;
    this.filePath = filePath;
    this.contentType = contentType;
    this.fileSize = fileSize;
    this.status = DocumentStatus.PENDING;
    this.createdAt = Instant.now();
  }

  public Document(
      String fileName,
      String filePath,
      String contentType,
      Long fileSize,
      DocumentSourceType sourceType) {
    this(fileName, filePath, contentType, fileSize);
    this.sourceType = sourceType;
  }

  public UUID getId() {
    return id;
  }

  public String getFileName() {
    return fileName;
  }

  public String getFilePath() {
    return filePath;
  }

  public String getContentType() {
    return contentType;
  }

  public Long getFileSize() {
    return fileSize;
  }

  /**
   * Backs {@code FileProcessingService#processRssEntry}'s update-in-place path: a changed RSS entry
   * keeps its row's identity so {@link #getId()} - and therefore any attachment's {@link
   * #parentDocumentId} pointing at it - stays valid, instead of the delete-and-recreate every other
   * connector path uses when a document's content changes.
   */
  public void setFileName(String fileName) {
    this.fileName = fileName;
  }

  /** See {@link #setFileName(String)}. */
  public void setContentType(String contentType) {
    this.contentType = contentType;
  }

  /** See {@link #setFileName(String)}. */
  public void setFileSize(Long fileSize) {
    this.fileSize = fileSize;
  }

  public int getChunkCount() {
    return chunkCount;
  }

  public void setChunkCount(int chunkCount) {
    this.chunkCount = chunkCount;
  }

  public Instant getIndexedAt() {
    return indexedAt;
  }

  public void setIndexedAt(Instant indexedAt) {
    this.indexedAt = indexedAt;
  }

  public DocumentStatus getStatus() {
    return status;
  }

  public void setStatus(DocumentStatus status) {
    this.status = status;
  }

  public String getChecksum() {
    return checksum;
  }

  public void setChecksum(String checksum) {
    this.checksum = checksum;
  }

  public DocumentSourceType getSourceType() {
    return sourceType;
  }

  public void setSourceType(DocumentSourceType sourceType) {
    this.sourceType = sourceType;
  }

  public String getLastModifiedRemote() {
    return lastModifiedRemote;
  }

  public void setLastModifiedRemote(String lastModifiedRemote) {
    this.lastModifiedRemote = lastModifiedRemote;
  }

  public UUID getLibraryId() {
    return libraryId;
  }

  public void setLibraryId(UUID libraryId) {
    this.libraryId = libraryId;
  }

  public UUID getOrganizationId() {
    return organizationId;
  }

  public void setOrganizationId(UUID organizationId) {
    this.organizationId = organizationId;
  }

  public UUID getUploadedByUserId() {
    return uploadedByUserId;
  }

  public void setUploadedByUserId(UUID uploadedByUserId) {
    this.uploadedByUserId = uploadedByUserId;
  }

  public String getSourceEntryUrl() {
    return sourceEntryUrl;
  }

  public String getSourceContainerKey() {
    return sourceContainerKey;
  }

  public String getSourceHierarchyPath() {
    return sourceHierarchyPath;
  }

  /** Applies {@code context}'s container and hierarchy; a {@code null} context clears neither. */
  public void applySourceContext(SourceDocumentContext context) {
    if (context == null) {
      return;
    }
    this.sourceContainerKey = context.containerKey();
    this.sourceHierarchyPath = truncate(context.hierarchyPath(), 2000);
  }

  private static String truncate(String value, int max) {
    return value != null && value.length() > max ? value.substring(0, max) : value;
  }

  public void setSourceEntryUrl(String sourceEntryUrl) {
    this.sourceEntryUrl = sourceEntryUrl;
  }

  public UUID getParentDocumentId() {
    return parentDocumentId;
  }

  public void setParentDocumentId(UUID parentDocumentId) {
    this.parentDocumentId = parentDocumentId;
  }

  public String getErrorMessage() {
    return errorMessage;
  }

  public void setErrorMessage(String errorMessage) {
    this.errorMessage = errorMessage;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public UUID getFolderId() {
    return folderId;
  }

  public void setFolderId(UUID folderId) {
    this.folderId = folderId;
  }

  public Integer getMetadataExtractionVersion() {
    return metadataExtractionVersion;
  }

  /**
   * The deep link target for a document with no local file: {@link #getFilePath()} holds the remote
   * URL itself for {@code HTTP_DIRECTORY} and {@code RSS_FEED} - the same identity {@code
   * FileProcessingService#processUrlFile} deduplicates by - but the server-local storage path for
   * {@code UPLOAD}/{@code FILESYSTEM}, which must stay internal. Shared between {@code
   * io.opaa.api.LibraryDocumentResponseMapper} (library listing) and {@code QueryService} (citation
   * deep links) so both compute the identical value from a single place.
   *
   * <p>Unlike {@code LibraryResponse.sourceUrl}, which is masked below MANAGER, this value is
   * deliberately visible to every VIEWER - the masked field is the library's own source
   * configuration (crawl target, proxy, credentials), while this one names only a single document's
   * own origin URL, the same visibility {@code sourceEntryUrl} has.
   */
  public String getDeepLinkSourceUrl() {
    if (sourceType == DocumentSourceType.HTTP_DIRECTORY
        || sourceType == DocumentSourceType.RSS_FEED
        || sourceType == DocumentSourceType.CONFLUENCE) {
      return filePath;
    }
    return null;
  }
}
