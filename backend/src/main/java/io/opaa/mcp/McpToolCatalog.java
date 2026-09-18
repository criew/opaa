package io.opaa.mcp;

import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.opaa.search.SearchableLibrary;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * The three tools, and the descriptions a foreign model decides by (#1721,
 * docs/features/external-access.md, "Die Beschreibungen entstehen je Anfrage neu").
 *
 * <p>The descriptions are built <b>per request</b> from the caller's effective view, never from the
 * installation's holdings: "Sucht in Wissensbibliotheken" does not answer the question the model
 * asks, "Sucht in den Beständen <i>Baugenehmigungen 2024</i> und <i>Vergaberecht</i>" does. It is
 * no additional disclosure - {@code list_libraries} returns the same names to the same token - and
 * it is <b>display text only</b>: no permission decision is made here, and a stale description can
 * never produce a hit outside the effective view, because the filter sits in the search.
 *
 * <p>A library description is free text from the administration surface, so it is fitted into a
 * fixed structure rather than pasted: newlines and control characters collapse into spaces, each
 * text is cut to {@link #DESCRIPTION_CHARACTERS}, and at most {@link #LISTED_LIBRARIES} libraries
 * are named before the rest becomes a count. Free text that could restructure the tool description
 * is the one thing this generation must not allow.
 */
@Component
public class McpToolCatalog {

  static final String SEARCH = "search";
  static final String FETCH = "fetch";
  static final String LIST_LIBRARIES = "list_libraries";

  /** Per library, of its own description - enough to recognise a holding, too little to reshape. */
  static final int DESCRIPTION_CHARACTERS = 160;

  /** Beyond this the listing becomes a count: a tool description stays a description. */
  static final int LISTED_LIBRARIES = 12;

  private static final String NOTHING_RELEASED =
      "Dieses Zugangstoken erreicht zurzeit keinen Bestand.";

  private static final Map<String, Object> SEARCH_SCHEMA =
      Map.of(
          "type",
          "object",
          "properties",
          Map.of(
              "query",
              Map.of("type", "string", "description", "Die Suchfrage in natürlicher Sprache."),
              "libraryIds",
              Map.of(
                  "type",
                  "array",
                  "items",
                  Map.of("type", "string"),
                  "description",
                  "Kennungen einzelner Bestände aus list_libraries; ohne Angabe wird in allen"
                      + " erreichbaren Beständen gesucht."),
              "maxHits",
              Map.of(
                  "type",
                  "integer",
                  "description",
                  "Höchstzahl der Treffer; serverseitig gedeckelt.")),
          "required",
          List.of("query"));

  private static final Map<String, Object> FETCH_SCHEMA =
      Map.of(
          "type",
          "object",
          "properties",
          Map.of(
              "id",
              Map.of("type", "string", "description", "Die Trefferkennung aus search."),
              "whole",
              Map.of(
                  "type",
                  "boolean",
                  "description",
                  "Statt des Abschnitts das ganze Dokument, gedeckelt auf eine Höchstlänge.")),
          "required",
          List.of("id"));

  private static final Map<String, Object> NO_ARGUMENTS =
      Map.of("type", "object", "properties", Map.of());

  /** The catalogue for one request, with the descriptions of {@code libraries}. */
  public List<Tool> toolsFor(List<SearchableLibrary> libraries) {
    String holdings = holdingsSentence(libraries);
    return List.of(
        Tool.builder(SEARCH, SEARCH_SCHEMA)
            .title("Fundstellen suchen")
            .description(
                "Sucht Fundstellen in den Wissensbeständen dieser Behörde. "
                    + holdings
                    + " Liefert Textauszüge mit Herkunft und einer Trefferkennung, keine erzeugte"
                    + " Antwort.")
            .build(),
        Tool.builder(FETCH, FETCH_SCHEMA)
            .title("Fundstelle lesen")
            .description(
                "Holt den Text zu einer Trefferkennung aus search - standardmäßig den Abschnitt"
                    + " samt angrenzendem Kontext, auf Wunsch das ganze Dokument. "
                    + holdings)
            .build(),
        Tool.builder(LIST_LIBRARIES, NO_ARGUMENTS)
            .title("Bestände auflisten")
            .description(
                "Listet die Wissensbestände auf, die dieses Zugangstoken erreicht, mit Kennung,"
                    + " Name und Beschreibung. "
                    + holdings)
            .build());
  }

  private String holdingsSentence(List<SearchableLibrary> libraries) {
    if (libraries.isEmpty()) {
      return NOTHING_RELEASED;
    }
    String listed =
        libraries.stream()
            .limit(LISTED_LIBRARIES)
            .map(McpToolCatalog::describe)
            .collect(Collectors.joining("; "));
    int remaining = libraries.size() - Math.min(libraries.size(), LISTED_LIBRARIES);
    String rest = remaining == 0 ? "" : " und " + remaining + " weitere";
    return "Erreichbare Bestände: " + listed + rest + ".";
  }

  private static String describe(SearchableLibrary library) {
    String name = flatten(library.name(), DESCRIPTION_CHARACTERS);
    String description = flatten(library.description(), DESCRIPTION_CHARACTERS);
    return description.isEmpty() ? name : name + " (" + description + ")";
  }

  /**
   * One line, bounded, and without the characters that carry structure in the markup a model reads:
   * free text enters the description as a phrase inside the generated sentence, never as a heading,
   * a list or an emphasis of its own.
   */
  private static String flatten(String text, int limit) {
    if (text == null) {
      return "";
    }
    String collapsed = text.replaceAll("[#`|*_]", " ").replaceAll("[\\p{Cntrl}\\s]+", " ").strip();
    if (collapsed.length() <= limit) {
      return collapsed;
    }
    return collapsed.substring(0, limit).strip() + "…";
  }
}
