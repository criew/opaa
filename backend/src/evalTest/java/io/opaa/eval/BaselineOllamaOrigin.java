package io.opaa.eval;

import java.nio.file.Path;

/**
 * The load-time guard on the {@code ollamaImage} fixed point of every baseline type (issue #1522):
 * a file drawn from a run against an external Ollama endpoint is refused instead of becoming a
 * comparison point at all.
 *
 * <p>This is a statement about the <b>file</b>, not about any later run. {@code
 * requireBaselineComparable} already stops an external <i>report</i> before it reaches a comparison
 * (all three paths call it before loading); what it cannot do is notice that the committed baseline
 * itself came from such a run. That is a drawing error, and the only correct outcome is to
 * re-measure — which a fixed-point mismatch, worded "measurement grounds changed", would not say.
 *
 * <p>Shared by all three baseline loaders rather than copied into each: the marker that makes a run
 * unusable as a baseline is the same on every path, and a second copy is how one of them stops
 * checking it.
 *
 * <p>A <b>missing</b> value is deliberately not refused here. It deserializes to {@code null} and
 * is reported by the comparator as an incomparable fixed point ({@code null} vs. the pinned image),
 * the same treatment {@code metadataFilterEnabled} gets and for the same reason: the regression job
 * then still writes its delta table naming the offending field, instead of dying with an exception
 * and no report.
 */
final class BaselineOllamaOrigin {

  private BaselineOllamaOrigin() {}

  static void refuseExternalOrigin(String ollamaImage, Path file, String baselineKind) {
    if (EvalOllamaEndpoint.describesExternalEndpoint(ollamaImage)) {
      throw new IllegalStateException(
          baselineKind
              + " "
              + file.toAbsolutePath()
              + " wurde aus einem Lauf gegen einen externen Ollama-Endpunkt gezogen (ollamaImage=\""
              + ollamaImage
              + "\", -D"
              + EvalOllamaEndpoint.BASE_URL_PROPERTY
              + ") — ein solcher Lauf ist nie baseline-tauglich: Er bettet möglicherweise auf der "
              + "GPU ein, deren Kernel nicht bitgleich zum CPU-Container rechnen, und die CI kann "
              + "ihn nicht reproduzieren. Die Baseline muss im Testcontainer-Lauf neu gezogen "
              + "werden. Siehe eval/README.md, \"Externer Ollama-Endpunkt\".");
    }
  }
}
