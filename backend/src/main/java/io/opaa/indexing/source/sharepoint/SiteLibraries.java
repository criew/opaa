package io.opaa.indexing.source.sharepoint;

import io.opaa.compliance.DataFlow;
import io.opaa.msgraph.GraphClient;
import io.opaa.msgraph.GraphException;
import io.opaa.msgraph.GraphPage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import tools.jackson.databind.JsonNode;

/**
 * The document libraries of the sites a library takes whole (ADR-0047, Entscheidung 2), as Graph
 * lists them now: every drive of type {@code documentLibrary}, each listing read to its last page.
 * A listing that cannot be completed throws, so a run never works from a partial resolution.
 */
@DataFlow("source-sharepoint")
final class SiteLibraries {

  private SiteLibraries() {}

  static List<SharePointLibrary> resolve(GraphClient graph, List<SharePointSite> sites)
      throws GraphException, InterruptedException {
    List<SharePointLibrary> found = new ArrayList<>();
    Set<String> seen = new TreeSet<>();
    for (SharePointSite site : sites) {
      String token = null;
      do {
        GraphPage page =
            graph.page(
                "sites/" + site.id() + "/drives", Map.of("$select", "id,name,driveType"), token);
        for (JsonNode drive : page.value()) {
          String id = text(drive, "id");
          if (id != null
              && "documentLibrary".equals(text(drive, "driveType"))
              && SharePointLibrary.ID.matcher(id).matches()
              && seen.add(id)) {
            found.add(new SharePointLibrary(id, List.of(), Map.of(), text(drive, "name"), site));
          }
        }
        token = page.nextToken();
      } while (token != null);
    }
    return found;
  }

  /** The container keys of {@code libraries} with the names a run protocol shows. */
  static Map<String, String> named(List<SharePointLibrary> libraries) {
    Map<String, String> names = new LinkedHashMap<>();
    for (SharePointLibrary library : libraries) {
      String site = library.site() == null ? null : library.site().name();
      String name = library.name();
      names.put(library.key(), site != null && name != null ? site + " / " + name : name);
    }
    return names;
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node.get(field);
    return value == null || value.isNull() ? null : value.asString();
  }
}
