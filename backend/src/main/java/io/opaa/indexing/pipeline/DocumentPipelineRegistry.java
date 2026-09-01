package io.opaa.indexing.pipeline;

import io.opaa.indexing.SupportedDocumentFormats;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Routes an admitted document to the {@link DocumentPipeline} responsible for its format
 * (docs/features/ingestion-pipelines.md, Teil 1).
 *
 * <p><b>Routing follows the detected content, not the file extension</b> - the same rule and the
 * same code path {@link SupportedDocumentFormats#decideForFileName} already applies to the
 * admission decision (#404), and for the same reason: in grown file shares documents routinely
 * carry the wrong extension. The text-tolerant special case for Markdown and Klartext (content
 * <em>and</em> extension must agree, because the two cannot be told apart by content alone)
 * therefore holds for routing too - it is not re-implemented here, it is inherited by asking {@link
 * SupportedDocumentFormats} the one question it already answers.
 *
 * <p><b>No second admission list.</b> A document that {@link SupportedDocumentFormats} does not
 * admit never reaches this class; a document it admits always gets a pipeline, the fallback one if
 * no specialized pipeline claimed its format.
 *
 * <p>Two pipelines claiming the same format is a wiring mistake, not a precedence question, and
 * fails at context startup rather than silently letting bean ordering decide.
 */
public class DocumentPipelineRegistry {

  private static final Logger log = LoggerFactory.getLogger(DocumentPipelineRegistry.class);

  private final Map<String, DocumentPipeline> byFormat;
  private final DocumentPipeline fallback;
  private final List<DocumentPipeline> all;

  /**
   * @param pipelines every registered pipeline, including {@code fallback} itself when it is a bean
   *     of the same type - it is filtered out of the format claims here rather than at every call
   *     site, so a future pipeline is registered by declaring a bean and nothing else
   */
  public DocumentPipelineRegistry(List<DocumentPipeline> pipelines, DocumentPipeline fallback) {
    this.fallback = fallback;
    // The fallback is reported among the registered pipelines even if a caller passed a list that
    // does not contain it - it is a pipeline chunks are attributed to, so a status view that omits
    // it would report those chunks as belonging to a pipeline this deployment does not have.
    this.all =
        pipelines.contains(fallback)
            ? pipelines.stream().distinct().toList()
            : java.util.stream.Stream.concat(
                    pipelines.stream(), java.util.stream.Stream.of(fallback))
                .distinct()
                .toList();
    Map<String, DocumentPipeline> claims = new HashMap<>();
    for (DocumentPipeline pipeline : all) {
      if (pipeline == fallback) {
        continue;
      }
      for (String format : pipeline.handledFormats()) {
        DocumentPipeline previous = claims.put(format, pipeline);
        if (previous != null) {
          throw new IllegalStateException(
              "Two document pipelines claim format "
                  + format
                  + ": "
                  + previous.id()
                  + " and "
                  + pipeline.id());
        }
      }
    }
    this.byFormat = Map.copyOf(claims);
  }

  /**
   * The pipeline and the extension it was routed on, for {@code file} - the pipeline result of
   * {@link #pipelineFor(Path, String)} paired with the same {@link
   * SupportedDocumentFormats.ContentDecision#detectedExtension()} that decided it, so a caller can
   * hand both to {@link DocumentPipelineSource#ofFile(Path, String, String)}. Kept as a nested
   * record rather than a two-element return, so a future addition to what routing resolves does not
   * ripple through every call site's argument list.
   *
   * @param detectedExtension {@code null} exactly when {@code pipeline} is the fallback because
   *     routing could not resolve one (detection failed, or the content is not admitted at all -
   *     see {@link #pipelineFor(String, String)})
   */
  public record Routed(DocumentPipeline pipeline, String detectedExtension) {}

  /**
   * The pipeline for {@code file}, decided from its bytes. A file whose content cannot be read for
   * detection (deleted or permission-denied since it was discovered) falls back rather than failing
   * here - the subsequent read attempt inside the pipeline reports the real problem.
   */
  public DocumentPipeline pipelineFor(Path file, String fileName) {
    return routedPipelineFor(file, fileName).pipeline();
  }

  /**
   * Like {@link #pipelineFor(Path, String)}, but also returns the extension the routing decision
   * resolved to - see {@link Routed}'s own Javadoc for why a pipeline needs it at all.
   */
  public Routed routedPipelineFor(Path file, String fileName) {
    try {
      return routedPipelineFor(fileName, SupportedDocumentFormats.detectMediaType(file));
    } catch (IOException e) {
      log.warn("Could not read {} to route it to a pipeline, using the fallback pipeline", file, e);
      return new Routed(fallback, null);
    }
  }

  /**
   * The pipeline for a document with this name whose content Tika detected as {@code mediaType}.
   */
  public DocumentPipeline pipelineFor(String fileName, String detectedMediaType) {
    return routedPipelineFor(fileName, detectedMediaType).pipeline();
  }

  private Routed routedPipelineFor(String fileName, String detectedMediaType) {
    SupportedDocumentFormats.ContentDecision decision =
        SupportedDocumentFormats.decideForFileName(fileName, detectedMediaType);
    if (!decision.supported()) {
      return new Routed(fallback, null);
    }
    DocumentPipeline pipeline = byFormat.getOrDefault(decision.detectedExtension(), fallback);
    return new Routed(pipeline, decision.detectedExtension());
  }

  /**
   * The pipeline for content that never was a file and therefore has no detectable format - an RSS
   * entry's already-extracted main text (see {@code FileProcessingService#processRssEntry}).
   */
  public DocumentPipeline fallbackPipeline() {
    return fallback;
  }

  /** Every registered pipeline - the source of the reported current versions. */
  public Collection<DocumentPipeline> pipelines() {
    return all;
  }
}
