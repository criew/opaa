package io.opaa.llm;

/**
 * The instruction a vision model of the text recognition role gets with every page image. It asks
 * for the page's text only, so the answer can be indexed as the page; it carries no content.
 */
final class TextRecognitionVisionPrompt {

  static final String TEXT =
      "Transcribe all text on this scanned page exactly as written, in reading order, as Markdown."
          + " Keep headings, lists and tables (as Markdown tables). Do not translate, summarise,"
          + " correct or comment. Answer with the transcription only; answer with nothing if the"
          + " page holds no text.";

  private TextRecognitionVisionPrompt() {}
}
