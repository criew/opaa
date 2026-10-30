package io.opaa.search;

import io.opaa.auth.CurrentUser;
import io.opaa.common.ValidationException;
import io.opaa.format.chunk.ChunkAnchor;
import io.opaa.format.chunk.ChunkAnchors;
import io.opaa.format.chunk.ChunkMetadataKeys;
import io.opaa.knowledge.Document;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * The Textfassung of a document: its indexed text in reading order, one bounded window of passages
 * at a time, for the formats a browser cannot show. Right and answers are those of the original's
 * download ({@link ChunkInspectionService#requireReadableText}), so the Textfassung never opens
 * more than the file itself.
 *
 * <p>Each passage is the stored chunk text without the part it shares with its predecessor -
 * chunking overlaps deliberately - so consecutive passages read as continuous text and a cited
 * passage can be marked exactly by its index. The predecessor of the window's first passage is read
 * along for that and not returned.
 */
@Service
public class DocumentTextService {

  /** Upper bound of passages in one window. */
  static final int MAX_WINDOW = 40;

  /** Upper bound of outline entries; a longer outline is cut and marked truncated. */
  static final int MAX_SECTIONS = 2000;

  /**
   * Shortest shared boundary that counts as overlap. A shorter match is a coincidence - a closing
   * bracket meeting an opening one - and removing it would eat characters of the original.
   */
  static final int MIN_OVERLAP = 8;

  private final ChunkInspectionService chunks;

  public DocumentTextService(ChunkInspectionService chunks) {
    this.chunks = chunks;
  }

  /**
   * The passages with {@code chunk_index} in {@code [from, to]}, at most {@link #MAX_WINDOW} of
   * them. {@code from} defaults to the first indexed passage, {@code to} to the widest window from
   * there; {@code withSections} adds the outline of the whole document.
   *
   * @throws ValidationException for a negative bound or {@code to} below {@code from}
   * @throws io.opaa.common.NotFoundException as the original's download would
   */
  public DocumentText read(
      CurrentUser caller, UUID documentId, Integer from, Integer to, boolean withSections) {
    if ((from != null && from < 0) || (to != null && to < 0)) {
      throw new ValidationException("Der Abschnitt der Textfassung darf nicht negativ sein.");
    }
    if (from != null && to != null && to < from) {
      throw new ValidationException("Das Ende des Abschnitts liegt vor seinem Anfang.");
    }
    Document document = chunks.requireReadableText(caller, documentId);
    UUID organizationId = caller.organizationId();
    Optional<ChunkInspectionService.IndexBounds> bounds =
        chunks.chunkIndexBounds(organizationId, documentId);
    if (bounds.isEmpty()) {
      return new DocumentText(
          document.getId(), document.getFileName(), null, null, List.of(), List.of(), false);
    }
    int start = from == null ? bounds.get().first() : from;
    long widest = (long) start + MAX_WINDOW - 1;
    int end = (int) Math.min(to == null ? widest : Math.min(to, widest), Integer.MAX_VALUE);
    List<DocumentText.Passage> passages =
        passages(chunks.listChunkWindow(organizationId, documentId, start - 1, end), start);

    DocumentOutline.Outline outline =
        withSections
            ? DocumentOutline.of(chunks.listChunkAnchors(organizationId, documentId), MAX_SECTIONS)
            : new DocumentOutline.Outline(List.of(), false);
    return new DocumentText(
        document.getId(),
        document.getFileName(),
        bounds.get().first(),
        bounds.get().last(),
        passages,
        outline.sections(),
        outline.truncated());
  }

  private static List<DocumentText.Passage> passages(List<ChunkInspection> window, int start) {
    List<DocumentText.Passage> passages = new ArrayList<>();
    ChunkInspection previous = null;
    for (ChunkInspection chunk : window) {
      String content = chunk.content() == null ? "" : chunk.content();
      if (chunk.chunkIndex() >= start) {
        String text =
            previous != null && previous.chunkIndex() == chunk.chunkIndex() - 1
                ? withoutOverlap(previous.content(), content)
                : content;
        ChunkAnchor anchor = ChunkAnchors.read(chunk.metadata());
        Object location = chunk.metadata().get(ChunkMetadataKeys.LOCATION_METADATA_KEY);
        passages.add(
            new DocumentText.Passage(
                chunk.chunkIndex(),
                text,
                location == null ? null : location.toString(),
                anchor.isEmpty() ? null : anchor));
      }
      previous = chunk;
    }
    return List.copyOf(passages);
  }

  /** {@code next} without the boundary it shares with {@code previous}, and without leading gap. */
  static String withoutOverlap(String previous, String next) {
    if (previous == null || previous.isEmpty()) {
      return next;
    }
    int overlap = PassageText.overlapLength(previous, next);
    // a passage that is nothing but its predecessor's tail keeps its text, or it would show empty
    if (overlap < MIN_OVERLAP || overlap >= next.strip().length()) {
      return next;
    }
    return next.substring(overlap).stripLeading();
  }
}
