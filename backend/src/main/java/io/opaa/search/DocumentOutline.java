package io.opaa.search;

import io.opaa.format.chunk.ChunkAnchor;
import java.util.ArrayList;
import java.util.List;

/**
 * The outline of a document read from its chunks' anchors: one entry where a slide, worksheet, mail
 * message, heading or page begins, in that order of preference per chunk. A heading path enters
 * every heading that differs from the predecessor's path, so a section that opens straight with a
 * subsection still lists its parent. A chunk without structure continues the section before it.
 */
public final class DocumentOutline {

  private DocumentOutline() {}

  /** One outline entry: the section starts at passage {@code chunkIndex}; level 1 is outermost. */
  public record Section(int chunkIndex, String label, int level) {}

  /** The entries and whether {@code limit} cut any off. */
  record Outline(List<Section> sections, boolean truncated) {}

  static Outline of(List<ChunkInspectionService.IndexedAnchor> anchors, int limit) {
    List<Section> sections = new ArrayList<>();
    List<String> previous = List.of();
    for (ChunkInspectionService.IndexedAnchor indexed : anchors) {
      List<String> labels = labels(indexed.anchor());
      if (labels.isEmpty()) {
        continue;
      }
      int common = 0;
      while (common < labels.size()
          && common < previous.size()
          && labels.get(common).equals(previous.get(common))) {
        common++;
      }
      for (int level = common; level < labels.size(); level++) {
        if (sections.size() == limit) {
          return new Outline(List.copyOf(sections), true);
        }
        sections.add(new Section(indexed.chunkIndex(), labels.get(level), level + 1));
      }
      previous = labels;
    }
    return new Outline(List.copyOf(sections), false);
  }

  private static List<String> labels(ChunkAnchor anchor) {
    if (anchor.slide() != null) {
      return List.of("Folie " + anchor.slide());
    }
    if (anchor.sheet() != null && !anchor.sheet().isBlank()) {
      return List.of(anchor.sheet());
    }
    if (anchor.message() != null) {
      return List.of("Nachricht " + anchor.message());
    }
    if (anchor.path() != null && !anchor.path().isEmpty()) {
      return anchor.path();
    }
    if (anchor.pageFrom() != null) {
      return List.of("Seite " + anchor.pageFrom());
    }
    return List.of();
  }
}
