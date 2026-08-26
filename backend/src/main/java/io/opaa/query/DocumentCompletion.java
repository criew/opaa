package io.opaa.query;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.ai.document.Document;

/**
 * Completes a post-fusion/post-MMR chunk selection with sibling chunks of documents it already
 * represents (#932, Lösungsrichtung 1 of #912's follow-up): once a document has a chunk in {@code
 * selection}, up to {@link QueryProperties#maxChunksPerDocument} of its chunks from {@code
 * candidatePool} are preferred over a second, third, ... chunk of a <em>different</em> document
 * that would otherwise fill the remaining budget - the failure mode where a document's true-but-
 * lower-ranked answer (e.g. a fee table) loses its slot to an unrelated document's chunk merely
 * because RRF/MMR spread the budget across topics before completeness within one document was
 * considered. {@code candidatePool} must already be permission- and threshold-filtered (the same
 * pool {@code similaritySearch} produced for {@code selection} itself) - this class only ever
 * reorders/replaces within that set, never searches or admits anything beyond it.
 *
 * <p>Filling never grows {@code selection} past {@code overallBudget}: once full, a candidate can
 * only be admitted by evicting the globally weakest chunk of some <em>other</em> document that
 * already holds at least two selected chunks - the diversity floor established by the fusion/MMR
 * step is never reduced below "at least two documents keep more than one chunk each" for the sake
 * of a third. No such eviction candidate leaves {@code selection} unchanged from that point on.
 */
final class DocumentCompletion {

  private DocumentCompletion() {}

  static List<Document> complete(
      List<Document> selection,
      List<Document> candidatePool,
      int maxChunksPerDocument,
      int overallBudget) {
    if (selection.isEmpty() || maxChunksPerDocument <= 1) {
      return selection;
    }

    List<Document> result = new ArrayList<>(selection);
    Set<String> selectedChunkIds =
        result.stream().map(Document::getId).collect(Collectors.toCollection(LinkedHashSet::new));
    Map<String, List<Document>> unusedCandidatesByDocument =
        unusedCandidatesByDocument(candidatePool, selectedChunkIds);
    List<String> documentOrder = distinctDocumentOrder(result);

    for (String documentKey : documentOrder) {
      List<Document> unused = unusedCandidatesByDocument.get(documentKey);
      if (unused == null) {
        continue;
      }
      for (Document candidate : unused) {
        if (countForDocument(result, documentKey) >= maxChunksPerDocument) {
          break;
        }
        if (result.size() < overallBudget) {
          result.add(candidate);
        } else if (evictWeakestFromAnOverrepresentedDocument(result, documentKey)) {
          result.add(candidate);
        } else {
          // No document currently holds a second chunk it could give up - the budget stays exactly
          // as it is, and no further completion (for this or any later document) can succeed
          // either.
          return result;
        }
      }
    }
    return result;
  }

  /**
   * The distinct document keys represented in {@code selection}, in first-appearance order - the
   * order completion attempts documents in, so an earlier-ranked document's completeness is
   * preferred over a later-ranked one's when the budget runs out.
   */
  private static List<String> distinctDocumentOrder(List<Document> selection) {
    return selection.stream()
        .map(QueryService::chunkGroupingKey)
        .distinct()
        .collect(Collectors.toList());
  }

  private static long countForDocument(List<Document> selection, String documentKey) {
    return selection.stream()
        .filter(d -> QueryService.chunkGroupingKey(d).equals(documentKey))
        .count();
  }

  /**
   * Groups every candidate not already in {@code selection} by document, deduplicated by chunk id
   * (the same chunk can appear once per sub-query in a pooled multi-query candidate list) and
   * sorted by relevance score descending within each document, so the strongest sibling chunk is
   * always tried first.
   */
  private static Map<String, List<Document>> unusedCandidatesByDocument(
      List<Document> candidatePool, Set<String> selectedChunkIds) {
    Map<String, Document> byChunkId = new LinkedHashMap<>();
    for (Document candidate : candidatePool) {
      if (selectedChunkIds.contains(candidate.getId())) {
        continue;
      }
      byChunkId.merge(candidate.getId(), candidate, DocumentCompletion::preferHigherScore);
    }
    Map<String, List<Document>> byDocument =
        byChunkId.values().stream()
            .collect(
                Collectors.groupingBy(
                    QueryService::chunkGroupingKey, LinkedHashMap::new, Collectors.toList()));
    byDocument.replaceAll(
        (documentKey, chunks) ->
            chunks.stream()
                .sorted(Comparator.comparingDouble(DocumentCompletion::scoreOf).reversed())
                .toList());
    return byDocument;
  }

  /**
   * Removes the globally weakest chunk of some document other than {@code excludeDocumentKey} that
   * currently holds at least two chunks in {@code result} - the eviction rule that keeps document
   * diversity from ever shrinking below what fusion/MMR already established (see this class's
   * Javadoc). Returns {@code false}, leaving {@code result} unchanged, when no such document
   * exists.
   */
  private static boolean evictWeakestFromAnOverrepresentedDocument(
      List<Document> result, String excludeDocumentKey) {
    Map<String, List<Document>> byDocument =
        result.stream()
            .collect(
                Collectors.groupingBy(
                    QueryService::chunkGroupingKey, LinkedHashMap::new, Collectors.toList()));
    Document weakest = null;
    for (Map.Entry<String, List<Document>> entry : byDocument.entrySet()) {
      if (entry.getKey().equals(excludeDocumentKey) || entry.getValue().size() < 2) {
        continue;
      }
      for (Document candidate : entry.getValue()) {
        if (weakest == null || scoreOf(candidate) < scoreOf(weakest)) {
          weakest = candidate;
        }
      }
    }
    if (weakest == null) {
      return false;
    }
    result.remove(weakest);
    return true;
  }

  private static double scoreOf(Document document) {
    Double score = document.getScore();
    return score != null ? score : 0.0;
  }

  /** {@code null} scores lose to any non-null one; between two non-null scores, the higher wins. */
  private static Document preferHigherScore(Document a, Document b) {
    Double scoreA = a.getScore();
    Double scoreB = b.getScore();
    if (scoreA == null) {
      return b;
    }
    if (scoreB == null) {
      return a;
    }
    return scoreA >= scoreB ? a : b;
  }
}
