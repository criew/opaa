package io.opaa.test;

import io.opaa.FakeEmbeddingModel;
import io.opaa.llm.EmbeddingEndpoint;
import io.opaa.llm.EmbeddingModelService.ProbeOutcome;
import io.opaa.llm.EmbeddingProbe;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;

/**
 * The probe embedding of the test suite, without a network: by default every endpoint answers with
 * the vectors of {@link FakeEmbeddingModel}, so the probe of an installed model always matches its
 * anchor. A test can make endpoints answer otherwise with {@link #answerWith}; {@link #reset} goes
 * back to the default before every test method.
 */
public final class FakeEmbeddingProbe implements EmbeddingProbe {

  private final AtomicReference<BiFunction<EmbeddingEndpoint, List<String>, ProbeOutcome>> answer =
      new AtomicReference<>();

  @Override
  public ProbeOutcome probe(EmbeddingEndpoint endpoint, List<String> texts) {
    BiFunction<EmbeddingEndpoint, List<String>, ProbeOutcome> scripted = answer.get();
    return scripted == null
        ? FakeEmbeddingModel.probeOutcome(texts)
        : scripted.apply(endpoint, texts);
  }

  /** Answers every probe from now on with {@code scripted}. */
  public void answerWith(BiFunction<EmbeddingEndpoint, List<String>, ProbeOutcome> scripted) {
    answer.set(scripted);
  }

  public void reset() {
    answer.set(null);
  }
}
