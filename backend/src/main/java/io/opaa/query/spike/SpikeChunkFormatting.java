package io.opaa.query.spike;

import java.util.List;
import java.util.stream.Collectors;
import org.springframework.ai.document.Document;

/**
 * The passage format the {@code search_knowledge} tool (#1789) returns to the model - deliberately
 * the same header and citation-marker shape {@code
 * io.opaa.query.answer.AnswerGenerationService#formatChunks} builds for the ordinary answer path,
 * so the model driving the tool loop learns to cite exactly as the ordinary path does.
 */
final class SpikeChunkFormatting {

  private static final String CITATION_FORMAT = "【source: %s#%s | %s】";

  private SpikeChunkFormatting() {}

  static String formatChunks(List<Document> chunks) {
    if (chunks.isEmpty()) {
      return "Keine Treffer.";
    }
    return chunks.stream()
        .map(
            chunk -> {
              String fileName = chunk.getMetadata().getOrDefault("file_name", "unknown").toString();
              String documentId = chunk.getMetadata().getOrDefault("document_id", "").toString();
              String chunkIndex = chunk.getMetadata().getOrDefault("chunk_index", "0").toString();
              String header =
                  "[Quelle: "
                      + fileName
                      + ", document_id: "
                      + documentId
                      + ", chunk_index: "
                      + chunkIndex
                      + ", zitieren als: "
                      + String.format(CITATION_FORMAT, documentId, chunkIndex, fileName)
                      + "]\n";
              return header + chunk.getText();
            })
        .collect(Collectors.joining("\n\n---\n\n"));
  }
}
