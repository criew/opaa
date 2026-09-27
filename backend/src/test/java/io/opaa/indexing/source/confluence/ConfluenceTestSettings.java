package io.opaa.indexing.source.confluence;

import io.opaa.api.types.ConfluenceEdition;
import io.opaa.knowledge.KnowledgeLibrary;
import java.util.List;

/**
 * Writes Confluence connector settings straight onto a library, the way the connector stores them.
 */
public final class ConfluenceTestSettings {

  private ConfluenceTestSettings() {}

  public static void configure(
      KnowledgeLibrary library, ConfluenceEdition edition, List<ConfluenceSpaceSelection> spaces) {
    store(
        library,
        new ConfluenceSourceSettings(
            edition, spaces, ConfluenceSourceSettings.of(library).fullSyncIntervalDays()));
  }

  public static void fullSyncIntervalDays(KnowledgeLibrary library, Integer days) {
    ConfluenceSourceSettings stored = ConfluenceSourceSettings.of(library);
    store(library, new ConfluenceSourceSettings(stored.edition(), stored.spaceSelection(), days));
  }

  private static void store(KnowledgeLibrary library, ConfluenceSourceSettings settings) {
    library.updateSourceSettings(settings.sortedByKey().toData().toJson());
  }
}
