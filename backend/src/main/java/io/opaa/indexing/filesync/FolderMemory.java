package io.opaa.indexing.filesync;

import io.opaa.format.SupportedDocumentFormats;
import io.opaa.indexing.source.SourceSyncState;
import io.opaa.knowledge.DocumentRepository;
import io.opaa.knowledge.SourceDocumentContext;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The folder markers of one round (ADR-0040): what a store is recalled, which markers its listing
 * reported or carried over, and which folders stay unsettled. A marker seen first in a round is
 * kept over a later one: everything below it was listed after it was seen.
 */
final class FolderMemory {

  private final FileSyncSettings settings;
  private final SupportedDocumentFormats supportedFormats;
  private final DocumentRepository documentRepository;
  private final UUID libraryId;
  private final FolderRevisits revisits;

  private SourceSyncState.SubtreeMemory remembered = SourceSyncState.SubtreeMemory.NONE;
  private Instant establishedAt;

  /** Per container key, the folder markers the store was recalled this run. */
  private final Map<String, Map<String, String>> recalled = new HashMap<>();

  private final Map<String, Map<String, String>> listed = new LinkedHashMap<>();
  private final Map<String, Map<String, String>> carried = new LinkedHashMap<>();
  private final Map<String, Set<String>> unsettled = new LinkedHashMap<>();

  FolderMemory(
      FileSyncSettings settings,
      SupportedDocumentFormats supportedFormats,
      DocumentRepository documentRepository,
      UUID libraryId,
      FolderRevisits revisits) {
    this.settings = settings;
    this.supportedFormats = supportedFormats;
    this.documentRepository = documentRepository;
    this.libraryId = libraryId;
    this.revisits = revisits;
  }

  /**
   * Recalls {@code memory} when it was judged under this run's size bound and formats and is
   * younger than {@link FileSyncSettings#subtreeMemoryMaxAge()}; otherwise the run lists every
   * folder. A resumed round carries on with what its earlier runs saw ({@code progress}).
   */
  void recall(
      SourceSyncState.SubtreeMemory memory, SourceSyncState.ScanProgress progress, Instant now) {
    Duration maxAge = settings.subtreeMemoryMaxAge();
    boolean valid =
        basis().equals(memory.basis())
            && memory.establishedAt() != null
            && (maxAge == null || memory.establishedAt().plus(maxAge).isAfter(now));
    remembered = valid ? memory : SourceSyncState.SubtreeMemory.NONE;
    if (progress == null) {
      establishedAt = valid ? memory.establishedAt() : now;
      return;
    }
    establishedAt = progress.memoryEstablishedAt() == null ? now : progress.memoryEstablishedAt();
    progress.markers().forEach((key, markers) -> listed.put(key, new HashMap<>(markers)));
    progress.carried().forEach((key, markers) -> carried.put(key, new HashMap<>(markers)));
    progress.unsettled().forEach((key, paths) -> unsettled.put(key, new HashSet<>(paths)));
  }

  Instant establishedAt() {
    return establishedAt;
  }

  /**
   * The markers {@code container} is recalled: none for a folder in or above a document deleted
   * outside a run or awaiting a visit - marked for reprocessing or not indexed - so such a folder
   * is listed again.
   */
  Map<String, String> recallFor(FileContainer container) {
    Map<String, String> markers = remembered.containers().get(container.key());
    Map<String, String> effective = new HashMap<>();
    if (markers != null) {
      List<String> awaiting = new ArrayList<>(revisits.atStart(container.key()));
      for (String path :
          documentRepository.findHierarchyPathsAwaitingAVisit(libraryId, container.key())) {
        awaiting.add(path == null ? "" : path);
      }
      effective.putAll(markers);
      effective.keySet().removeIf(folder -> awaiting.stream().anyMatch(p -> covers(folder, p)));
    }
    recalled.put(container.key(), Map.copyOf(effective));
    return effective;
  }

  /** The markers {@code containerKey} was recalled this run. */
  Map<String, String> recalled(String containerKey) {
    return recalled.getOrDefault(containerKey, Map.of());
  }

  /** The folders a page listed; a marker the round already holds is kept. */
  void listed(String containerKey, Map<String, String> markers) {
    Map<String, String> held = listed.computeIfAbsent(containerKey, key -> new HashMap<>());
    markers.forEach(held::putIfAbsent);
  }

  /** The recalled markers in and below the unchanged {@code folder} carry over. */
  void carry(String containerKey, String folder) {
    Map<String, String> held = carried.computeIfAbsent(containerKey, key -> new HashMap<>());
    recalled(containerKey)
        .forEach(
            (path, marker) -> {
              if (covers(folder, path)) {
                held.put(path, marker);
              }
            });
  }

  /** The entry's stored state does not reflect the listing: its folders are listed again. */
  void unsettle(FileEntry entry) {
    String path = entry.context().hierarchyPath();
    unsettled
        .computeIfAbsent(entry.container().key(), key -> new HashSet<>())
        .add(path == null ? "" : path);
  }

  Map<String, Map<String, String>> listedMarkers() {
    return copy(listed);
  }

  Map<String, Map<String, String>> carriedMarkers() {
    return copy(carried);
  }

  Map<String, Set<String>> unsettledFolders() {
    Map<String, Set<String>> copy = new LinkedHashMap<>();
    unsettled.forEach((key, paths) -> copy.put(key, Set.copyOf(paths)));
    return copy;
  }

  /**
   * The markers the next round is recalled: those of every listed folder and those carried over
   * from unchanged ones, except a folder in or above an unsettled entry or a document deleted
   * outside a run that its container's listing did not see at its start ({@code deletedOutside}).
   */
  SourceSyncState.SubtreeMemory remembered(Map<String, Set<String>> deletedOutside) {
    Map<String, Map<String, String>> containers = new LinkedHashMap<>();
    Set<String> keys = new LinkedHashSet<>(carried.keySet());
    keys.addAll(listed.keySet());
    for (String key : keys) {
      Map<String, String> markers = new HashMap<>(carried.getOrDefault(key, Map.of()));
      markers.putAll(listed.getOrDefault(key, Map.of()));
      Set<String> open = new HashSet<>(unsettled.getOrDefault(key, Set.of()));
      open.addAll(deletedOutside.getOrDefault(key, Set.of()));
      markers.keySet().removeIf(folder -> open.stream().anyMatch(path -> covers(folder, path)));
      if (!markers.isEmpty()) {
        containers.put(key, markers);
      }
    }
    return new SourceSyncState.SubtreeMemory(basis(), establishedAt, containers);
  }

  /** What a remembered marker presumes besides the folder: the size bound and the formats. */
  private String basis() {
    return "v2|"
        + settings.maxFileSizeBytes()
        + "|"
        + String.join(",", supportedFormats.extensions().stream().sorted().toList());
  }

  /** Whether {@code path} is the folder {@code folder} or lies below it. */
  static boolean covers(String folder, String path) {
    return folder.isEmpty()
        || path.equals(folder)
        || path.startsWith(folder + SourceDocumentContext.HIERARCHY_SEPARATOR);
  }

  private static Map<String, Map<String, String>> copy(Map<String, Map<String, String>> source) {
    Map<String, Map<String, String>> copy = new LinkedHashMap<>();
    source.forEach((key, markers) -> copy.put(key, Map.copyOf(markers)));
    return copy;
  }
}
