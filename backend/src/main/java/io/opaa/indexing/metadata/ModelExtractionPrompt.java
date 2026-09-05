package io.opaa.indexing.metadata;

import java.util.List;

/**
 * Builds the one prompt of the model-backed extraction (metadata-schema.md, Schritt 2): the offered
 * fields with their codes and labels, the instruction to answer with a code or {@code null}, and
 * the document's Titelzeile plus a capped beginning of its text. The answer format it asks for is
 * what {@link ModelExtractionAnswer#parse} reads.
 */
public final class ModelExtractionPrompt {

  /**
   * Characters of document text handed to the model. The unscharfe fields it decides - Dokumentart
   * and the Auswahlfelder of a library - are visible in the Dokumentkopf; the rest of the text
   * moves the decision no further, but it does move the cost, the latency and the amount of content
   * leaving the house per document.
   */
  public static final int TEXT_LIMIT = 4000;

  private ModelExtractionPrompt() {}

  /**
   * The prompt for {@code fields} and, when {@code keywords} is set, up to {@link
   * DocumentKeyword#MAX_KEYWORDS_PER_DOCUMENT} freie Schlagworte in the same call - one round trip
   * per document, never one per field.
   */
  public static String build(
      String title, String text, List<ModelExtractionField> fields, boolean keywords) {
    StringBuilder prompt = new StringBuilder();
    prompt
        .append(
            "Du erschließt Metadaten eines Verwaltungsdokuments. Antworte ausschließlich mit einem"
                + " JSON-Objekt, ohne Erklärung und ohne Codeblock.\n\n")
        .append("Format:\n")
        .append("{\"fields\": {\"<feldschlüssel>\": {\"value\": \"<CODE>\", \"confidence\":")
        .append(" <0..1>}}");
    if (keywords) {
      prompt.append(", \"keywords\": [\"<schlagwort>\"]");
    }
    prompt.append("}\n\n");
    if (!fields.isEmpty()) {
      prompt.append("Felder und die einzigen zulässigen Codes:\n");
      for (ModelExtractionField field : fields) {
        prompt
            .append("- ")
            .append(field.field().key())
            .append(" (")
            .append(field.field().label())
            .append("):\n");
        for (ModelExtractionField.Option option : field.options()) {
          prompt
              .append("    ")
              .append(option.code())
              .append(" = ")
              .append(option.label())
              .append('\n');
        }
      }
      prompt
          .append(
              "\nAntworte je Feld nur mit einem der aufgeführten Codes. Passt kein Code, setze"
                  + " \"value\": null. Erfinde keinen Code, wähle keinen ähnlichen. Die"
                  + " \"confidence\" ist deine eigene Sicherheit zwischen 0 und 1.\n")
          .append("\n");
    }
    if (keywords) {
      prompt.append(
          "Vergib zusätzlich höchstens "
              + DocumentKeyword.MAX_KEYWORDS_PER_DOCUMENT
              + " freie Schlagworte in Alltagssprache, je höchstens "
              + DocumentKeyword.MAX_KEYWORD_LENGTH
              + " Zeichen. Keine Personennamen. Gibt der Text keine her, antworte mit einer leeren"
              + " Liste.\n\n");
    }
    prompt.append("Titel: ").append(title == null ? "" : title).append("\n\n");
    prompt.append("Textanfang:\n").append(capText(text));
    return prompt.toString();
  }

  /** {@code text} shortened to {@link #TEXT_LIMIT} characters; never {@code null}. */
  public static String capText(String text) {
    if (text == null) {
      return "";
    }
    return text.length() <= TEXT_LIMIT ? text : text.substring(0, TEXT_LIMIT);
  }
}
