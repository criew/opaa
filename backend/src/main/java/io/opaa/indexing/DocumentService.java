package io.opaa.indexing;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.reader.ExtractedTextFormatter;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.core.io.FileSystemResource;

public class DocumentService {

  private static final Logger log = LoggerFactory.getLogger(DocumentService.class);

  /**
   * The user-facing message a document is rejected with when {@link #isTextlessPdf} detects it -
   * ingestion-pipelines.md, Teil 3, Punkt 1 "Scan-Erkennung und Bestandsprüfung". Shared by every
   * caller that needs to both set it as {@link Document#getErrorMessage()} and report the same
   * wording as an {@link IndexingRunEvent}, so the two never drift apart.
   */
  static final String NO_EXTRACTABLE_TEXT_MESSAGE =
      "Enthält keinen extrahierbaren Text, vermutlich ein Scan; für diese Datei ist"
          + " Texterkennung nötig, die derzeit nicht eingerichtet ist";

  /**
   * Everything found below the document directory, split into what will be indexed, what was
   * rejected because of its format, and which of the indexed files carried an extension that did
   * not match their actually detected content. The rejected files are carried out of here on
   * purpose: they belong in the indexing job's counters, not in a filter nobody sees.
   */
  public record DiscoveredFiles(
      List<Path> supported, List<Path> rejected, List<FormatMismatch> mismatches) {

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
   * @throws IOException if {@code directory} does not exist or is not a directory - a missing
   *     source path (an unmounted network share, a moved/renamed directory) must fail this run, not
   *     report an empty, successful bestand (#886 review): {@code AsyncIndexingExecutor}'s own
   *     stale-document cleanup would otherwise read that empty bestand as "every previously indexed
   *     document vanished" and delete the whole library's content.
   */
  public DiscoveredFiles discoverFiles(Path directory) throws IOException {
    if (!Files.exists(directory)) {
      throw new IOException("Document directory does not exist: " + directory);
    }
    if (!Files.isDirectory(directory)) {
      throw new IOException("Path is not a directory: " + directory);
    }
    List<Path> supported = new ArrayList<>();
    List<Path> rejected = new ArrayList<>();
    List<FormatMismatch> mismatches = new ArrayList<>();
    try (Stream<Path> walk = Files.walk(directory)) {
      for (Path file : walk.filter(Files::isRegularFile).toList()) {
        SupportedDocumentFormats.ContentDecision decision = classify(file);
        if (!decision.supported()) {
          rejected.add(file);
          continue;
        }
        supported.add(file);
        if (decision.extensionMismatch()) {
          mismatches.add(new FormatMismatch(file, decision.detectedExtension()));
        }
      }
    }
    return new DiscoveredFiles(supported, rejected, mismatches);
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
   * Whether {@code file} is accepted for indexing, decided from its actual content - see {@link
   * SupportedDocumentFormats#decideForFileName}. A file that cannot even be read for detection
   * (deleted or permission-denied between {@link #discoverFiles}'s own walk and this call) is
   * treated as unsupported rather than propagating the {@link IOException}.
   */
  boolean isSupportedFormat(Path file) {
    return classify(file).supported();
  }

  /**
   * Whether {@code parsed} carries no extractable text at all and {@code file} was detected as a
   * PDF. Tika's PDF parser returns a {@link org.springframework.ai.document.Document} even for a
   * scan without a text layer - just with blank text - so {@code parsed.isEmpty()} alone does not
   * catch this case. Scoped to PDF for now; meant to extend to TIFF/PNG/JPEG once accepted.
   */
  public boolean isTextlessPdf(Path file, List<org.springframework.ai.document.Document> parsed) {
    boolean hasText = parsed.stream().anyMatch(d -> d.getText() != null && !d.getText().isBlank());
    if (hasText) {
      return false;
    }
    return isPdf(file);
  }

  private boolean isPdf(Path file) {
    try {
      return SupportedDocumentFormats.isPdfContent(SupportedDocumentFormats.detectMediaType(file));
    } catch (IOException e) {
      log.warn("Could not read {} to detect whether it is a PDF", file, e);
      return false;
    }
  }

  private SupportedDocumentFormats.ContentDecision classify(Path file) {
    try {
      String detectedMimeType = SupportedDocumentFormats.detectMediaType(file);
      return SupportedDocumentFormats.decideForFileName(
          file.getFileName().toString(), detectedMimeType);
    } catch (IOException e) {
      log.warn("Could not read {} to detect its format, treating it as unsupported", file, e);
      return SupportedDocumentFormats.decideForFileName(file.getFileName().toString(), null);
    }
  }
}
