package io.opaa.indexing;

import io.opaa.api.types.DocumentSourceType;
import io.opaa.indexing.source.filesystem.FilesystemPathAllowlist;
import io.opaa.library.KnowledgeLibrary;
import io.opaa.library.KnowledgeLibraryRepository;
import io.opaa.library.UploadProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * How the bytes of an already indexed document can be reached again by an operator-triggered run
 * over the bestand: its own file on this machine ({@link #localSourceFile}), re-extracted from its
 * root ancestor's file along the attachment chain ({@link #withReextractedAttachment}, ADR-0022),
 * or only by its next connector run ({@link #markRemoteChainForNextRun}). Shared by the pipeline
 * re-index ({@link PipelineReindexService}) and the core-metadata backfill ({@code
 * io.opaa.indexing.metadata.MetadataBackfillService}), so both apply the same runtime containment
 * discipline (ADR-0018, Entscheidung 6) and the same chain rules. Every unreachable case is a skip,
 * never an error.
 */
public class StoredDocumentSourceAccess {

  private static final Logger log = LoggerFactory.getLogger(StoredDocumentSourceAccess.class);

  private final AttachmentExtractor attachmentExtractor;
  private final DocumentRepository documentRepository;
  private final KnowledgeLibraryRepository libraryRepository;
  private final ChecksumService checksumService;
  private final FilesystemPathAllowlist filesystemAllowlist;
  private final UploadProperties uploadProperties;

  public StoredDocumentSourceAccess(
      AttachmentExtractor attachmentExtractor,
      DocumentRepository documentRepository,
      KnowledgeLibraryRepository libraryRepository,
      ChecksumService checksumService,
      FilesystemPathAllowlist filesystemAllowlist,
      UploadProperties uploadProperties) {
    this.attachmentExtractor = attachmentExtractor;
    this.documentRepository = documentRepository;
    this.libraryRepository = libraryRepository;
    this.checksumService = checksumService;
    this.filesystemAllowlist = filesystemAllowlist;
    this.uploadProperties = uploadProperties;
  }

  /**
   * Whether {@code document}'s bytes live on a remote the connector run alone can re-read.
   * Confluence included (#1137): clearing checksum and version marker makes the next run fetch and
   * process the page again - the executor's pre-fetch version check and the processing checksum
   * check both see "changed".
   */
  public static boolean isRemote(Document document) {
    DocumentSourceType sourceType = document.getSourceType();
    return sourceType == DocumentSourceType.HTTP_DIRECTORY
        || sourceType == DocumentSourceType.RSS_FEED
        || sourceType == DocumentSourceType.CONFLUENCE;
  }

  /**
   * The document's own file on this machine, or {@code null} when this deployment may not read it
   * again. Applies the same runtime containment discipline {@code
   * LibraryDocumentService#filesystemFileIfWithinConfiguredDirectory}/{@code
   * #uploadedFileIfManagedByThisService} apply before serving an original, and for the same reason
   * (ADR-0018, Entscheidung 6): {@code file_path} was validated when the document was indexed, but
   * the allowlist can be narrowed - or emptied, which disables the {@code FILESYSTEM} source type
   * entirely - afterwards, and a run over the bestand must not be the one path that silently keeps
   * reading from a directory an operator has since withdrawn.
   *
   * <ul>
   *   <li>{@code FILESYSTEM}: the library's own {@code sourcePath} must still pass {@link
   *       FilesystemPathAllowlist}, and the file must resolve underneath it - both via {@link
   *       Path#toRealPath}, so a symlink out of the configured directory cannot pass the lexical
   *       prefix check.
   *   <li>{@code UPLOAD}: the file must lie inside this library's own subdirectory of the managed
   *       upload storage - the only files this system wrote itself.
   * </ul>
   */
  public Path localSourceFile(Document document) {
    if (document.getFilePath() == null || document.getLibraryId() == null) {
      return null;
    }
    Path candidate;
    try {
      candidate = Path.of(document.getFilePath());
    } catch (InvalidPathException e) {
      log.warn("Document {} has a file path that is not a local path", document.getId(), e);
      return null;
    }
    return switch (document.getSourceType()) {
      case FILESYSTEM -> filesystemFileWithinConfiguredDirectory(document, candidate);
      case UPLOAD -> uploadedFileWithinManagedStorage(document, candidate);
      case HTTP_DIRECTORY, RSS_FEED, CONFLUENCE -> null;
    };
  }

  /**
   * Re-extracts an attachment document's bytes (ADR-0022) - a row whose {@code file_path} is
   * synthetic ({@code <parentPath>/<index>/<name>}, see {@code
   * FileProcessingService#attachmentFilePath}) and therefore never resolves to a file of its own -
   * from the root ancestor's still-readable source file, by re-running the parent chain's pipelines
   * and following the positional index encoded in each {@code file_path} segment, and hands the
   * resulting temp file to {@code action}. The temp files are deleted afterwards. Returns {@code
   * false} without calling {@code action} on every non-recoverable mismatch (chain broken or
   * cyclic, root remote or unreadable, index out of range or checksum mismatch because the parent
   * file changed since) - a skip, never an error: the next full indexing run of the parent
   * re-establishes consistency.
   */
  public boolean withReextractedAttachment(Document document, Predicate<Path> action) {
    List<Document> chain = new ArrayList<>();
    Set<UUID> seen = new HashSet<>();
    Document current = document;
    while (current.getParentDocumentId() != null) {
      if (!seen.add(current.getId())) {
        log.warn(
            "Skipping attachment document {}: its parent_document_id chain contains a cycle",
            document.getId());
        return false;
      }
      Document parent = documentRepository.findById(current.getParentDocumentId()).orElse(null);
      if (parent == null) {
        log.warn(
            "Skipping attachment document {}: its parent chain is broken at {}",
            document.getId(),
            current.getParentDocumentId());
        return false;
      }
      chain.add(current);
      current = parent;
    }
    Document root = current;
    if (root.getSourceType() != DocumentSourceType.FILESYSTEM
        && root.getSourceType() != DocumentSourceType.UPLOAD) {
      log.info(
          "Skipping attachment document {}: only FILESYSTEM and UPLOAD parents support"
              + " re-extraction",
          document.getId());
      return false;
    }
    Path rootFile = localSourceFile(root);
    if (rootFile == null) {
      log.info(
          "Skipping attachment document {}: its root ancestor's file is not readable within the"
              + " directories this deployment is configured to read",
          document.getId());
      return false;
    }
    List<Integer> indices = new ArrayList<>(chain.size());
    String parentPath = root.getFilePath();
    for (int i = chain.size() - 1; i >= 0; i--) {
      int index = FileProcessingService.attachmentIndexIn(parentPath, chain.get(i).getFilePath());
      if (index < 0) {
        log.warn(
            "Skipping attachment document {}: file_path {} does not embed its parent's path {}",
            document.getId(),
            chain.get(i).getFilePath(),
            parentPath);
        return false;
      }
      indices.add(index);
      parentPath = chain.get(i).getFilePath();
    }
    List<Path> extractedFiles = new ArrayList<>(indices.size());
    try {
      Path currentFile = rootFile;
      String currentName = root.getFileName();
      for (int i = 0; i < indices.size(); i++) {
        AttachmentExtractor.Extracted extracted =
            attachmentExtractor.extract(currentFile, currentName, indices.get(i));
        if (extracted == null) {
          log.info(
              "Skipping attachment document {}: attachment index {} no longer exists in {}",
              document.getId(),
              indices.get(i),
              currentName);
          return false;
        }
        extractedFiles.add(extracted.file());
        currentFile = extracted.file();
        currentName = chain.get(chain.size() - 1 - i).getFileName();
      }
      // Positional indices are only stable while the parent file is unchanged - a parent edited
      // since the row was created (an attachment removed, order shifted) can leave a DIFFERENT
      // attachment at this row's index. Extraction is deterministic, so for an unchanged parent
      // the re-extracted bytes match the row's own stored checksum exactly; a mismatch means the
      // bytes belong to some other attachment and must never be written under this row.
      String extractedChecksum = checksumService.computeSha256(currentFile);
      if (document.getChecksum() != null && !extractedChecksum.equals(document.getChecksum())) {
        log.info(
            "Skipping attachment document {}: the re-extracted bytes no longer match its checksum"
                + " (parent file changed since indexing) - the next indexing run of the parent"
                + " re-establishes consistency",
            document.getId());
        return false;
      }
      return action.test(currentFile);
    } catch (IOException e) {
      log.warn("Skipping attachment document {}: re-extraction failed", document.getId(), e);
      return false;
    } finally {
      for (Path extracted : extractedFiles) {
        try {
          Files.deleteIfExists(extracted);
        } catch (IOException e) {
          log.warn("Failed to delete re-extracted attachment temp file: {}", extracted, e);
        }
      }
    }
  }

  /**
   * Marks a remote (HTTP_DIRECTORY/RSS_FEED) document, and for an attachment its <em>whole</em>
   * parent chain up to the root (#1219), for the next connector run by clearing every change marker
   * ({@link DocumentRepository#markForReindexOnNextRun}). Only that run can re-download the root; a
   * chain cleared level by level is re-parsed level by level, so the attachment is reached again.
   * Never deletes a row (ADR-0022, Entscheidung 3). Returns {@code false} - a skip - for a broken
   * or cyclic chain.
   */
  public boolean markRemoteChainForNextRun(Document document) {
    List<UUID> chainIds = new ArrayList<>();
    Set<UUID> seen = new HashSet<>();
    Document current = document;
    while (true) {
      if (!seen.add(current.getId())) {
        log.warn(
            "Skipping attachment document {}: its parent_document_id chain contains a cycle",
            document.getId());
        return false;
      }
      chainIds.add(current.getId());
      if (current.getParentDocumentId() == null) {
        break;
      }
      Document parent = documentRepository.findById(current.getParentDocumentId()).orElse(null);
      if (parent == null) {
        log.warn(
            "Skipping attachment document {}: its parent chain is broken at {}",
            document.getId(),
            current.getParentDocumentId());
        return false;
      }
      current = parent;
    }
    for (UUID id : chainIds) {
      documentRepository.markForReindexOnNextRun(id);
    }
    return true;
  }

  private Path filesystemFileWithinConfiguredDirectory(Document document, Path candidate) {
    KnowledgeLibrary library = libraryRepository.findById(document.getLibraryId()).orElse(null);
    if (library == null || library.getSourcePath() == null) {
      return null;
    }
    if (!filesystemAllowlist.isAllowed(library.getSourcePath())) {
      return null;
    }
    Path real = resolveReal(candidate);
    Path configuredDirectory = resolveReal(Path.of(library.getSourcePath()));
    if (real == null || configuredDirectory == null) {
      return null;
    }
    return real.startsWith(configuredDirectory) ? real : null;
  }

  private Path uploadedFileWithinManagedStorage(Document document, Path candidate) {
    Path libraryUploadDirectory =
        Paths.get(uploadProperties.storagePath())
            .resolve(document.getLibraryId().toString())
            .toAbsolutePath()
            .normalize();
    Path real = resolveReal(candidate);
    Path managedDirectory = resolveReal(libraryUploadDirectory);
    if (real == null || managedDirectory == null) {
      return null;
    }
    return real.startsWith(managedDirectory) ? real : null;
  }

  /**
   * {@link Path#toRealPath()}, or {@code null} if the path does not (or no longer) exist - a file
   * that has since disappeared is skipped, not an error.
   */
  private Path resolveReal(Path path) {
    try {
      return path.toRealPath();
    } catch (IOException e) {
      return null;
    }
  }
}
