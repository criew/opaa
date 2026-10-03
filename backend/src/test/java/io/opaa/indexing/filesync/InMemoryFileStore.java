package io.opaa.indexing.filesync;

import io.opaa.indexing.source.SourceFolderPath;
import io.opaa.knowledge.SourceDocumentContext;
import io.opaa.sourceaccess.SourceRequestMeter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * A {@link FileStore} over files held in memory - the reference implementation of the port. Files
 * are named by a slash-separated path within their container; {@code file_path} is {@code
 * mem://<container>/<name>}, the change feature a hash of the bytes. A store is reusable across
 * runs; its state is shared with the test that fills it.
 */
public final class InMemoryFileStore implements FileStore {

  private record StoredFile(byte[] bytes, String mediaType) {}

  private final Map<String, TreeMap<String, StoredFile>> containers = new LinkedHashMap<>();
  private final Set<String> unlistable = new HashSet<>();
  private final Set<String> unreadable = new HashSet<>();
  private final Set<String> deselected = new HashSet<>();
  private final Map<String, Map<String, String>> recalled = new LinkedHashMap<>();
  private boolean folderMarkers;
  private final List<String> calls = new ArrayList<>();
  private SourceRequestMeter meter = new SourceRequestMeter();
  private int pageSize = 1000;
  private ChangeFeed feed;
  private int cursors;
  private boolean credentialsRejected;
  private boolean endAfterFirstPage;

  public InMemoryFileStore container(String key) {
    containers.computeIfAbsent(key, k -> new TreeMap<>());
    return this;
  }

  public InMemoryFileStore put(String container, String name, String text) {
    return put(container, name, text.getBytes(java.nio.charset.StandardCharsets.UTF_8), null);
  }

  public InMemoryFileStore put(String container, String name, byte[] bytes, String mediaType) {
    container(container).containers.get(container).put(name, new StoredFile(bytes, mediaType));
    return this;
  }

  public InMemoryFileStore remove(String container, String name) {
    containers.get(container).remove(name);
    return this;
  }

  public InMemoryFileStore denyListing(String container) {
    unlistable.add(container);
    return this;
  }

  public InMemoryFileStore allowListing(String container) {
    unlistable.remove(container);
    return this;
  }

  /** From now on no file of {@code container} can be read; listing still works. */
  public InMemoryFileStore denyReading(String container) {
    unreadable.add(container);
    return this;
  }

  /** From now on every request fails as if the credentials were revoked. */
  public InMemoryFileStore rejectCredentials() {
    credentialsRejected = true;
    return this;
  }

  /** {@code name} lies outside the library's patterns, in listing and single check alike. */
  public InMemoryFileStore deselect(String name) {
    deselected.add(name);
    return this;
  }

  /** A broken store for the contract's own test: it reports the first page as the last one. */
  public InMemoryFileStore endAfterFirstPage() {
    endAfterFirstPage = true;
    return this;
  }

  public InMemoryFileStore pageSize(int pageSize) {
    this.pageSize = pageSize;
    return this;
  }

  /**
   * From now on a folder's marker is a hash over the names and bytes below it, the way a store with
   * propagating folder ETags reports it, and a folder whose marker equals the recalled one is
   * reported unchanged instead of listed.
   */
  public InMemoryFileStore withFolderMarkers() {
    folderMarkers = true;
    return this;
  }

  /** The markers {@link #recall} last handed over, per container. */
  public Map<String, Map<String, String>> recalled() {
    return recalled;
  }

  /** Gives the store a change log with one stream per container and numbered start cursors. */
  public InMemoryFileStore withChangeFeed() {
    feed =
        new ChangeFeed() {
          @Override
          public String feedKey(FileContainer container) {
            return "stream:" + container.key();
          }

          @Override
          public String startCursor(String feedKey) throws FileAccessException {
            call("startCursor " + feedKey);
            return "cursor-" + (++cursors);
          }
        };
    return this;
  }

  public static String filePath(String container, String name) {
    return "mem://" + container + "/" + name;
  }

  /** Every call in order, e.g. {@code list A}, {@code fetch A/a.txt}. */
  public List<String> calls() {
    return calls;
  }

  /** Forgets the calls and the meter of earlier runs. */
  public InMemoryFileStore reset() {
    calls.clear();
    meter = new SourceRequestMeter();
    return this;
  }

  @Override
  public List<FileContainer> containers() {
    return containers.keySet().stream().map(FileContainer::new).toList();
  }

  @Override
  public FilePage list(FileContainer container, String continuation) throws FileAccessException {
    call("list " + container.key() + (continuation == null ? "" : " @" + continuation));
    if (unlistable.contains(container.key())) {
      throw new FileAccessException.ContainerUnlistable(
          "Der Bereich „" + container.key() + "“ darf nicht aufgelistet werden.");
    }
    TreeMap<String, StoredFile> files = containers.get(container.key());
    Map<String, String> markers = folderMarkers ? folderMarkers(files) : Map.of();
    Map<String, String> previous = recalled.getOrDefault(container.key(), Map.of());
    List<String> skipped = new ArrayList<>();
    Map<String, String> listed = new TreeMap<>();
    markers.forEach(
        (folder, marker) -> {
          if (skipped.stream().anyMatch(outer -> FileSync.covers(outer, folder))) {
            return;
          }
          if (marker.equals(previous.get(folder))) {
            skipped.add(folder);
          } else {
            listed.put(folder, marker);
          }
        });
    List<String> names =
        files.keySet().stream()
            .filter(name -> skipped.stream().noneMatch(folder -> FileSync.covers(folder, of(name))))
            .toList();
    int start = continuation == null ? 0 : Integer.parseInt(continuation);
    int end = Math.min(start + pageSize, names.size());
    List<FileEntry> entries = new ArrayList<>();
    for (String name : names.subList(start, end)) {
      entries.add(entry(container, name));
    }
    String next = end < names.size() && !endAfterFirstPage ? Integer.toString(end) : null;
    return start == 0 ? new FilePage(entries, next, skipped, listed) : new FilePage(entries, next);
  }

  @Override
  public void recall(FileContainer container, Map<String, String> subtreeMarkers) {
    recalled.put(container.key(), Map.copyOf(subtreeMarkers));
  }

  /** The hierarchy path of the folder {@code name} lies in, {@code ""} at the container's root. */
  private static String of(String name) {
    int slash = name.lastIndexOf('/');
    return slash < 0
        ? ""
        : String.join(
            SourceDocumentContext.HIERARCHY_SEPARATOR, name.substring(0, slash).split("/"));
  }

  /** Every folder, root first and parents before children, with a hash over all files below it. */
  private static Map<String, String> folderMarkers(TreeMap<String, StoredFile> files) {
    Map<String, Integer> hashes = new TreeMap<>();
    hashes.put("", 1);
    files.forEach(
        (name, file) -> {
          int contribution = name.hashCode() * 31 + Arrays.hashCode(file.bytes());
          String folder = of(name);
          List<String> chain = new ArrayList<>(List.of(""));
          if (!folder.isEmpty()) {
            String[] segments = folder.split(SourceDocumentContext.HIERARCHY_SEPARATOR);
            for (int i = 1; i <= segments.length; i++) {
              chain.add(
                  String.join(
                      SourceDocumentContext.HIERARCHY_SEPARATOR,
                      Arrays.asList(segments).subList(0, i)));
            }
          }
          chain.forEach(path -> hashes.merge(path, contribution, (a, b) -> a * 31 + b));
        });
    Map<String, String> markers = new LinkedHashMap<>();
    hashes.forEach((folder, hash) -> markers.put(folder, "m:" + hash));
    return markers;
  }

  @Override
  public FileEntry head(FileContainer container, String id) throws FileAccessException {
    call("head " + container.key() + "/" + id);
    if (!containers.get(container.key()).containsKey(id)) {
      throw new FileAccessException.Gone(
          "„" + container.key() + "/" + id + "“ existiert nicht mehr.");
    }
    return entry(container, id);
  }

  @Override
  public FetchedFile fetch(FileEntry entry, long maxBytes) throws FileAccessException {
    call("fetch " + entry.container().key() + "/" + entry.id());
    if (unreadable.contains(entry.container().key())) {
      throw new FileAccessException.Unreadable("„" + entry.id() + "“ darf nicht gelesen werden.");
    }
    StoredFile file = containers.get(entry.container().key()).get(entry.id());
    if (file == null) {
      throw new FileAccessException.Gone("„" + entry.id() + "“ existiert nicht mehr.");
    }
    if (file.bytes().length > maxBytes) {
      throw new FileAccessException.TooLarge("„" + entry.id() + "“ ist zu groß.");
    }
    try {
      Path temp = Files.createTempFile("opaa-mem-", ".bin");
      Files.write(temp, file.bytes());
      meter.recordBytes(file.bytes().length);
      return new FetchedFile(temp, file.bytes().length, marker(file));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Override
  public Optional<ChangeFeed> changes() {
    return Optional.ofNullable(feed);
  }

  @Override
  public SourceRequestMeter meter() {
    return meter;
  }

  @Override
  public void close() {}

  private void call(String call) throws FileAccessException.RunEnding {
    calls.add(call);
    meter.recordRequest();
    if (credentialsRejected) {
      throw new FileAccessException.RunEnding("Die Zugangsdaten wurden abgelehnt.");
    }
  }

  private FileEntry entry(FileContainer container, String name) {
    StoredFile file = containers.get(container.key()).get(name);
    int slash = name.lastIndexOf('/');
    List<String> folders =
        slash < 0 ? List.of() : Arrays.asList(name.substring(0, slash).split("/"));
    return new FileEntry(
        container,
        name,
        filePath(container.key(), name),
        slash < 0 ? name : name.substring(slash + 1),
        SourceFolderPath.capped(folders),
        new SourceDocumentContext(
            container.key(),
            folders.isEmpty()
                ? null
                : String.join(SourceDocumentContext.HIERARCHY_SEPARATOR, folders)),
        file.bytes().length,
        marker(file),
        file.mediaType(),
        deselected.contains(name) ? new Exclusion.Deselected(" außerhalb der Muster") : null);
  }

  private static String marker(StoredFile file) {
    return "h:" + Arrays.hashCode(file.bytes()) + "|" + file.bytes().length;
  }
}
