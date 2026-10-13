package io.opaa.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import io.opaa.eval.EvalEmbeddingContinuity.Probe;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Docker-free guard for the decision {@link EvalEmbeddingContinuity} takes over its probes. */
class EvalEmbeddingContinuityTest {

  private static final double[] A = {1.0, 0.0, 0.0};
  private static final double[] A_NUDGED = {1.0, 0.01, 0.0};
  private static final double[] TURNED = {0.41, 0.912, 0.0};

  @Test
  void continuousProbesAcrossTheBoundaryPassWithTheirLowestNeighbourCosine() {
    List<Probe> probes =
        List.of(new Probe(1200, 492, A), new Probe(1250, 511, A_NUDGED), new Probe(1300, 529, A));

    assertThat(EvalEmbeddingContinuity.requireContinuous(probes, "m"))
        .isCloseTo(0.99995, within(1e-4));
  }

  /** The measurement of ollama/ollama:0.6.5 in issue #2343: 0.41 at 511 tokens. */
  @Test
  void aVectorThatTurnsAtTheBoundaryIsRefused() {
    List<Probe> probes =
        List.of(new Probe(1240, 508, A), new Probe(1250, 511, TURNED), new Probe(1260, 515, A));

    assertThatThrownBy(() -> EvalEmbeddingContinuity.requireContinuous(probes, "nomic"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("discontinuously")
        .hasMessageContaining("1250 characters (511 tokens)")
        .hasMessageContaining("every chunk beyond 512 tokens")
        .hasMessageContaining("nomic");
  }

  @Test
  void probesThatDoNotStraddleTheBoundaryProveNothingAndAreRefused() {
    List<Probe> below = List.of(new Probe(1000, 400, A), new Probe(1100, 450, A));
    List<Probe> above = List.of(new Probe(1400, 520, A), new Probe(1500, 600, A));

    assertThatThrownBy(() -> EvalEmbeddingContinuity.requireContinuous(below, "m"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("must straddle 512 tokens");
    assertThatThrownBy(() -> EvalEmbeddingContinuity.requireContinuous(above, "m"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("must straddle 512 tokens");
    assertThatThrownBy(() -> EvalEmbeddingContinuity.requireContinuous(List.of(), "m"))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void theProbeFileIsLongEnoughForTheLastPrefix() throws Exception {
    String text =
        EvalEmbeddingContinuity.probeText(
            RepoPaths.evalDir().resolve(EvalEmbeddingContinuity.PROBE_FILE));

    assertThat(text).doesNotContain("\r");
    assertThat(text.length()).isGreaterThanOrEqualTo(EvalEmbeddingContinuity.LAST_PREFIX_CHARS);
  }

  @Test
  void cosineOfIdenticalAndOrthogonalVectors() {
    assertThat(EvalEmbeddingContinuity.cosine(A, A)).isCloseTo(1.0, within(1e-12));
    assertThat(EvalEmbeddingContinuity.cosine(A, new double[] {0.0, 1.0, 0.0}))
        .isCloseTo(0.0, within(1e-12));
    assertThatThrownBy(() -> EvalEmbeddingContinuity.cosine(A, new double[] {1.0}))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
