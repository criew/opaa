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
   * Everything found below the document directory, split into what will be indexed, what was
   * rejected because of its format, and which of the indexed files carried an extension that did
   * not match their actually detected content. The rejected files are carried out of here on
   * purpose: they belong in the indexing job's counters, not in a filter nobody sees.
   */
  public record DiscoveredFiles(
      List<Path> supported, List<Path> rejected, List<FormatMismatch> mismatches) {

    static DiscoveredFiles empty() {
      return new DiscoveredFiles(List.of(), List.of(), List.of());
    }

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

  public DiscoveredFiles discoverFiles(Path directory) throws IOException {
    if (!Files.exists(directory)) {
      log.warn("Document directory does not exist: {}", directory);
      return DiscoveredFiles.empty();
    }
    if (!Files.isDirectory(directory)) {
      log.warn("Path is not a directory: {}", directory);
      return DiscoveredFiles.empty();
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
