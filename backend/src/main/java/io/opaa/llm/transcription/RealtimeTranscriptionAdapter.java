package io.opaa.llm.transcription;

import io.opaa.api.types.RealtimeTranscriptionProtocol;
import java.net.URI;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * One dialect of a live transcription upstream (ADR-0072). The browser always speaks OPAA's own
 * protocol; an adapter only translates between PCM16 frames, the end of the recording and the
 * events of its upstream, so a further dialect is one more implementation and no change to the
 * WebSocket endpoint or {@link RealtimeTranscriptionClient}.
 *
 * <p>An adapter is stateless; everything of one recording lives in its {@link Dialog}. It never
 * logs and never puts audio or recognised text into an exception or a {@link Event.Failed} reason.
 */
public interface RealtimeTranscriptionAdapter {

  /** The most text one recording may gather; a dictation of two minutes has a few thousand. */
  int MAX_TEXT_CHARS = 1024 * 1024;

  /** The failure of a recording whose upstream sent more than {@link #MAX_TEXT_CHARS}. */
  static Event tooMuchText() {
    return new Event.Failed("the upstream sent more text than " + MAX_TEXT_CHARS + " characters");
  }

  RealtimeTranscriptionProtocol protocol();

  /** The sample rate of the PCM16 mono audio the upstream expects, in Hz. */
  int sampleRate();

  /** The address to open: the stored one, completed by what the dialect needs in the query. */
  URI uri(RealtimeTranscriptionEndpoint endpoint);

  /**
   * Starts the dialog of one recording.
   *
   * @param language ISO-639-1 code, empty to let the model detect it
   * @param vocabulary terms the model should recognise, empty for none; a dialect without such a
   *     field ignores it
   */
  Dialog open(RealtimeTranscriptionEndpoint endpoint, String language, String vocabulary);

  /** The messages of one recording, in the order they are sent, and the reading of the answers. */
  interface Dialog {

    /** Sent once right after the connection opened, before any audio. */
    List<String> opening();

    /** One frame of PCM16 mono little-endian audio in {@link #sampleRate()}. */
    String audio(byte[] pcm16);

    /** Sent once the recording ended; the upstream then finishes and answers {@link Event.Done}. */
    List<String> commit();

    /** Reads one message of the upstream. */
    Event receive(JsonNode message);
  }

  /** What one message of the upstream means for the recording. */
  sealed interface Event {

    /** The text recognised so far, as a whole - not only what was added. */
    record Text(String text) implements Event {}

    /** The final text of the recording; nothing follows. */
    record Done(String text) implements Event {}

    /** The upstream refused or failed; a technical English reason without any content. */
    record Failed(String reason) implements Event {}

    /** A message without meaning for the text (session created, buffer committed, usage). */
    record Other() implements Event {}
  }
}
