package io.opaa.indexing.source.confluence.webhook;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads the page ids a notification names. The payload is a hint about <em>which</em> pages to look
 * at, never about <em>what</em> happened to them - the targeted fetch asks the instance (ADR-0023,
 * Entscheidung 4) - so the event type is not interpreted and every shape that carries a page id is
 * accepted: Data Center's {@code page.id} (page events) and {@code attachment.pageId}/{@code
 * attachment.container.id} (attachment events), the generic {@code content.id}, and the flat {@code
 * pageId} or {@code pageIds} an Automation rule's custom body sends. A body that names no page is
 * not an error: the sender is answered, nothing is queued.
 */
public final class ConfluenceWebhookPayload {

  private static final List<String[]> ID_PATHS =
      List.of(
          new String[] {"page", "id"},
          new String[] {"content", "id"},
          new String[] {"attachment", "pageId"},
          new String[] {"attachment", "container", "id"},
          new String[] {"pageId"});

  private ConfluenceWebhookPayload() {}

  /** The distinct page ids named in {@code body}, in order of appearance; empty for no JSON. */
  public static Set<String> pageIds(byte[] body, JsonMapper mapper) {
    if (body == null || body.length == 0) {
      return Set.of();
    }
    JsonNode root;
    try {
      root = mapper.readTree(body);
    } catch (JacksonException e) {
      return Set.of();
    }
    if (root == null || !root.isObject()) {
      return Set.of();
    }
    Set<String> ids = new LinkedHashSet<>();
    for (String[] path : ID_PATHS) {
      JsonNode node = root;
      for (String segment : path) {
        node = node.path(segment);
      }
      addId(ids, node);
    }
    JsonNode list = root.path("pageIds");
    if (list.isArray()) {
      list.forEach(element -> addId(ids, element));
    }
    return Collections.unmodifiableSet(ids);
  }

  private static void addId(Set<String> ids, JsonNode node) {
    if (node.isNumber() || (node.isTextual() && !node.asString().isBlank())) {
      ids.add(node.asString().trim());
    }
  }
}
