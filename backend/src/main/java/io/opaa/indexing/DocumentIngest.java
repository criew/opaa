package io.opaa.indexing;

import io.opaa.api.types.DocumentSourceType;
import io.opaa.library.KnowledgeLibrary;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * One document to take into a library, as {@link FileProcessingService#ingest} sees it: its
 * identity, its content and the provenance its source declares. The sources differ only in the
 * values they put here, never in the sequence that runs over them.
 *
 * @param library the library the document belongs to
 * @param filePath the document's identity within the library ({@code file_path}: an absolute path
 *     or a URL)
 * @param fileName the document's own name - a file name, or the declared title of a {@link Text}
 * @param content what is parsed: a file on disk or text extracted upstream
 * @param sourceType the source the document comes from
 * @param parentDocumentId the row this document is an attachment of, or {@code null}
 * @param sourceEntryUrl the document an attachment was found on, or {@code null}
 * @param context where inside its source the document sits; {@code null} makes no statement and
 *     leaves an existing row's context alone
 * @param changeMarker the source's own change marker (a listing's last-modified, a feed entry's
 *     publication instant, a page version), persisted as {@code last_modified_remote}
 * @param folder the library folder to place the document in; {@code null} leaves an existing row's
 *     folder alone (a source whose executor manages folders itself)
 * @param title the title the source declares for the document; overrides the format's own
 * @param syntheticName whether {@code fileName} is free text (a headline, a page title) rather than
 *     a file name a naming convention could be read from
 * @param documentDate a date the source declares as the document's own
 * @param modifiedAt when the source says the document's current version was created
 * @param pipelineId the pipeline to run directly; {@code null} routes a {@link File} by its
 *     detected content and hands a {@link Text} to the fallback pipeline
 * @param existingRow the row already exists and was admitted when it was created (an upload stored
 *     as {@code PENDING}, a document being re-indexed): no change detection, no quota check, and
 *     the row's fields stay as they are
 * @param reindex a re-run over content that has not changed: on every outcome but {@code CHUNKED},
 *     and on a failure before the previous chunks were removed, the row and its chunks stay
 *     untouched. Implies {@code existingRow}.
 */
