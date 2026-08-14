package io.opaa.indexing;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.core.io.FileSystemResource;

public class DocumentService {

  private static final Logger log = LoggerFactory.getLogger(DocumentService.class);

  /**
   * Everything found below the document directory, split into what will be indexed and what was
   * rejected because of its format. The rejected files are carried out of here on purpose: they
   * belong in the indexing job's counters, not in a filter nobody sees (issue #375).
   */
  public record DiscoveredFiles(List<Path> supported, List<Path> rejected) {

    static DiscoveredFiles empty() {
      return new DiscoveredFiles(List.of(), List.of());
    }

    public int totalFound() {
      return supported.size() + rejected.size();
    }
  }

  public DiscoveredFiles discoverFiles(Path directory) throws IOException {
    if (!Files.exists(directory)) {
      log.warn("Document directory does not exist: {}", directory);
      return DiscoveredFiles.empty();
    }
    if (!Files.isDirectory(directory)) {
      log.warn("Path is not a directory: {}", directory);
      return DiscoveredFiles.empty();
    }
    try (Stream<Path> walk = Files.walk(directory)) {
      Map<Boolean, List<Path>> partitioned =
          walk.filter(Files::isRegularFile)
              .collect(Collectors.partitioningBy(this::isSupportedFormat));
      return new DiscoveredFiles(
          partitioned.getOrDefault(true, List.of()), partitioned.getOrDefault(false, List.of()));
    }
  }

  public List<org.springframework.ai.document.Document> parseDocument(Path file) {
    log.debug("Parsing document: {}", file);
    var resource = new FileSystemResource(file);
    var reader = new TikaDocumentReader(resource);
    return reader.read();
  }

  boolean isSupportedFormat(Path file) {
    return SupportedDocumentFormats.isSupported(file.getFileName().toString());
  }
}
