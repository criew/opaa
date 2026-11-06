package io.opaa.api.types;

/**
 * How the text recognition model role reaches its service. Exactly two: the docling-serve API (v1)
 * and an OpenAI-compatible vision model; there is no OCR standard to take its place.
 */
public enum TextRecognitionConnectionType {

  /** The docling-serve API, key as {@code X-Api-Key}. */
  DOCLING_SERVE,

  /** An OpenAI-compatible vision model reading each page as an image, key as bearer token. */
  OPENAI_VISION
}
