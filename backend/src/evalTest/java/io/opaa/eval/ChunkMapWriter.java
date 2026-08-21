package io.opaa.eval;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes the {@link ChunkMap.DocumentChunkMap} list a run produces as a JSON artifact (issue #721),
 * next to {@code retrieval-metrics.json} — not committed, rewritten on every {@code
 * evaluateRetrieval} run. Grounds for a future golden-case curator to pick {@code answer_span}
 * cases deliberately close to a chunk boundary (#234), and itself a measurement result, not a
 * generator assumption (ADR-0010: the corpus generator deliberately does not count tokens).
 */
public final class ChunkMapWriter {

  private ChunkMapWriter() {}

  public static void write(List<ChunkMap.DocumentChunkMap> chunkMaps, Path target)
      throws IOException {
    Files.createDirectories(target.getParent());
    JsonMapper mapper = JsonMapper.builder().build();
    Files.writeString(
        target,
        mapper.writerWithDefaultPrettyPrinter().writeValueAsString(chunkMaps),
        StandardCharsets.UTF_8);
  }
}
