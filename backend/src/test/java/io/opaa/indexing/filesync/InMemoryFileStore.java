package io.opaa.indexing.filesync;

import io.opaa.indexing.job.RequestBudgetExhaustedException;
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
 * are named by a slash-separated path within their container and listed in name order; {@code
 * file_path} is {@code mem://<container>/<name>}, the change feature a hash of the bytes. A store
 * is reusable across runs; its state is shared with the test that fills it. With checkpoints, a
 * page's checkpoint is the last name it delivered.
 */
public final class InMemoryFileStore implements FileStore {

  private record StoredFile(byte[] bytes, String mediaType) {}

  private final Map<String, TreeMap<String, StoredFile>> containers = new LinkedHashMap<>();
  private final Set<String> unlistable = new HashSet<>();
  private final Set<String> unreadable = new HashSet<>();
  private final Set<String> deselected = new HashSet<>();
  private java.util.function.Predicate<String> deselectedWhere = name -> false;
  private final Map<String, Map<String, String>> recalled = new LinkedHashMap<>();
  private boolean folderMarkers;
  private boolean shallowMarkers;
  private boolean stableIds;
  private boolean globalIds;
  private final Map<String, Long> ids = new HashMap<>();
  private long nextId;
  private final List<String> calls = new ArrayList<>();
  private SourceRequestMeter meter = new SourceRequestMeter();
  private int pageSize = 1000;
  private ChangeFeed feed;
  private int cursors;
  private final List<String[]> changeLog = new ArrayList<>();
  private final Map<String, Integer> cursorPositions = new HashMap<>();
  private boolean cursorsExpired;
  private boolean structureChanged;
  private boolean earlyNewStart;
  private boolean swallowExpiry;
  private boolean recordChanges;
  private Runnable beforeNextRead;
  private boolean reportAsLogged;
  private final Set<String> textExports = new HashSet<>();
  private boolean credentialsRejected;
  private int rejectAfter = -1;
  private boolean endAfterFirstPage;
  private boolean checkpoints;
  private int checkpointGeneration;
  private int budget;
  private AbsenceProof absenceProof = AbsenceProof.SINGLE_RUN;

  public InMemoryFileStore container(String key) {
    containers.computeIfAbsent(key, k -> new TreeMap<>());
    return this;
  }

  public InMemoryFileStore put(String container, String name, String text) {
    return put(container, name, text.getBytes(java.nio.charset.StandardCharsets.UTF_8), null);
  }

  public InMemoryFileStore put(String container, String name, byte[] bytes, String mediaType) {
    container(container).containers.get(container).put(name, new StoredFile(bytes, mediaType));
    ids.computeIfAbsent(container + "\n" + name, key -> ++nextId);
    recordChange(container, name);
    return this;
  }

  public InMemoryFileStore remove(String container, String name) {
    containers.get(container).remove(name);
    departed(container, name);
    recordChange(container, name);
    return this;
  }

  /**
   * Per container and folder, how often something left it: a folder's marker never returns to an
   * earlier value, as a Nextcloud ETag does not, even when the files below are the same again.
   */
  private final Map<String, Map<String, Integer>> departures = new HashMap<>();

  private void departed(String container, String name) {
    Map<String, Integer> counts = departures.computeIfAbsent(container, key -> new HashMap<>());
    String folder = of(name);
    counts.merge("", 1, Integer::sum);
    if (!folder.isEmpty()) {
      String[] segments = folder.split(SourceDocumentContext.HIERARCHY_SEPARATOR);
      for (int i = 1; i <= segments.length; i++) {
        counts.merge(
            String.join(
                SourceDocumentContext.HIERARCHY_SEPARATOR, Arrays.asList(segments).subList(0, i)),
            1,
            Integer::sum);
      }
    }
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

  public InMemoryFileStore allowReading(String container) {
    unreadable.remove(container);
    return this;
  }

  /** From now on every request fails as if the credentials were revoked. */
  public InMemoryFileStore rejectCredentials() {
    credentialsRejected = true;
    return this;
  }

  public InMemoryFileStore acceptCredentials() {
    credentialsRejected = false;
    rejectAfter = -1;
    return this;
  }

  /**
   * From now on every request of a run after its first {@code requests} fails as if the credentials
   * were revoked mid-run; {@link #acceptCredentials()} ends it.
   */
  public InMemoryFileStore rejectCredentialsAfter(int requests) {
    rejectAfter = requests;
    return this;
  }

  /** Every page carries a checkpoint, the last name it delivered, which a later run resumes. */
  public InMemoryFileStore withCheckpoints() {
    checkpoints = true;
    return this;
  }

  /** From now on no checkpoint given so far is accepted. */
  public InMemoryFileStore expireCheckpoints() {
    checkpointGeneration++;
    return this;
  }

  /** From now on a run refuses its requests beyond {@code budget}; {@code 0} for no bound. */
  public InMemoryFileStore budget(int budget) {
    this.budget = budget;
    return this;
  }

  public InMemoryFileStore absenceProof(AbsenceProof absenceProof) {
    this.absenceProof = absenceProof;
    return this;
  }

  /** {@code name} lies outside the library's patterns, in listing and single check alike. */
  public InMemoryFileStore deselect(String name) {
    deselected.add(name);
    return this;
  }

  /**
   * From now on every name {@code outside} accepts lies outside the library's patterns, as {@link
   * #deselect} does for one name; {@code name -> false} selects everything again.
   */
  public InMemoryFileStore deselectWhere(java.util.function.Predicate<String> outside) {
    deselectedWhere = outside;
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

  /** Whether the store reports folders and skips unchanged ones. */
  public boolean reportsFolders() {
    return folderMarkers;
  }

  /**
   * A broken store for the contract's own test: a folder's marker covers only its own files, so a
   * change two levels down never reaches the root.
   */
  public InMemoryFileStore withShallowFolderMarkers() {
    folderMarkers = true;
    shallowMarkers = true;
    return this;
  }

  /**
   * From now on a file keeps its identity ({@code file_path}) and its change feature when it is
   * renamed or moved, like a store that tracks files by id.
   */
  public InMemoryFileStore withStableIds() {
    stableIds = true;
    return this;
  }

  /**
   * Renames or moves the file or folder {@code from} to {@code to}; ids follow under stable ids.
   */
  public InMemoryFileStore move(String container, String from, String to) {
    TreeMap<String, StoredFile> files = containers.get(container);
    Map<String, StoredFile> moved = new LinkedHashMap<>();
    // a renamed folder keeps what it counted, as it keeps its own marker
    Map<String, Integer> counts = departures.computeIfAbsent(container, key -> new HashMap<>());
    String fromFolder = from.replace("/", SourceDocumentContext.HIERARCHY_SEPARATOR);
    String toFolder = to.replace("/", SourceDocumentContext.HIERARCHY_SEPARATOR);
    for (String folder : List.copyOf(counts.keySet())) {
      if (FileSync.covers(fromFolder, folder) && !fromFolder.isEmpty()) {
        counts.put(toFolder + folder.substring(fromFolder.length()), counts.remove(folder));
      }
    }
    for (String name : List.copyOf(files.keySet())) {
      if (name.equals(from) || name.startsWith(from + "/")) {
        String target = to + name.substring(from.length());
        departed(container, name);
        moved.put(target, files.remove(name));
        Long id = ids.remove(container + "\n" + name);
        if (id != null) {
          ids.put(container + "\n" + target, id);
        }
        recordChange(container, target);
        if (!stableIds) {
          // the old name is another identity, reported removed
          recordChange(container, name);
        }
      }
    }
    files.putAll(moved);
    return this;
  }

  /** The {@code file_path} the store gives {@code name}, also after a removal. */
  public String filePathOf(String container, String name) {
    if (globalIds) {
      return "mem://#" + ids.get(container + "\n" + name);
    }
    if (!stableIds) {
      return filePath(container, name);
    }
    return "mem://" + container + "/#" + ids.get(container + "\n" + name);
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
            return cursorAt(changeLog.size());
          }

          @Override
          public ChangePage read(String feedKey, String cursor) throws FileAccessException {
            call("read " + feedKey + " @" + cursor);
            if (beforeNextRead != null) {
              Runnable once = beforeNextRead;
              beforeNextRead = null;
              once.run();
            }
            Integer position = cursorPositions.get(cursor);
            if (cursorsExpired || position == null) {
              if (swallowExpiry) {
                return new ChangePage(List.of(), null, cursorAt(changeLog.size()), false);
              }
              throw new FileAccessException.CursorExpired("Der Änderungszeiger ist verfallen.");
            }
            List<Change> changes = new ArrayList<>();
            int index = position;
            for (; index < changeLog.size() && changes.size() < pageSize; index++) {
              String[] logged = changeLog.get(index);
              if (feedKey.equals("stream:" + logged[0])) {
                changes.add(
                    reportAsLogged && !Boolean.parseBoolean(logged[3])
                        ? new Change.Removed(filePathOf(logged[1], logged[2]))
                        : change(logged[1], logged[2]));
              }
            }
            boolean more = false;
            for (int rest = index; rest < changeLog.size(); rest++) {
              more |= feedKey.equals("stream:" + changeLog.get(rest)[0]);
            }
            boolean structure = structureChanged;
            structureChanged = false;
            return more && !earlyNewStart
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

  /**
   * Runs {@code action} once, while the next page of the change log is read - something that
   * happens while a change run is under way.
   */
  public InMemoryFileStore beforeNextChangeRead(Runnable action) {
    beforeNextRead = action;
    return this;
  }

  /**
   * From now on a file's identity is its id alone, independent of its container, like a store that
   * tracks files across areas; {@link #moveAcross} keeps it.
   */
  public InMemoryFileStore withGlobalIds() {
    globalIds = true;
    return this;
  }

  /**
   * Moves {@code name} unchanged from container {@code from} to {@code to}, keeping its id, and
   * notes the change in both streams: an update in {@code to}'s, a removal in {@code from}'s.
   */
  public InMemoryFileStore moveAcross(String from, String name, String to) {
    StoredFile file = containers.get(from).remove(name);
    departed(from, name);
    container(to).containers.get(to).put(name, file);
    ids.put(to + "\n" + name, ids.get(from + "\n" + name));
    changed(to, name);
    changed(from, name);
    return this;
  }

  /** Notes a change of {@code name} in {@code container}'s own stream. */
  public InMemoryFileStore changed(String container, String name) {
    return changedIn(container, container, name);
  }

  /**
   * Notes a change of {@code name} in {@code container} on the stream of {@code stream} - a stream
   * that also reports files of a container it does not serve, as a provider's account-wide log
   * does.
   */
  public InMemoryFileStore changedIn(String stream, String container, String name) {
    boolean present = containers.getOrDefault(container, new TreeMap<>()).containsKey(name);
    changeLog.add(new String[] {stream, container, name, Boolean.toString(present)});
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

  /**
   * From now on every put, removal and move notes itself in its container's stream, as the change
   * log of a real source does; a moved file under stable ids is reported once, at its new name.
   */
  public InMemoryFileStore recordingChanges() {
    recordChanges = true;
    return this;
  }

  private void recordChange(String container, String name) {
    if (recordChanges) {
      changed(container, name);
    }
  }

  /**
   * From now on a noted removal is reported as one even if the file exists again by the time the
   * stream is read, as a change log that reports each entry in its logged form does: the same file
   * may then appear removed and present in one read, the last report being its state.
   */
  public InMemoryFileStore reportingAsLogged() {
    reportAsLogged = true;
    return this;
  }

  /** A broken feed for the contract's own test: the first page already names the new start. */
  public InMemoryFileStore withEarlyNewStart() {
    earlyNewStart = true;
    return this;
  }

  /** A broken feed for the contract's own test: an expired cursor silently starts over. */
  public InMemoryFileStore withSwallowedExpiry() {
    swallowExpiry = true;
    return this;
  }

  /** {@code name} is fetched as plain text under a {@code .txt} name, with a protocol note. */
  public InMemoryFileStore exportAsText(String name) {
    textExports.add(name);
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
        : new Change.Removed(filePathOf(container, name));
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
    return continuation == null
        ? page(container, 0, true, null)
        : page(container, Integer.parseInt(continuation), false, null);
  }

  @Override
  public FilePage resume(FileContainer container, String checkpoint) throws FileAccessException {
    call("resume " + container.key() + " @" + checkpoint);
    if (!checkpoints) {
      throw new FileAccessException.CheckpointExpired("Ohne Fortsetzungspunkte.");
    }
    int colon = checkpoint.indexOf(':');
    if (Integer.parseInt(checkpoint.substring(1, colon)) != checkpointGeneration) {
      throw new FileAccessException.CheckpointExpired("Der Fortsetzungspunkt ist verfallen.");
    }
    String last = checkpoint.substring(colon + 1);
    List<String> names = names(container);
    int start = 0;
    while (start < names.size() && names.get(start).compareTo(last) <= 0) {
      start++;
    }
    return page(container, start, true, last);
  }

  @Override
  public AbsenceProof absenceProof() {
    return absenceProof;
  }

  /** The names a listing of {@code container} delivers, without those in unchanged folders. */
  private List<String> names(FileContainer container) {
    TreeMap<String, StoredFile> files = containers.get(container.key());
    List<String> skipped = unchangedFolders(container, files);
    return files.keySet().stream()
        .filter(name -> skipped.stream().noneMatch(folder -> FileSync.covers(folder, of(name))))
        .toList();
  }

  private List<String> unchangedFolders(
      FileContainer container, TreeMap<String, StoredFile> files) {
    Map<String, String> markers = folderMarkers ? folderMarkers(container.key(), files) : Map.of();
    Map<String, String> previous = recalled.getOrDefault(container.key(), Map.of());
    List<String> skipped = new ArrayList<>();
    markers.forEach(
        (folder, marker) -> {
          if (skipped.stream().noneMatch(outer -> FileSync.covers(outer, folder))
              && marker.equals(previous.get(folder))) {
            skipped.add(folder);
          }
        });
    return skipped;
  }

  /**
   * The page from {@code start} on; the first one of a listing or a resumption also names the
   * unchanged and the listed folders - after {@code resumedAfter} only folders none of whose files
   * came up to it.
   */
  private FilePage page(
      FileContainer container, int start, boolean reportFolders, String resumedAfter)
      throws FileAccessException {
    if (unlistable.contains(container.key())) {
      throw new FileAccessException.ContainerUnlistable(
          "Der Bereich „" + container.key() + "“ darf nicht aufgelistet werden.");
    }
    TreeMap<String, StoredFile> files = containers.get(container.key());
    Map<String, String> markers = folderMarkers ? folderMarkers(container.key(), files) : Map.of();
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
    if (resumedAfter != null) {
      listed
          .keySet()
          .removeIf(
              folder ->
                  files.keySet().stream()
                      .anyMatch(
                          name ->
                              FileSync.covers(folder, of(name))
                                  && name.compareTo(resumedAfter) <= 0));
    }
    int end = Math.min(start + pageSize, names.size());
    List<FileEntry> entries = new ArrayList<>();
    for (String name : names.subList(start, end)) {
      entries.add(entry(container, name));
    }
    String next = end < names.size() && !endAfterFirstPage ? Integer.toString(end) : null;
    String checkpoint =
        checkpoints
            ? "g" + checkpointGeneration + ":" + (end == 0 ? "" : names.get(end - 1))
            : null;
    return reportFolders
        ? new FilePage(entries, next, checkpoint, skipped, listed)
        : new FilePage(entries, next, checkpoint);
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
  private Map<String, String> folderMarkers(String container, TreeMap<String, StoredFile> files) {
    Map<String, Integer> hashes = new TreeMap<>();
    hashes.put("", 1);
    files.forEach(
        (name, file) -> {
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
          if (shallowMarkers) {
            chain = List.of(folder);
          }
          for (String path : chain) {
            // relative to the folder: a renamed folder keeps its own marker, as Nextcloud does
            String relative =
                path.isEmpty() ? name : name.substring(path.replace(" / ", "/").length());
            int contribution = relative.hashCode() * 31 + Arrays.hashCode(file.bytes());
            hashes.merge(path, contribution, (a, b) -> a * 31 + b);
          }
        });
    Map<String, String> markers = new LinkedHashMap<>();
    Map<String, Integer> counts = departures.getOrDefault(container, Map.of());
    hashes.forEach(
        (folder, hash) -> markers.put(folder, "m:" + hash + "|" + counts.getOrDefault(folder, 0)));
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

  private synchronized void call(String call) throws FileAccessException.RunEnding {
    if (budget > 0 && meter.requests() >= budget) {
      throw RequestBudgetExhaustedException.requests(budget);
    }
    calls.add(call);
    meter.recordRequest();
    if (credentialsRejected || (rejectAfter >= 0 && meter.requests() > rejectAfter)) {
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
        filePathOf(container.key(), name),
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
        deselected.contains(name) || deselectedWhere.test(name)
            ? new Exclusion.Deselected(" außerhalb der Muster")
            : null);
  }

  private static String marker(StoredFile file) {
    return "h:" + Arrays.hashCode(file.bytes()) + "|" + file.bytes().length;
  }
}
