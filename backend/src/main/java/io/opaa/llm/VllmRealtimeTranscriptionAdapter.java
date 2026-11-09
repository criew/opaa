package io.opaa.llm;

import io.opaa.api.types.RealtimeTranscriptionProtocol;
import java.net.URI;
import java.util.Base64;
import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * vLLM's {@code /v1/realtime} endpoint, e.g. with Voxtral-Mini-4B-Realtime: {@code session.update}
 * naming the model, {@code input_audio_buffer.commit} to start, {@code input_audio_buffer.append}
 * with Base64 PCM16 at 16 kHz, {@code input_audio_buffer.commit} with {@code final: true} at the
 * end. The upstream answers {@code transcription.delta} ({@code delta}) and {@code
 * transcription.done} ({@code text}). Language and vocabulary are not part of this dialect.
 */
@Component
class VllmRealtimeTranscriptionAdapter implements RealtimeTranscriptionAdapter {

  static final int SAMPLE_RATE = 16_000;

  private final ObjectMapper json;

  VllmRealtimeTranscriptionAdapter(ObjectMapper json) {
    this.json = json;
  }

  @Override
  public RealtimeTranscriptionProtocol protocol() {
    return RealtimeTranscriptionProtocol.VLLM;
  }

  @Override
  public int sampleRate() {
    return SAMPLE_RATE;
  }

  @Override
  public URI uri(RealtimeTranscriptionEndpoint endpoint) {
    return RealtimeUris.parse(endpoint.url());
  }

  @Override
  public Dialog open(RealtimeTranscriptionEndpoint endpoint, String language, String vocabulary) {
    String model = endpoint.model();
    return new Dialog() {

      private final StringBuilder text = new StringBuilder();

      @Override
      public List<String> opening() {
        ObjectNode update = json.createObjectNode();
        update.put("type", "session.update");
        update.put("model", model);
        return List.of(json.writeValueAsString(update), "{\"type\":\"input_audio_buffer.commit\"}");
      }

      @Override
      public String audio(byte[] pcm16) {
        ObjectNode message = json.createObjectNode();
        message.put("type", "input_audio_buffer.append");
        message.put("audio", Base64.getEncoder().encodeToString(pcm16));
        return json.writeValueAsString(message);
      }

      @Override
      public List<String> commit() {
        return List.of("{\"type\":\"input_audio_buffer.commit\",\"final\":true}");
      }

      @Override
      public Event receive(JsonNode message) {
        return switch (RealtimeUris.text(message, "type")) {
          case "transcription.delta" -> {
            text.append(RealtimeUris.text(message, "delta"));
            yield new Event.Text(text.toString().strip());
          }
          case "transcription.done" -> new Event.Done(RealtimeUris.text(message, "text").strip());
          case "error" -> new Event.Failed(RealtimeUris.errorReason(message));
          default -> new Event.Other();
        };
      }
    };
  }
}
