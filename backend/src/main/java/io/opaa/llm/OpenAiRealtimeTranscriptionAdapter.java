package io.opaa.llm;

import io.opaa.api.types.RealtimeTranscriptionProtocol;
import java.net.URI;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The OpenAI Realtime API in transcription mode ({@code wss://api.openai.com/v1/realtime
 * ?intent=transcription}, without a model in the address): {@code session.update} with {@code
 * session.type = "transcription"} and PCM16 at 24 kHz, {@code input_audio_buffer.append} with
 * Base64 audio, {@code input_audio_buffer.commit} at the end. The text arrives per item as {@code
 * conversation.item.input_audio_transcription.delta} and {@code .completed}; items are kept in the
 * order they first appeared, since completions may arrive out of order. The language goes as {@code
 * languages} for {@code gpt-live-transcribe} and as {@code language} for every other model.
 */
@Component
class OpenAiRealtimeTranscriptionAdapter implements RealtimeTranscriptionAdapter {

  static final int SAMPLE_RATE = 24_000;

  /** The most items one recording may hold; a dictation of two minutes has a handful. */
  static final int MAX_ITEMS = 1024;

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

  /** Whether the model takes {@code languages} (a list) instead of {@code language}. */
  static boolean takesLanguageList(String model) {
    return model != null && model.startsWith("gpt-live-transcribe");
  }

  private final class OpenAiDialog implements Dialog {

    private final String model;
    private final String language;
    private final String vocabulary;

    /** Items in the order they first appeared, each with its text so far. */
    private final Map<String, StringBuilder> items = new LinkedHashMap<>();

    private final Set<String> committedItems = new HashSet<>();
    private final Set<String> completed = new HashSet<>();
    private boolean committed;
    private boolean finalCommitSeen;
    private int chars;

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
        if (takesLanguageList(model)) {
          transcription.putArray("languages").add(language);
        } else {
          transcription.put("language", language);
        }
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

    /**
     * Done once the buffer committed after OPAA's commit and every committed item completed. An
     * item known only from deltas never holds the end back, and the final text joins completed
     * items only, so deltas under another item id cannot leave the session waiting or doubled.
     */
    @Override
    public Event receive(JsonNode message) {
      String type = RealtimeUris.text(message, "type");
      String itemId = RealtimeUris.text(message, "item_id");
      switch (type) {
        case "input_audio_buffer.committed" -> {
          if (tooLarge(0)) {
            return RealtimeTranscriptionAdapter.tooMuchText();
          }
          items.putIfAbsent(itemId, new StringBuilder());
          committedItems.add(itemId);
          if (committed) {
            finalCommitSeen = true;
          }
          return finished() ? new Event.Done(joined(true)) : new Event.Other();
        }
        case "conversation.item.input_audio_transcription.delta" -> {
          String delta = RealtimeUris.text(message, "delta");
          if (tooLarge(delta.length())) {
            return RealtimeTranscriptionAdapter.tooMuchText();
          }
          items.computeIfAbsent(itemId, id -> new StringBuilder()).append(delta);
          return new Event.Text(joined(false));
        }
        case "conversation.item.input_audio_transcription.completed" -> {
          String transcript = RealtimeUris.text(message, "transcript");
          if (tooLarge(transcript.length())) {
            return RealtimeTranscriptionAdapter.tooMuchText();
          }
          StringBuilder text = items.computeIfAbsent(itemId, id -> new StringBuilder());
          text.setLength(0);
          text.append(transcript);
          completed.add(itemId);
          return finished() ? new Event.Done(joined(true)) : new Event.Text(joined(false));
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

    private boolean finished() {
      return committed && finalCommitSeen && completed.containsAll(committedItems);
    }

    private boolean tooLarge(int added) {
      chars += added;
      return chars > MAX_TEXT_CHARS || items.size() >= MAX_ITEMS;
    }

    private String joined(boolean completedOnly) {
      StringBuilder all = new StringBuilder();
      for (Map.Entry<String, StringBuilder> item : items.entrySet()) {
        if (completedOnly && !completed.contains(item.getKey())) {
          continue;
        }
        String part = item.getValue().toString().strip();
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
