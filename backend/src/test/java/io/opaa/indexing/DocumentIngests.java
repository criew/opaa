package io.opaa.indexing;

import static org.mockito.ArgumentMatchers.argThat;

import io.opaa.api.types.DocumentSourceType;
import io.opaa.indexing.pipeline.DocumentProperties;
import io.opaa.indexing.pipeline.confluence.ConfluenceDocumentPipeline;
import io.opaa.library.KnowledgeLibrary;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Test support for {@link DocumentIngest}: the shapes the connectors hand to {@link
 * FileProcessingService#ingest} as fixtures, and a Mockito matcher over the record's fields for
 * tests that mock the service. Public - consumed from every connector's test package.
 */
public final class DocumentIngests {

  private DocumentIngests() {}

  /** An RSS entry's body the way {@code RssFeedIndexingExecutor} hands it over. */
  public static DocumentIngest rssEntry(
      KnowledgeLibrary library,
      String mainText,
      String title,
      String entryUrl,
      String publishedAt) {
    return DocumentIngest.text(library, entryUrl, mainText)
        .sourceType(DocumentSourceType.RSS_FEED)
        .title(title)
        .changeMarker(publishedAt)
        .documentDate(DocumentProperties.instantToLocalDate(publishedAt))
        .build();
  }

  /** A Confluence page's storage body the way {@code ConfluenceIndexingExecutor} hands it over. */
  public static DocumentIngest confluencePage(
      KnowledgeLibrary library,
      String storageBody,
      String title,
      String pageUrl,
      String version,
      Instant lastModified,
      SourceDocumentContext context) {
    return DocumentIngest.text(library, pageUrl, storageBody)
        .sourceType(DocumentSourceType.CONFLUENCE)
        .title(title)
        .context(context)
        .changeMarker(version)
        .modifiedAt(DocumentProperties.instantToLocalDate(lastModified))
        .pipelineId(ConfluenceDocumentPipeline.ID)
        .build();
  }

  /** A downloaded file identified by its remote URL, the way {@code UrlIndexingExecutor} does. */
  public static DocumentIngest.Builder downloadedFile(
      KnowledgeLibrary library,
      Path localFile,
      String fileName,
      String remoteUrl,
      String lastModified,
      long remoteFileSize) {
    return DocumentIngest.builder(library)
        .file(localFile, remoteFileSize)
        .filePath(remoteUrl)
        .fileName(fileName)
        .sourceType(DocumentSourceType.HTTP_DIRECTORY)
        .context(SourceDocumentContext.NONE)
        .changeMarker(lastModified);
  }

  public static Path fileOf(DocumentIngest ingest) {
    return ((DocumentIngest.File) ingest.content()).path();
  }

  public static String textOf(DocumentIngest ingest) {
    return ((DocumentIngest.Text) ingest.content()).text();
  }

  /** A Mockito matcher: {@code ingest(that().text().at(url).match(), any())}. */
  public static Matcher that() {
    return new Matcher();
  }

  /** Any ingest whose content is text extracted upstream. */
  public static DocumentIngest anyText() {
    return that().text().match();
  }

  /** Any ingest whose content is a file on disk. */
  public static DocumentIngest anyFile() {
    return that().file().match();
  }

  /** Any text ingest identified by {@code filePath}. */
  public static DocumentIngest textAt(String filePath) {
    return that().text().at(filePath).match();
  }

  /** Any file ingest named {@code fileName}. */
  public static DocumentIngest fileNamed(String fileName) {
    return that().file().named(fileName).match();
  }

  /** Composable field predicates over a {@link DocumentIngest}, ending in {@link #match()}. */
  public static final class Matcher {

    private Predicate<DocumentIngest> predicate = ingest -> ingest != null;

    private Matcher() {}

    private Matcher and(Predicate<DocumentIngest> next) {
      predicate = predicate.and(next);
      return this;
    }

    public Matcher text() {
      return and(ingest -> ingest.content() instanceof DocumentIngest.Text);
    }

    public Matcher text(String text) {
      return text().and(ingest -> text.equals(textOf(ingest)));
    }

    public Matcher textContaining(String fragment) {
      return text().and(ingest -> textOf(ingest).contains(fragment));
    }

    public Matcher textMatching(Predicate<String> text) {
      return text().and(ingest -> text.test(textOf(ingest)));
    }

    public Matcher file() {
      return and(ingest -> ingest.content() instanceof DocumentIngest.File);
    }

    public Matcher file(Path path) {
      return file().and(ingest -> path.equals(fileOf(ingest)));
    }

    public Matcher at(String filePath) {
      return and(ingest -> filePath.equals(ingest.filePath()));
    }

    public Matcher atPathMatching(Predicate<String> filePath) {
      return and(ingest -> filePath.test(ingest.filePath()));
    }

    public Matcher named(String fileName) {
      return and(ingest -> fileName.equals(ingest.fileName()));
    }

    public Matcher titled(String title) {
      return and(ingest -> title.equals(ingest.title()));
    }

    public Matcher in(KnowledgeLibrary library) {
      return and(ingest -> library == ingest.library());
    }

    public Matcher from(DocumentSourceType sourceType) {
      return and(ingest -> sourceType == ingest.sourceType());
    }

    public Matcher foundOn(String sourceEntryUrl) {
      return and(ingest -> Objects.equals(sourceEntryUrl, ingest.sourceEntryUrl()));
    }

    public Matcher childOf(UUID parentDocumentId) {
      return and(ingest -> Objects.equals(parentDocumentId, ingest.parentDocumentId()));
    }

    public Matcher withContext(SourceDocumentContext context) {
      return and(ingest -> Objects.equals(context, ingest.context()));
    }

    public Matcher marked(String changeMarker) {
      return and(ingest -> Objects.equals(changeMarker, ingest.changeMarker()));
    }

    public Matcher modifiedAt(Instant lastModified) {
      return and(
          ingest ->
              Objects.equals(
                  DocumentProperties.instantToLocalDate(lastModified), ingest.modifiedAt()));
    }

    public Matcher sized(long byteSize) {
      return file().and(ingest -> ((DocumentIngest.File) ingest.content()).byteSize() == byteSize);
    }

    public Matcher inFolder(UUID folderId) {
      return and(
          ingest -> ingest.folder() != null && Objects.equals(folderId, ingest.folder().id()));
    }

    /** The Mockito argument matcher; {@code null} at call time like every {@code argThat}. */
    public DocumentIngest match() {
      return argThat(predicate::test);
    }
  }
}
