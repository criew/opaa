package io.opaa.format.file.pdf;

import io.opaa.format.chunk.ChunkAnchor;
import io.opaa.format.chunk.ChunkAnchors;
import io.opaa.format.chunk.ChunkMetadataKeys;
import java.util.List;
import org.springframework.ai.document.Document;

/**
 * Finds the pages of chunks cut along the outline, where a section's text is a concatenation of
 * pages and the cut itself no longer knows them. Each chunk's own passage is looked up in the
 * whitespace-normalized page texts, in document order: its start gives the first page, its end the
 * last. A chunk whose passage is not found verbatim keeps an anchor without pages.
 */
final class PdfPageLocator {

  private static final int PROBE_LENGTH = ChunkAnchors.QUOTE_LENGTH;

  private final String text;
  private final int[] pageStarts;

  private PdfPageLocator(String text, int[] pageStarts) {
    this.text = text;
    this.pageStarts = pageStarts;
  }

  static PdfPageLocator forPages(List<String> pageTexts) {
    StringBuilder text = new StringBuilder();
    int[] starts = new int[pageTexts.size()];
    for (int i = 0; i < pageTexts.size(); i++) {
      if (!text.isEmpty()) {
        text.append(' ');
      }
      starts[i] = text.length();
      text.append(ChunkAnchors.normalized(pageTexts.get(i)));
    }
    return new PdfPageLocator(text.toString(), starts);
  }

  /** Adds the page range to the anchor of every chunk whose passage is found, in place. */
  void addPages(List<Document> chunks) {
    int cursor = 0;
    for (Document chunk : chunks) {
      String passage =
          ChunkAnchors.normalized(
              ChunkAnchors.passage(
                  chunk.getText(),
                  chunk.getMetadata().get(ChunkMetadataKeys.ANCHOR_LEAD_METADATA_KEY)));
      if (passage.isEmpty()) {
        continue;
      }
      String head = passage.substring(0, Math.min(PROBE_LENGTH, passage.length()));
      int start = find(head, cursor);
      if (start < 0) {
        continue;
      }
      String tail = passage.substring(Math.max(0, passage.length() - PROBE_LENGTH));
      int tailStart = text.indexOf(tail, start);
      int end = tailStart < 0 ? start : tailStart + tail.length() - 1;
      cursor = start + 1;
      ChunkAnchor anchor = ChunkAnchors.read(chunk.getMetadata());
      ChunkAnchors.write(chunk.getMetadata(), anchor.withPages(pageAt(start), pageAt(end)));
    }
  }

  private int find(String probe, int from) {
    int found = text.indexOf(probe, from);
    return found >= 0 ? found : text.indexOf(probe);
  }

  /** The 1-based page holding {@code offset} of the normalized text. */
  private int pageAt(int offset) {
    int page = 0;
    while (page + 1 < pageStarts.length && pageStarts[page + 1] <= offset) {
      page++;
    }
    return page + 1;
  }
}
