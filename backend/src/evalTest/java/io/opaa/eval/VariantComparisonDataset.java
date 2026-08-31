package io.opaa.eval;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import tools.jackson.databind.json.JsonMapper;

/**
 * Loads a {@link VariantComparison} from {@code eval/variants/<file>.json} (issue #1041). Mirrors
 * {@link GoldenDataset}'s loading pattern: a comparison is data in the repository, not a Java
 * class, so triggering a different comparison is a file argument, never a code change (issue #1041
 * acceptance criteria; see {@code VariantComparisonHarnessTest} for the system property that
 * selects the file).
 */
public final class VariantComparisonDataset {

  private VariantComparisonDataset() {}

  public static VariantComparison load(Path jsonFile) throws IOException {
    byte[] bytes = Files.readAllBytes(jsonFile);
    return JsonMapper.builder().build().readValue(bytes, VariantComparison.class);
  }
}
