package io.opaa.installation;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.api.types.RealtimeTranscriptionProtocol;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** The example answer files of the repository read as answer files, with the parts they show. */
class AnswerFileExamplesTest {

  private static final Path EXAMPLES = Path.of("..", "installation");

  @Test
  void theLiveTranscriptionExampleNamesItsServiceFromTheFixedList() {
    AnswerFile answers =
        AnswerFile.read(
            EXAMPLES.resolve("installation.live-transcription.example.json"),
            JsonMapper.builder().build());

    assertThat(answers.transcriptionModel().realtime().protocol())
        .isEqualTo(RealtimeTranscriptionProtocol.VLLM);
    assertThat(answers.transcriptionModel().realtime().url()).startsWith("ws://");
  }

  @Test
  void theDefaultExampleReadsWithoutSpeechInput() {
    AnswerFile answers =
        AnswerFile.read(
            EXAMPLES.resolve("installation.example.json"), JsonMapper.builder().build());

    assertThat(answers.transcriptionModel()).isNull();
  }
}
