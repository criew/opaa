package io.opaa.api.types;

/**
 * The dialect a live transcription upstream speaks over its WebSocket. The browser always speaks
 * OPAA's own protocol; one adapter per value translates it (ADR-0070).
 */
public enum RealtimeTranscriptionProtocol {

  /** OpenAI Realtime API in transcription mode, PCM16 at 24 kHz. */
  OPENAI,

  /** vLLM's {@code /v1/realtime} endpoint, e.g. with Voxtral Realtime, PCM16 at 16 kHz. */
  VLLM,

  /** Mistral's realtime transcription API, PCM16 at 16 kHz. */
  MISTRAL
}
