package io.opaa.indexing.source.confluence;

import io.opaa.indexing.IndexingRunEventRecorder;
import io.opaa.indexing.IndexingRunProgress;
import io.opaa.indexing.source.IndexingRun;
import io.opaa.library.ConfluenceSpaceSelection;
import io.opaa.library.KnowledgeLibrary;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

/** Everything one Confluence run shares across its spaces, pages and attachments. */
final class ConfluenceRun {
  final IndexingRun frame;
  final ConfluenceClient client;
  final KnowledgeLibrary library;
  final IndexingRunProgress progress;
  final IndexingRunEventRecorder events;

  /** The keys of the library's selected spaces - the selection a visited page is held against. */
  final Set<String> selectedKeys = new HashSet<>();

  /** False once any selected space or attachment list could not be listed completely. */
  boolean listingComplete = true;

  /**
   * The space keys behind {@code listingComplete == false}, in the order the run met them: a space
   * that could not be listed at all, or the space of a page whose attachments could not be.
   * Persisted by a successful full sync so the library view can name them.
   */
  final Set<String> unreadableSpaceKeys = new LinkedHashSet<>();

  /**
   * True when this full sync continues an interrupted one: a page already stored at the listed
   * version then costs no call at all - its attachments were listed by the run that stored it, and
   * a chain of resumed runs must converge, not re-spend its budget on the done part.
   */
  boolean resumed;

  int total;

  ConfluenceRun(IndexingRun frame, ConfluenceClient client) {
    this.frame = frame;
    this.client = client;
    this.library = frame.library();
    this.progress = frame.progress();
    this.events = frame.events();
    for (ConfluenceSpaceSelection space : library.getConfluenceSpaces()) {
      selectedKeys.add(space.getSpaceKey());
    }
  }
}
