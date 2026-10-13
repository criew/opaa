package io.opaa.llm.web;

import io.opaa.api.dto.ErrorResponse;
import io.opaa.api.dto.TranscriptionAvailabilityResponse;
import io.opaa.api.dto.TranscriptionResponse;
import io.opaa.common.ConditionalOnFeature;
import io.opaa.common.Feature;
import io.opaa.common.PayloadTooLargeException;
import io.opaa.common.ValidationException;
import io.opaa.llm.TranscriptionAudioFormat;
import io.opaa.llm.TranscriptionClient.TranscriptionUnavailableException;
import io.opaa.llm.TranscriptionModelRole;
import io.opaa.llm.TranscriptionModelRole.TranscriptionRoleBusyException;
import io.opaa.llm.TranscriptionModelRole.TranscriptionRoleUnusableException;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.Arrays;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Dictation for the chat input, for every signed-in person. Exists only while the feature
 * speech-input is switched on.
 *
 * <p><b>The recording stays in memory and is never logged.</b> It arrives as the raw request body,
 * not as multipart, so the servlet container never buffers it to a temporary file ({@link
 * TranscriptionMultipartGuard} turns multipart away before it is parsed). It is read through {@link
 * #readBounded} into one array, which the client sends without copying and which is overwritten
 * once the call ends. No answer, no exception and no log line carries audio or recognised text, and
 * no person is named to the endpoint. At most {@code opaa.transcription.max-concurrent} recordings
 * are held at once, across the whole installation.
 */
@RestController
@RequestMapping("/api/v1/transcriptions")
@ConditionalOnFeature(Feature.SPEECH_INPUT)
public class TranscriptionController {

  static final String UNAVAILABLE_MESSAGE =
      "Die Spracheingabe ist derzeit nicht verfügbar. Bitte tippen Sie Ihre Frage.";
  static final String BUSY_MESSAGE =
      "Die Spracheingabe ist gerade ausgelastet. Bitte versuchen Sie es gleich erneut oder tippen"
          + " Sie Ihre Frage.";
  static final String UPSTREAM_FAILED_MESSAGE =
      "Die Texterkennung ist fehlgeschlagen. Bitte versuchen Sie es erneut oder tippen Sie Ihre"
          + " Frage.";

  private final TranscriptionModelRole role;

  public TranscriptionController(TranscriptionModelRole role) {
    this.role = role;
  }

  @GetMapping("/availability")
  public TranscriptionAvailabilityResponse getTranscriptionAvailability() {
    return new TranscriptionAvailabilityResponse(role.usable(), false);
  }

  @PostMapping(consumes = "audio/*")
  public TranscriptionResponse createTranscription(HttpServletRequest request) throws IOException {
    if (!role.usable()) {
      throw new TranscriptionRoleUnusableException(UNAVAILABLE_MESSAGE);
    }
    TranscriptionAudioFormat format =
        TranscriptionAudioFormat.fromContentType(request.getContentType())
            .orElseThrow(
                () ->
                    new ValidationException(
                        "Das Aufnahmeformat wird nicht unterstützt. Erlaubt sind WebM, Ogg, MP4,"
                            + " MP3 und WAV."));
    try (TranscriptionModelRole.Slot slot = role.reserve()) {
      byte[] audio = readBounded(request, role.maxAudioBytes());
      try {
        if (audio.length == 0) {
          throw new ValidationException("Die Aufnahme ist leer.");
        }
        return new TranscriptionResponse(role.transcribe(audio, format));
      } finally {
        Arrays.fill(audio, (byte) 0);
      }
    }
  }

  /** Expected failures of a dependency: answered without a stack trace, logged by the role. */
  @ExceptionHandler(TranscriptionUnavailableException.class)
  ResponseEntity<ErrorResponse> upstreamFailed() {
    return error(HttpStatus.BAD_GATEWAY, UPSTREAM_FAILED_MESSAGE);
  }

  @ExceptionHandler(TranscriptionRoleUnusableException.class)
  ResponseEntity<ErrorResponse> roleUnusable() {
    return error(HttpStatus.SERVICE_UNAVAILABLE, UNAVAILABLE_MESSAGE);
  }

  @ExceptionHandler(TranscriptionRoleBusyException.class)
  ResponseEntity<ErrorResponse> busy() {
    return error(HttpStatus.SERVICE_UNAVAILABLE, BUSY_MESSAGE);
  }

  private static ResponseEntity<ErrorResponse> error(HttpStatus status, String message) {
    return ResponseEntity.status(status)
        .body(new ErrorResponse(message, status.value(), Instant.now()));
  }

  /** Rejects by the declared length first, then by what actually arrives (chunked senders). */
  static byte[] readBounded(HttpServletRequest request, int maxBytes) throws IOException {
    if (request.getContentLengthLong() > maxBytes) {
      throw tooLarge(maxBytes);
    }
    try (InputStream in = request.getInputStream()) {
      byte[] body = in.readNBytes(maxBytes + 1);
      if (body.length > maxBytes) {
        Arrays.fill(body, (byte) 0);
        throw tooLarge(maxBytes);
      }
      return body;
    }
  }

  private static PayloadTooLargeException tooLarge(int maxBytes) {
    long megabytes = Math.max(1, maxBytes / (1024 * 1024));
    return new PayloadTooLargeException(
        "Die Aufnahme ist zu groß. Erlaubt sind höchstens " + megabytes + " MB.");
  }
}
