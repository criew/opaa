package io.opaa.llm;

import java.util.Locale;
import java.util.Optional;

/**
 * The recording formats the transcription endpoint is handed. OpenAI-compatible servers detect the
 * format from the file name's extension, so each format names the extension its part carries.
 */
public enum TranscriptionAudioFormat {
  WEBM("audio/webm", "webm"),
  OGG("audio/ogg", "ogg"),
  MP4("audio/mp4", "mp4"),
  MPEG("audio/mpeg", "mp3"),
  WAV("audio/wav", "wav");

  private final String mediaType;
  private final String extension;

  TranscriptionAudioFormat(String mediaType, String extension) {
    this.mediaType = mediaType;
    this.extension = extension;
  }

  public String mediaType() {
    return mediaType;
  }

  public String extension() {
    return extension;
  }

  /**
   * The format of a request's {@code Content-Type}, parameters such as {@code codecs=opus} ignored;
   * empty for anything this role does not pass on.
   */
  public static Optional<TranscriptionAudioFormat> fromContentType(String contentType) {
    if (contentType == null) {
      return Optional.empty();
    }
    int parameters = contentType.indexOf(';');
    String base =
        (parameters >= 0 ? contentType.substring(0, parameters) : contentType)
            .strip()
            .toLowerCase(Locale.ROOT);
    return switch (base) {
      case "audio/webm" -> Optional.of(WEBM);
      case "audio/ogg" -> Optional.of(OGG);
      case "audio/mp4", "audio/m4a", "audio/x-m4a" -> Optional.of(MP4);
      case "audio/mpeg", "audio/mp3" -> Optional.of(MPEG);
      case "audio/wav", "audio/wave", "audio/x-wav" -> Optional.of(WAV);
      default -> Optional.empty();
    };
  }
}
