package io.opaa.indexing.document;

import io.opaa.api.types.DocumentSourceType;
import io.opaa.indexing.source.filesystem.FilesystemPathAllowlist;
import io.opaa.library.KnowledgeLibrary;
import io.opaa.library.KnowledgeLibraryRepository;
import io.opaa.library.UploadedOriginalRef;
import io.opaa.library.UploadedOriginalStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * How the bytes of an already indexed document can be reached again by an operator-triggered run
 * over the bestand: its own file ({@link #withLocalSourceFile}), re-extracted from its root
 * ancestor's file along the attachment chain ({@link #withReextractedAttachment}, ADR-0022), or
 * only by its next connector run ({@link #markRemoteChainForNextRun}). Shared by the pipeline
 * re-index ({@link io.opaa.indexing.maintenance.PipelineReindexService}) and the core-metadata
 * backfill ({@code io.opaa.indexing.maintenance.MetadataBackfillService}), so both apply the same
 * runtime containment discipline (ADR-0018, Entscheidung 6) and the same chain rules. Every
 * unreachable case is a skip, never an error.
 */
public class StoredDocumentSourceAccess {

  private static final Logger log = LoggerFactory.getLogger(StoredDocumentSourceAccess.class);

  private final AttachmentExtractor attachmentExtractor;
  private final DocumentRepository documentRepository;
  private final KnowledgeLibraryRepository libraryRepository;
  private final ChecksumService checksumService;
  private final FilesystemPathAllowlist filesystemAllowlist;
  private final UploadedOriginalStore uploadedOriginalStore;

  public StoredDocumentSourceAccess(
      AttachmentExtractor attachmentExtractor,
      DocumentRepository documentRepository,
      KnowledgeLibraryRepository libraryRepository,
      ChecksumService checksumService,
      FilesystemPathAllowlist filesystemAllowlist,
      UploadedOriginalStore uploadedOriginalStore) {
    this.attachmentExtractor = attachmentExtractor;
    this.documentRepository = documentRepository;
    this.libraryRepository = libraryRepository;
    this.checksumService = checksumService;
    this.filesystemAllowlist = filesystemAllowlist;
    this.uploadedOriginalStore = uploadedOriginalStore;
  }

  /**
   * Whether {@code document}'s bytes live on a remote the connector run alone can re-read.
   * Confluence included: clearing checksum and version marker makes the next run fetch and process
   * the page again - the executor's pre-fetch version check and the processing checksum check both
   * see "changed".
   */
  public static boolean isRemote(Document document) {
    DocumentSourceType sourceType = document.getSourceType();
    return sourceType != null && sourceType.isRemote();
  }

  /**
   * Runs {@code action} on the document's own file, or returns {@code false} without running it
   * when this deployment may not read it again: a {@code FILESYSTEM} library's {@code sourcePath}
   * must still pass {@link FilesystemPathAllowlist} and the file must resolve underneath it (the
   * allowlist can be narrowed after indexing, ADR-0018, Entscheidung 6), and an {@code UPLOAD} file
   * must belong to its library's own storage area. The file is only valid for the duration of the
   * call - an upload store that is not this machine's disk hands out a copy and removes it
   * afterwards (ADR-0030, Entscheidung 6).
   */
  public boolean withLocalSourceFile(Document document, Predicate<Path> action) {
    Optional<Boolean> outcome = withLocalFile(document, action::test);
    if (outcome.isEmpty()) {
      log.info(
          "Skipping document {}: its file is not readable within the directories this deployment"
              + " is configured to read",
          document.getId());
      return false;
    }
    return outcome.get();
  }

  /**
   * {@link #withLocalSourceFile} without the logging, so the attachment path can report its own
   * reason: empty means no readable file of this document, otherwise {@code action}'s own result.
   */
  private <T> Optional<T> withLocalFile(Document document, Function<Path, T> action) {
    if (document.getFilePath() == null || document.getLibraryId() == null || isRemote(document)) {
      return Optional.empty();
    }
    return switch (document.getSourceType()) {
      case FILESYSTEM ->
          Optional.ofNullable(filesystemFileWithinConfiguredDirectory(document)).map(action);
      case UPLOAD ->
          UploadedOriginalRef.of(document)
              .flatMap(ref -> uploadedOriginalStore.withLocalFile(ref, action));
      default ->
          throw new IllegalStateException(
              "local source type without a file resolution: " + document.getSourceType());
    };
  }

  /**
   * Re-extracts an attachment document's bytes (ADR-0022) - its {@code file_path} is synthetic and
   * resolves to no file of its own - by re-running the parent chain's pipelines from the root
   * ancestor's still-readable source and following the positional index in each path segment, then
   * hands the temp file to {@code action} and deletes it afterwards. Returns {@code false} without
   * calling {@code action} on any mismatch - a skip, never an error, since the next run repairs it.
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
    if (isRemote(root)) {
      log.info(
          "Skipping attachment document {}: only a parent with a local file supports"
              + " re-extraction",
          document.getId());
      return false;
    }
    List<Integer> indices = new ArrayList<>(chain.size());
    String parentPath = root.getFilePath();
    for (int i = chain.size() - 1; i >= 0; i--) {
      int index = AttachmentFilePath.indexIn(parentPath, chain.get(i).getFilePath());
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
    Optional<Boolean> outcome =
        withLocalFile(
            root, rootFile -> extractAlong(document, root, chain, indices, rootFile, action));
    if (outcome.isEmpty()) {
      log.info(
          "Skipping attachment document {}: its root ancestor's file is not readable within the"
              + " directories this deployment is configured to read",
          document.getId());
      return false;
    }
    return outcome.get();
  }

  /**
   * Follows {@code indices} down {@code chain}, starting at the root ancestor's own file, and hands
   * the last extracted file to {@code action}. Every temp file it created is deleted afterwards.
   */
  private boolean extractAlong(
      Document document,
      Document root,
      List<Document> chain,
      List<Integer> indices,
      Path rootFile,
      Predicate<Path> action) {
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
   * Marks a remote document, and for an attachment its <em>whole</em> parent chain up to the root,
   * for the next connector run by clearing every change marker. Only that run can re-download the
   * root, and a chain cleared level by level is re-parsed level by level, so the attachment is
   * reached again. Never deletes a row (ADR-0022, Entscheidung 3); a broken chain is a skip.
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

  private Path filesystemFileWithinConfiguredDirectory(Document document) {
    KnowledgeLibrary library = libraryRepository.findById(document.getLibraryId()).orElse(null);
    if (library == null || library.getSourcePath() == null) {
      return null;
    }
    if (!filesystemAllowlist.isAllowed(library.getSourcePath())) {
      return null;
    }
    Path candidate = localPath(document);
    if (candidate == null) {
      return null;
    }
    Path real = resolveReal(candidate);
    Path configuredDirectory = resolveReal(Path.of(library.getSourcePath()));
    if (real == null || configuredDirectory == null) {
      return null;
    }
    return real.startsWith(configuredDirectory) ? real : null;
  }

  private Path localPath(Document document) {
    try {
      return Path.of(document.getFilePath());
    } catch (InvalidPathException e) {
      log.warn("Document {} has a file path that is not a local path", document.getId(), e);
      return null;
    }
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
