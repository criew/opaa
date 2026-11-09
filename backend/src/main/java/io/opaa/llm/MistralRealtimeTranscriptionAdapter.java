package io.opaa.llm;

import io.opaa.api.types.RealtimeTranscriptionProtocol;
import java.net.URI;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Mistral's realtime transcription ({@code wss://api.mistral.ai/v1/audio/transcriptions/realtime},
 * the model in the query): {@code session.update} with {@code audio_format} PCM16 at 16 kHz,
 * language and the vocabulary as {@code context_bias}, {@code input_audio.append} with Base64
 * audio, {@code input_audio.end} at the end. The upstream answers {@code transcription.text.delta}
 * with the added text and {@code transcription.done} with the whole text.
 */
@Component
class MistralRealtimeTranscriptionAdapter implements RealtimeTranscriptionAdapter {

  static final int SAMPLE_RATE = 16_000;

  /** The most terms passed as {@code context_bias}. */
  static final int MAX_BIAS_TERMS = 100;

  private final ObjectMapper json;

  MistralRealtimeTranscriptionAdapter(ObjectMapper json) {
    this.json = json;
  }

  @Override
  public RealtimeTranscriptionProtocol protocol() {
    return RealtimeTranscriptionProtocol.MISTRAL;
  }

  @Override
  public int sampleRate() {
    return SAMPLE_RATE;
  }

  @Override
  public URI uri(RealtimeTranscriptionEndpoint endpoint) {
    return RealtimeUris.withQueryParameter(endpoint.url(), "model", endpoint.model());
  }

  @Override
  public Dialog open(RealtimeTranscriptionEndpoint endpoint, String language, String vocabulary) {
    return new Dialog() {

      private final StringBuilder text = new StringBuilder();

      @Override
      public List<String> opening() {
        ObjectNode message = json.createObjectNode();
        message.put("type", "session.update");
        ObjectNode session = message.putObject("session");
        ObjectNode format = session.putObject("audio_format");
        format.put("encoding", "pcm_s16le");
        format.put("sample_rate", SAMPLE_RATE);
        if (!language.isEmpty()) {
          session.put("language", language);
        }
        List<String> terms =
            Arrays.stream(vocabulary.split("[,;\\n]"))
                .map(String::strip)
                .filter(term -> !term.isEmpty())
                .limit(MAX_BIAS_TERMS)
                .toList();
        if (!terms.isEmpty()) {
          ArrayNode bias = session.putArray("context_bias");
          terms.forEach(bias::add);
        }
        return List.of(json.writeValueAsString(message));
      }

      @Override
      public String audio(byte[] pcm16) {
        ObjectNode message = json.createObjectNode();
        message.put("type", "input_audio.append");
        message.put("audio", Base64.getEncoder().encodeToString(pcm16));
        return json.writeValueAsString(message);
      }

      @Override
      public List<String> commit() {
        return List.of("{\"type\":\"input_audio.end\"}");
      }

      @Override
      public Event receive(JsonNode message) {
        return switch (RealtimeUris.text(message, "type")) {
          case "transcription.text.delta" -> {
            text.append(RealtimeUris.text(message, "text"));
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
