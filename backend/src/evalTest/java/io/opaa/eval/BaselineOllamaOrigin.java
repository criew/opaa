package io.opaa.eval;

import java.nio.file.Path;

/**
 * The load-time guard on the {@code ollamaImage} fixed point of every baseline type (issue #1522):
 * a committed baseline must name the pinned Ollama container image its numbers were measured with.
 *
 * <p>Shared by all three baseline loaders rather than copied into each: the marker that makes a run
 * unusable as a baseline is the same on every path, and a second copy is how one of them stops
 * checking it.
 *
 * <p>The two refusals differ in what they prevent. A missing value leaves a baseline unable to say
 * anything about where its vectors came from; a value carrying {@link
 * EvalOllamaEndpoint#EXTERNAL_MARKER_PREFIX} says it explicitly - such a run talked to an external
 * Ollama (possibly GPU-backed, certainly not the pinned container), is not reproducible in CI, and
 * its numbers would be read as a regression on every subsequent comparison.
 */
final class BaselineOllamaOrigin {

  private BaselineOllamaOrigin() {}

  static void requirePinnedContainerImage(String ollamaImage, Path file, String baselineKind) {
    if (ollamaImage == null || ollamaImage.isBlank()) {
      throw new IllegalStateException(
          baselineKind
              + " "
              + file.toAbsolutePath()
              + " carries no fixedPoints.ollamaImage — a committed baseline must name the pinned "
              + "Ollama container image it was measured with, otherwise it cannot show that its "
              + "numbers come from the reproducible CPU/Testcontainer run the measurement contract "
              + "requires (ADR-0012, Nachtrag Ollama-Herkunft).");
    }
    if (EvalOllamaEndpoint.describesExternalEndpoint(ollamaImage)) {
      throw new IllegalStateException(
          baselineKind
              + " "
              + file.toAbsolutePath()
              + " was drawn from a run against an external Ollama endpoint (ollamaImage=\""
              + ollamaImage
              + "\", -D"
              + EvalOllamaEndpoint.BASE_URL_PROPERTY
              + "). Such a run is meant for local iteration and is never baseline-comparable: it "
              + "may embed on a GPU, whose kernels are not guaranteed bit-identical to the CPU "
              + "container, and CI cannot reproduce it — every later comparison would report the "
              + "difference as a regression. Re-measure in the Testcontainer run (see "
              + "eval/README.md, \"Externer Ollama-Endpunkt\").");
    }
  }
}
