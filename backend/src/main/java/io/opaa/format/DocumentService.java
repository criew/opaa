package io.opaa.format;

import io.opaa.format.chunk.PageMarkingContentHandler;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.reader.ExtractedTextFormatter;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.core.io.FileSystemResource;

public class DocumentService {

  private static final Logger log = LoggerFactory.getLogger(DocumentService.class);

  /**
   * The user-facing message a document is rejected with when a pipeline finds no extractable text
   * (ingestion-pipelines.md, Teil 3, Punkt 1 "Scan-Erkennung und Bestandsprüfung"). Shared by every
   * caller that both sets it as a document's error message and reports it as an indexing run event,
   * so the two never drift apart.
   */
  public static final String NO_EXTRACTABLE_TEXT_MESSAGE =
      "Enthält keinen extrahierbaren Text, vermutlich ein Scan; für diese Datei ist"
          + " Texterkennung nötig, die derzeit nicht eingerichtet ist";

  /**
   * Everything found below the document directory, split into what will be indexed, what was
   * rejected because of its format, and which of the indexed files carried an extension that did
   * not match their actually detected content. The rejected files are carried out of here on
   * purpose: they belong in the indexing job's counters, not in a filter nobody sees. {@code
   * unreadable} names the entries below the directory the walk could not enter or inspect - what
   * lies beneath them is unknown, so a non-empty list means the listing is incomplete.
   */
  public record DiscoveredFiles(
      List<Path> supported,
      List<Path> rejected,
      List<FormatMismatch> mismatches,
      List<Path> unreadable) {

    public int totalFound() {
      return supported.size() + rejected.size();
    }
  }

  /**
   * A file that was accepted for indexing, but whose own extension did not match its Tika-detected
   * content - reported, never silently reinterpreted or rejected. {@code detectedExtension} is the
   * extension {@link SupportedDocumentFormats} associates with the detected content, for the event
   * message.
   */
  public record FormatMismatch(Path file, String detectedExtension) {}

  /**
   * @param supportedFormats what the caller's run admits - passed in rather than held as a field,
   *     because it is derived from the registered formats and the fallback format needs this class
   *     to parse, which a field would close into a bean cycle
   * @throws IOException if {@code directory} does not exist or is not a directory - a missing
   *     source path must fail the run rather than report an empty, successful bestand, which {@code
   *     AsyncIndexingExecutor}'s stale-document cleanup would read as "every indexed document
   *     vanished" and act on by deleting the whole library's content. The same holds when {@code
   *     directory} itself cannot be listed; an unreadable entry below it is skipped and reported in
   *     {@link DiscoveredFiles#unreadable()} instead.
   */
  public DiscoveredFiles discoverFiles(Path directory, SupportedDocumentFormats supportedFormats)
      throws IOException {
    if (!Files.exists(directory)) {
      throw new IOException("Document directory does not exist: " + directory);
    }
    if (!Files.isDirectory(directory)) {
      throw new IOException("Path is not a directory: " + directory);
    }
    List<Path> supported = new ArrayList<>();
    List<Path> rejected = new ArrayList<>();
    List<FormatMismatch> mismatches = new ArrayList<>();
    List<Path> unreadable = new ArrayList<>();
    Files.walkFileTree(
        directory,
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
            // a symlink to a file counts as that file; a linked directory is a leaf, never entered
            if (!Files.isRegularFile(file)) {
              return FileVisitResult.CONTINUE;
            }
            SupportedDocumentFormats.ContentDecision decision = classify(file, supportedFormats);
            if (!decision.supported()) {
              rejected.add(file);
            } else {
              supported.add(file);
              if (decision.extensionMismatch()) {
                mismatches.add(new FormatMismatch(file, decision.detectedExtension()));
              }
            }
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFileFailed(Path file, IOException e) throws IOException {
            if (file.equals(directory)) {
              throw e;
            }
            log.warn("Skipping unreadable {}: {}", file, e.toString());
            unreadable.add(file);
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult postVisitDirectory(Path dir, IOException e) throws IOException {
            if (e == null) {
              return FileVisitResult.CONTINUE;
            }
            if (dir.equals(directory)) {
              throw e;
            }
            log.warn("Listing of {} broke off: {}", dir, e.toString());
            unreadable.add(dir);
            return FileVisitResult.CONTINUE;
          }
        });
    return new DiscoveredFiles(supported, rejected, mismatches, unreadable);
  }

  public List<org.springframework.ai.document.Document> parseDocument(Path file) {
    log.debug("Parsing document: {}", file);
    var resource = new FileSystemResource(file);
    // Keep page boundaries as form feeds so chunks can carry a "S. n" location.
    var reader =
        new TikaDocumentReader(
            resource, new PageMarkingContentHandler(), ExtractedTextFormatter.defaults());
    return reader.read();
  }

  /**
   * Decides acceptance from the file's actual content ({@link
   * SupportedDocumentFormats#decideForFileName}). A file that cannot be read for detection at all
   * (deleted or permission-denied between the walk and this call) counts as unsupported rather than
   * propagating the {@link IOException}.
   */
  private static SupportedDocumentFormats.ContentDecision classify(
      Path file, SupportedDocumentFormats supportedFormats) {
    try {
      String detectedMimeType = SupportedDocumentFormats.detectMediaType(file);
      return supportedFormats.decideForFileName(file.getFileName().toString(), detectedMimeType);
    } catch (IOException e) {
      log.warn("Could not read {} to detect its format, treating it as unsupported", file, e);
      return supportedFormats.decideForFileName(file.getFileName().toString(), null);
    }
  }
}
