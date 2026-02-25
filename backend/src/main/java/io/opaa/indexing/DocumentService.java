package io.opaa.indexing;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.core.io.FileSystemResource;

public class DocumentService {

  private static final Logger log = LoggerFactory.getLogger(DocumentService.class);

  private static final Set<String> SUPPORTED_EXTENSIONS =
      Set.of(".md", ".txt", ".pdf", ".docx", ".pptx");

  public List<Path> discoverFiles(Path directory) throws IOException {
    if (!Files.exists(directory)) {
      log.warn("Document directory does not exist: {}", directory);
      return List.of();
    }
    if (!Files.isDirectory(directory)) {
      log.warn("Path is not a directory: {}", directory);
      return List.of();
    }
    try (Stream<Path> walk = Files.walk(directory)) {
      return walk.filter(Files::isRegularFile).filter(this::isSupportedFormat).toList();
    }
  }

  public List<org.springframework.ai.document.Document> parseDocument(Path file) {
    log.debug("Parsing document: {}", file);
    var resource = new FileSystemResource(file);
    var reader = new TikaDocumentReader(resource);
    return reader.read();
  }

  boolean isSupportedFormat(Path file) {
    String name = file.getFileName().toString().toLowerCase();
    return SUPPORTED_EXTENSIONS.stream().anyMatch(name::endsWith);
  }
}