public record DocumentIngest(
    KnowledgeLibrary library,
    String filePath,
    String fileName,
    Content content,
    DocumentSourceType sourceType,
    UUID parentDocumentId,
    String sourceEntryUrl,
    SourceDocumentContext context,
    String changeMarker,
    Folder folder,
    String title,
    boolean syntheticName,
    LocalDate documentDate,
    LocalDate modifiedAt,
    String pipelineId,
    boolean existingRow,
    boolean reindex) {

  public DocumentIngest {
    Objects.requireNonNull(library, "library");
    Objects.requireNonNull(filePath, "filePath");
    Objects.requireNonNull(fileName, "fileName");
    Objects.requireNonNull(content, "content");
    Objects.requireNonNull(sourceType, "sourceType");
    if (reindex && !existingRow) {
      throw new IllegalArgumentException("A re-index runs over an existing row: " + filePath);
    }
  }

  /** The bytes to parse: exactly one of {@link File} and {@link Text}. */
  public sealed interface Content permits File, Text {}

  /**
   * A file on disk. {@code byteSize} is what counts toward the library's quota and is persisted as
   * the row's {@code file_size} - a source may know a size the local copy does not have.
   */
  public record File(Path path, long byteSize) implements Content {

    public File {
      Objects.requireNonNull(path, "path");
    }

    /** {@code path} with its own size on disk. */
    public static File of(Path path) throws IOException {
      return new File(path, Files.size(path));
    }
  }

  /** Text extracted upstream that never was a file; its UTF-8 bytes are what is measured. */
  public record Text(String text) implements Content {

    public Text {
      Objects.requireNonNull(text, "text");
    }

    public byte[] bytes() {
      return text.getBytes(StandardCharsets.UTF_8);
    }
  }

  /** A folder placement; {@code id == null} is the library's root. */
  public record Folder(UUID id) {}

  /**
   * A file to be identified by its own absolute path, as a filesystem source indexes it: name and
   * size are the file's own, the source type is {@link DocumentSourceType#FILESYSTEM}.
   */
  public static Builder localFile(KnowledgeLibrary library, Path file) throws IOException {
    return new Builder(library)
        .file(file)
        .filePath(file.toAbsolutePath().toString())
        .fileName(file.getFileName().toString())
        .sourceType(DocumentSourceType.FILESYSTEM);
  }

  /**
   * Text that never was a file, identified by {@code filePath}: its name is the declared {@link
   * Builder#title} and, without one, {@code filePath} itself - a synthetic name either way.
   */
  public static Builder text(KnowledgeLibrary library, String filePath, String text) {
    return new Builder(library).text(text).filePath(filePath).syntheticName(true);
  }

  public static Builder builder(KnowledgeLibrary library) {
    return new Builder(library);
  }

  /** Names each value a source sets; {@link #build()} validates what every ingest needs. */
  public static final class Builder {

    private final KnowledgeLibrary library;
    private String filePath;
    private String fileName;
    private Content content;
    private DocumentSourceType sourceType;
    private UUID parentDocumentId;
    private String sourceEntryUrl;
    private SourceDocumentContext context;
    private String changeMarker;
    private Folder folder;
    private String title;
    private boolean syntheticName;
    private LocalDate documentDate;
    private LocalDate modifiedAt;
    private String pipelineId;
    private boolean existingRow;
    private boolean reindex;

    private Builder(KnowledgeLibrary library) {
      this.library = Objects.requireNonNull(library, "library");
    }

    public Builder file(Path path) throws IOException {
      this.content = File.of(path);
      return this;
    }

    public Builder file(Path path, long byteSize) {
      this.content = new File(path, byteSize);
      return this;
    }

    public Builder text(String text) {
      this.content = new Text(text);
      return this;
    }

    public Builder filePath(String filePath) {
      this.filePath = filePath;
      return this;
    }

    public Builder fileName(String fileName) {
      this.fileName = fileName;
      return this;
    }

    public Builder sourceType(DocumentSourceType sourceType) {
      this.sourceType = sourceType;
      return this;
    }

    public Builder parentDocumentId(UUID parentDocumentId) {
      this.parentDocumentId = parentDocumentId;
      return this;
    }

    public Builder sourceEntryUrl(String sourceEntryUrl) {
      this.sourceEntryUrl = sourceEntryUrl;
      return this;
    }

    public Builder context(SourceDocumentContext context) {
      this.context = context;
      return this;
    }

    public Builder changeMarker(String changeMarker) {
      this.changeMarker = changeMarker;
      return this;
    }

    /** Places the document in {@code folderId}, {@code null} being the library's root. */
    public Builder folder(UUID folderId) {
      this.folder = new Folder(folderId);
      return this;
    }

    /** A blank title counts as none. */
    public Builder title(String title) {
      this.title = title == null || title.isBlank() ? null : title;
      return this;
    }

    public Builder syntheticName(boolean syntheticName) {
      this.syntheticName = syntheticName;
      return this;
    }

    public Builder documentDate(LocalDate documentDate) {
      this.documentDate = documentDate;
      return this;
    }

    public Builder modifiedAt(LocalDate modifiedAt) {
      this.modifiedAt = modifiedAt;
      return this;
    }

    public Builder pipelineId(String pipelineId) {
      this.pipelineId = pipelineId;
      return this;
    }

    public Builder existingRow() {
      this.existingRow = true;
      return this;
    }

    public Builder reindex() {
      this.existingRow = true;
      this.reindex = true;
      return this;
    }

    public DocumentIngest build() {
      String name = fileName;
      if (name == null && syntheticName) {
        name = title != null ? title : filePath;
      }
      return new DocumentIngest(
          library,
          filePath,
          name,
          content,
          sourceType,
          parentDocumentId,
          sourceEntryUrl,
          context,
          changeMarker,
          folder,
          title,
          syntheticName,
          documentDate,
          modifiedAt,
          pipelineId,
          existingRow,
          reindex);
    }
  }
}
