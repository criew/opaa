package io.opaa.eval;

import io.opaa.indexing.pipeline.DocumentPipeline;
import io.opaa.indexing.pipeline.DocumentPipelineRegistry;
import java.util.Comparator;
import java.util.stream.Collectors;

/**
 * The collective fixed point recording which ingestion pipelines produced a corpus's chunks (issue
 * #1144). Neither {@code corpusManifestSha256} nor {@code goldenDatasetSha256} answer that question
 * — both describe the input, not what processed it — so a pipeline change (a routing change, or a
 * version bump on a pipeline the corpus actually uses) moved a baseline's numbers without a single
 * fixed point noticing, indistinguishable from a retrieval regression.
 *
 * <p>A canonical, sorted string rather than a hash, matching {@code embeddingModel} (kept alongside
 * its digest) rather than {@code corpusManifestSha256} (a hash, because a document listing would be
 * unreadably large): ten pipelines fit a git diff a reviewer can read at a glance, and a diff that
 * names <em>which</em> pipeline moved is strictly more useful here than one that only says
 * "something changed".
 */
final class IngestionPipelineFingerprint {

  private IngestionPipelineFingerprint() {}

  /**
   * {@code "id:version"} for every pipeline {@code registry} knows (including the fallback),
   * comma-joined and sorted by id — deterministic regardless of bean registration order.
   */
  static String of(DocumentPipelineRegistry registry) {
    return registry.pipelines().stream()
        .sorted(Comparator.comparing(DocumentPipeline::id))
        .map(pipeline -> pipeline.id() + ":" + pipeline.version())
        .collect(Collectors.joining(","));
  }
}
