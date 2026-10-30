package io.opaa.fuzz;

import io.opaa.common.ClockConfiguration;
import io.opaa.format.DiscoveredAttachment;
import io.opaa.format.DocumentFormatRegistry;
import io.opaa.format.DocumentFormatResult;
import io.opaa.format.DocumentFormatRunner;
import io.opaa.format.DocumentFormatSource;
import io.opaa.format.config.FormatConfiguration;
import io.opaa.indexing.IndexingProperties;
import io.opaa.indexing.attachment.AttachmentProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;

/**
 * The way an indexed file takes through the formats, wired as the application wires it and
 * configured from {@code application.yml}: routing by content ({@link
 * DocumentFormatRegistry#routedPipelineFor}), the routed format through {@link
 * DocumentFormatRunner} with its discovered attachments under the limits of {@link
 * AttachmentProperties}, and the re-extraction of the document properties. A routing failure is an
 * answer, as {@code DocumentIngestService} books it; what escapes the runner is not.
 */
final class ProductionPath implements AutoCloseable {

  /** What one file came to: the outcome of every document read, and the leftover temp files. */
  record Run(
      String format, List<DocumentFormatResult.Outcome> outcomes, List<Path> leftoverTempFiles) {}

  private final AnnotationConfigApplicationContext context;
  private final DocumentFormatRegistry registry;
  private final AttachmentProperties attachments;

  private ProductionPath(AnnotationConfigApplicationContext context) {
    this.context = context;
    this.registry = context.getBean(DocumentFormatRegistry.class);
    this.attachments = context.getBean(AttachmentProperties.class);
  }

  static ProductionPath start() {
    AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
    new ConfigDataApplicationContextInitializer().initialize(context);
    context.register(FormatConfiguration.class, ClockConfiguration.class, Limits.class);
    context.refresh();
    return new ProductionPath(context);
  }

  DocumentFormatRegistry registry() {
    return registry;
  }

  /** {@code file} under {@code fileName} through the whole path, attachments included. */
  Run run(Path file, String fileName) {
    List<DocumentFormatResult.Outcome> outcomes = new ArrayList<>();
    List<Path> leftovers = new ArrayList<>();
    String format = run(file, fileName, 0, new Budget(), outcomes, leftovers);
    return new Run(format, outcomes, leftovers);
  }

  /** The id of the format {@code file} was routed to, {@code "unrouted"} without one. */
  private String run(
      Path file,
      String fileName,
      int depth,
      Budget budget,
      List<DocumentFormatResult.Outcome> outcomes,
      List<Path> leftovers) {
    DocumentFormatRegistry.Routed routed;
    try {
      routed = registry.routedPipelineFor(file, fileName);
    } catch (RuntimeException e) {
      outcomes.add(DocumentFormatResult.Outcome.PARSE_FAILED);
      return "unrouted";
    }
    DocumentFormatSource source =
        DocumentFormatSource.ofFile(file, fileName, routed.detectedExtension());
    List<DiscoveredAttachment> discovered = new ArrayList<>();
    DocumentFormatResult result =
        DocumentFormatRunner.run(
            routed.pipeline(),
            source,
            parsed -> {
              discovered.addAll(parsed.discoveredAttachments());
              if (depth < attachments.maxDepth()) {
                int perParent = 0;
                for (DiscoveredAttachment attachment : parsed.discoveredAttachments()) {
                  long size = sizeOf(attachment.tempFile());
                  if (++perParent > attachments.maxPerParent()
                      || size > attachments.maxSizeBytes()
                      || !budget.admit(size, attachments)) {
                    continue;
                  }
                  run(
                      attachment.tempFile(),
                      attachment.fileName(),
                      depth + 1,
                      budget,
                      outcomes,
                      leftovers);
                }
              }
            });
    outcomes.add(result.outcome());
    for (DiscoveredAttachment attachment : discovered) {
      if (Files.exists(attachment.tempFile())) {
        leftovers.add(attachment.tempFile());
      }
    }
    routed.pipeline().readProperties(source);
    return routed.pipeline().id();
  }

  private static long sizeOf(Path file) {
    try {
      return Files.size(file);
    } catch (IOException e) {
      return 0;
    }
  }

  @Override
  public void close() {
    context.close();
  }

  /** The attachment budget of one root document over every level, as AttachmentIndexer keeps it. */
  private static final class Budget {
    private int count;
    private long bytes;

    boolean admit(long size, AttachmentProperties limits) {
      if (count + 1 > limits.maxPerRoot() || bytes + size > limits.maxBytesPerRoot()) {
        return false;
      }
      count++;
      bytes += size;
      return true;
    }
  }

  /** The bound properties the formats need from the indexing configuration. */
  @Configuration
  @EnableConfigurationProperties({IndexingProperties.class, AttachmentProperties.class})
  static class Limits {}
}
