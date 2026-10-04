package io.opaa.msgraph;

import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * One page of a Graph collection: its {@code value} entries, the token of the next page ({@code
 * null} on the last one) and, on the last page of a {@code delta} enumeration, the delta token to
 * read changes from later. Both tokens are bare values, never addresses.
 */
public record GraphPage(List<JsonNode> value, String nextToken, String deltaToken) {

  public GraphPage {
    value = List.copyOf(value);
  }
}
