package io.opaa.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.opaa.api.types.ToolCapability;
import io.opaa.api.types.ToolCapabilityBasis;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataAccessResourceFailureException;

/** An unverified active chat model is named at start; the start itself goes on. */
@ExtendWith(OutputCaptureExtension.class)
class ToolCapabilityStartupWarningTest {

  private final LlmModelRepository repository = mock(LlmModelRepository.class);
  private final ToolCapabilityStartupWarning startupWarning =
      new ToolCapabilityStartupWarning(repository);

  @Test
  void anUnverifiedActiveModelIsWarnedAbout(CapturedOutput output) {
    when(repository.findAllByActiveTrue()).thenReturn(List.of(model(ToolCapability.UNVERIFIED)));

    startupWarning.warnAboutAnUnverifiedActiveModel();

    assertThat(output.getOut())
        .contains("WARN")
        .contains("'qwen2.5:14b'")
        .contains("UNVERIFIED")
        .contains("The ordinary chat is not affected");
  }

  @Test
  void aCheckedActiveModelOrNoneNeedsNoWarning() {
    assertThat(ToolCapabilityStartupWarning.warning(List.of(model(ToolCapability.CAPABLE))))
        .isEmpty();
    assertThat(ToolCapabilityStartupWarning.warning(List.of(model(ToolCapability.NOT_CAPABLE))))
        .isEmpty();
    assertThat(ToolCapabilityStartupWarning.warning(List.of())).isEmpty();
  }

  @Test
  void aFailingReadDoesNotStopTheStart(CapturedOutput output) {
    when(repository.findAllByActiveTrue())
        .thenThrow(new DataAccessResourceFailureException("database gone"));

    assertThatCode(startupWarning::warnAboutAnUnverifiedActiveModel).doesNotThrowAnyException();
    assertThat(output.getOut()).contains("Could not read the tool capability");
  }

  private static LlmModel model(ToolCapability capability) {
    LlmModel model =
        new LlmModel(
            "Ollama", "http://ollama:11434/v1", "qwen2.5:14b", new BigDecimal("0.70"), 2000, null);
    if (capability != ToolCapability.UNVERIFIED) {
      model.assessToolCapability(
          capability, ToolCapabilityBasis.MANUAL, null, Instant.parse("2026-11-09T10:00:00Z"));
    }
    return model;
  }
}
