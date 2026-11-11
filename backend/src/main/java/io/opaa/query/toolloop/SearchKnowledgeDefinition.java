package io.opaa.query.toolloop;

/**
 * What the model sees of {@code search_knowledge}: description and argument schema. Public so the
 * tool-calling measurement offers the model exactly the definition the agent loop offers.
 */
public final class SearchKnowledgeDefinition {

  /** The argument holding the question. */
  public static final String QUESTION = "frage";

  public static final String DESCRIPTION =
      "Durchsucht die Wissensbasis dieses Agenten nach Textstellen zu einer Frage oder zu"
          + " Suchbegriffen. Vor jeder inhaltlichen Aussage aufrufen; für Teilaspekte mehrfach mit"
          + " eigener Frage aufrufen. Optional lässt sich die Suche auf Bibliotheken, Dokumentarten"
          + " oder ein Datumsfenster eingrenzen.";

  public static final String SCHEMA =
      """
      {
        "type": "object",
        "properties": {
          "frage": {
            "type": "string",
            "description": "Wonach gesucht wird: eine Frage oder Suchbegriffe, auf Deutsch."
          },
          "bibliotheken": {
            "type": "array",
            "items": {"type": "string"},
            "description": "Optional: nur in diesen Bibliotheken suchen (Kennungen)."
          },
          "dokumentarten": {
            "type": "array",
            "items": {"type": "string"},
            "description": "Optional: nur Dokumente dieser Dokumentarten (Codes)."
          },
          "datum_von": {
            "type": "string",
            "description": "Optional: frühestes Dokumentdatum, JJJJ-MM-TT."
          },
          "datum_bis": {
            "type": "string",
            "description": "Optional: spätestes Dokumentdatum, JJJJ-MM-TT."
          }
        },
        "required": ["frage"]
      }
      """;

  private SearchKnowledgeDefinition() {}
}
