package io.opaa.eval;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import tools.jackson.databind.json.JsonMapper;

/** Loads and fingerprints the golden-query dataset (see {@link GoldenCase}). */
public final class GoldenDataset {

  private GoldenDataset() {}

  public static List<GoldenCase> load(Path jsonFile) throws IOException {
    byte[] bytes = Files.readAllBytes(jsonFile);
    GoldenCase[] cases = JsonMapper.builder().build().readValue(bytes, GoldenCase[].class);
    return List.of(cases);
  }

  /**
   * SHA-256 of the dataset file, reported alongside the metrics so a report can be traced back to
   * the exact golden-dataset version it was measured against (issue #227 acceptance criteria).
   */
  public static String sha256(Path jsonFile) throws IOException {
    return CorpusManifest.sha256Hex(jsonFile);
  }
}
