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
import java.util.HashMap;
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
  private final Map<String, List<String>> unchangedSubtrees = new LinkedHashMap<>();
  private final List<String> calls = new ArrayList<>();
  private SourceRequestMeter meter = new SourceRequestMeter();
  private int pageSize = 1000;
  private ChangeFeed feed;
  private int cursors;
  private final List<String[]> changeLog = new ArrayList<>();
  private final Map<String, Integer> cursorPositions = new HashMap<>();
  private boolean cursorsExpired;
  private boolean structureChanged;
  private final Set<String> textExports = new HashSet<>();
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
   * The container's listing leaves out every file below {@code folder} and reports it unchanged.
   */
  public InMemoryFileStore unchangedSubtree(String container, String folder) {
    unchangedSubtrees.computeIfAbsent(container, k -> new ArrayList<>()).add(folder);
    return this;
  }

  /**
   * Gives the store a change log with one stream per container and numbered cursors. A change is
   * noted with {@link #changed}; a read reports the file's state at that moment - updated while it
   * exists, removed once it does not.
   */
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
            return cursorAt(changeLog.size());
          }

          @Override
          public ChangePage read(String feedKey, String cursor) throws FileAccessException {
            call("read " + feedKey + " @" + cursor);
            Integer position = cursorPositions.get(cursor);
            if (cursorsExpired || position == null) {
              throw new FileAccessException.CursorExpired("Der Änderungszeiger ist verfallen.");
            }
            List<Change> changes = new ArrayList<>();
            int index = position;
            for (; index < changeLog.size() && changes.size() < pageSize; index++) {
              String[] logged = changeLog.get(index);
              if (feedKey.equals("stream:" + logged[0])) {
                changes.add(change(logged[0], logged[1]));
              }
            }
            boolean more = false;
            for (int rest = index; rest < changeLog.size(); rest++) {
              more |= feedKey.equals("stream:" + changeLog.get(rest)[0]);
            }
            boolean structure = structureChanged;
            structureChanged = false;
            return more
                ? new ChangePage(changes, cursorAt(index), null, structure)
                : new ChangePage(changes, null, cursorAt(changeLog.size()), structure);
          }

          @Override
          public void requireReachable(FileContainer container) throws FileAccessException {
            call("reachable " + container.key());
            if (unlistable.contains(container.key())) {
              throw new FileAccessException.ContainerUnlistable(
                  "Der Bereich „" + container.key() + "“ ist nicht erreichbar.");
            }
          }
        };
    return this;
  }

  /** Notes a change of {@code name} in {@code container}'s stream. */
  public InMemoryFileStore changed(String container, String name) {
    changeLog.add(new String[] {container, name});
    return this;
  }

  /** {@code name} is fetched as plain text under a {@code .txt} name, with a protocol note. */
  public InMemoryFileStore exportAsText(String name) {
    textExports.add(name);
    return this;
  }

  /** The next read reports a change of structure. */
  public InMemoryFileStore structureChanged() {
    structureChanged = true;
    return this;
  }

  /** From now on no cursor is accepted. */
  public InMemoryFileStore expireCursors() {
    cursorsExpired = true;
    return this;
  }

  private String cursorAt(int position) {
    String cursor = "cursor-" + (++cursors);
    cursorPositions.put(cursor, position);
    return cursor;
  }

  private Change change(String container, String name) {
    return containers.get(container).containsKey(name)
        ? new Change.Updated(entry(new FileContainer(container), name))
        : new Change.Removed(filePath(container, name));
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
    List<String> skipped = unchangedSubtrees.getOrDefault(container.key(), List.of());
    List<String> names =
        containers.get(container.key()).keySet().stream()
            .filter(name -> skipped.stream().noneMatch(folder -> name.startsWith(folder + "/")))
            .toList();
    int start = continuation == null ? 0 : Integer.parseInt(continuation);
    int end = Math.min(start + pageSize, names.size());
    List<FileEntry> entries = new ArrayList<>();
    for (String name : names.subList(start, end)) {
      entries.add(entry(container, name));
    }
    String next = end < names.size() && !endAfterFirstPage ? Integer.toString(end) : null;
    List<String> subtrees =
        start == 0
            ? skipped.stream().map(folder -> filePath(container.key(), folder) + "/").toList()
            : List.of();
    return new FilePage(entries, next, subtrees);
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
      if (textExports.contains(entry.id())) {
        return new FetchedFile(
            temp,
            file.bytes().length,
            marker(file),
            entry.fileName().replaceAll("\\.[^.]+$", "") + ".txt",
            "Als Text exportiert.");
      }
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
