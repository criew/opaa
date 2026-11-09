package io.opaa.llm;

import io.opaa.api.types.RealtimeTranscriptionProtocol;
import java.net.URI;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The OpenAI Realtime API in transcription mode ({@code wss://api.openai.com/v1/realtime
 * ?intent=transcription}, without a model in the address): {@code session.update} with {@code
 * session.type = "transcription"} and PCM16 at 24 kHz, {@code input_audio_buffer.append} with
 * Base64 audio, {@code input_audio_buffer.commit} at the end. The text arrives per committed item
 * as {@code conversation.item.input_audio_transcription.delta} and {@code .completed}; items are
 * kept in the order the upstream committed them, since completions may arrive out of order.
 */
@Component
class OpenAiRealtimeTranscriptionAdapter implements RealtimeTranscriptionAdapter {

  static final int SAMPLE_RATE = 24_000;

  private final ObjectMapper json;

  OpenAiRealtimeTranscriptionAdapter(ObjectMapper json) {
    this.json = json;
  }

  @Override
  public RealtimeTranscriptionProtocol protocol() {
    return RealtimeTranscriptionProtocol.OPENAI;
  }

  @Override
  public int sampleRate() {
    return SAMPLE_RATE;
  }

  @Override
  public URI uri(RealtimeTranscriptionEndpoint endpoint) {
    return RealtimeUris.withQueryParameter(endpoint.url(), "intent", "transcription");
  }

  @Override
  public Dialog open(RealtimeTranscriptionEndpoint endpoint, String language, String vocabulary) {
    return new OpenAiDialog(endpoint.model(), language, vocabulary);
  }

  private final class OpenAiDialog implements Dialog {

    private final String model;
    private final String language;
    private final String vocabulary;

    /** Committed items in commit order, each with its text so far. */
    private final Map<String, StringBuilder> items = new LinkedHashMap<>();

    private final List<String> completed = new ArrayList<>();
    private boolean committed;

    OpenAiDialog(String model, String language, String vocabulary) {
      this.model = model;
      this.language = language;
      this.vocabulary = vocabulary;
    }

    @Override
    public List<String> opening() {
      ObjectNode message = json.createObjectNode();
      message.put("type", "session.update");
      ObjectNode session = message.putObject("session");
      session.put("type", "transcription");
      ObjectNode input = session.putObject("audio").putObject("input");
      ObjectNode format = input.putObject("format");
      format.put("type", "audio/pcm");
      format.put("rate", SAMPLE_RATE);
      ObjectNode transcription = input.putObject("transcription");
      transcription.put("model", model);
      if (!language.isEmpty()) {
        transcription.putArray("languages").add(language);
      }
      if (!vocabulary.isEmpty()) {
        transcription.put("prompt", vocabulary);
      }
      input.putNull("turn_detection");
      return List.of(json.writeValueAsString(message));
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
      committed = true;
      return List.of("{\"type\":\"input_audio_buffer.commit\"}");
    }

    @Override
    public Event receive(JsonNode message) {
      String type = RealtimeUris.text(message, "type");
      String itemId = RealtimeUris.text(message, "item_id");
      switch (type) {
        case "input_audio_buffer.committed" -> {
          items.putIfAbsent(itemId, new StringBuilder());
          return new Event.Other();
        }
        case "conversation.item.input_audio_transcription.delta" -> {
          items
              .computeIfAbsent(itemId, id -> new StringBuilder())
              .append(RealtimeUris.text(message, "delta"));
          return new Event.Text(joined());
        }
        case "conversation.item.input_audio_transcription.completed" -> {
          StringBuilder text = items.computeIfAbsent(itemId, id -> new StringBuilder());
          text.setLength(0);
          text.append(RealtimeUris.text(message, "transcript"));
          completed.add(itemId);
          return committed && completed.containsAll(items.keySet())
              ? new Event.Done(joined())
              : new Event.Text(joined());
        }
        case "conversation.item.input_audio_transcription.failed" -> {
          return new Event.Failed("the upstream could not transcribe the recording");
        }
        case "error" -> {
          return new Event.Failed(RealtimeUris.errorReason(message));
        }
        default -> {
          return new Event.Other();
        }
      }
    }

    private String joined() {
      StringBuilder all = new StringBuilder();
      for (StringBuilder text : items.values()) {
        String part = text.toString().strip();
        if (!part.isEmpty()) {
          if (!all.isEmpty()) {
            all.append(' ');
          }
          all.append(part);
        }
      }
      return all.toString();
    }
  }
}
