package io.opaa.indexing.source.smb;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.opaa.indexing.filesync.AbsenceProof;
import io.opaa.indexing.filesync.Exclusion;
import io.opaa.indexing.filesync.FetchedFile;
import io.opaa.indexing.filesync.FileAccessException;
import io.opaa.indexing.filesync.FileContainer;
import io.opaa.indexing.filesync.FileEntry;
import io.opaa.indexing.filesync.FilePage;
import io.opaa.indexing.filesync.FilePathLimit;
import io.opaa.indexing.filesync.FileStore;
import io.opaa.indexing.source.SourceFolderPath;
import io.opaa.knowledge.SourceDocumentContext;
import io.opaa.sourceaccess.SourceRequestMeter;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * The files of a share as the {@link FileStore} of a full sync: a container is a configured folder,
 * walked depth first in name order, a page at most {@code pageSize} entries or folder openings. A
 * file's identity is its path below the share; the change feature is its modification time and
 * size. SMB has no folder feature that changes with everything below it, so no folder is reported
 * unchanged. A page's checkpoint is its place in the walk, the folders met so far and the folders
 * that could not be read; a later run resumes there ({@link AbsenceProof#LOCATION_IDENTITY}).
 *
 * <p>A link is listed but neither fetched nor followed, and a folder met before under another name
 * counts as one. A folder below the container that cannot be read is named and the rest is listed;
 * the container then counts as incompletely listed, so nothing in it is removed.
 */
final class SmbFileStore implements FileStore {

  /**
   * Folder levels below a container that are walked. Deeper ones are not listed and leave the
   * container incomplete - the bound against a link loop the server itself resolves.
   */
  static final int MAX_DEPTH = 64;

  static final String LINK_NOTE =
      "Verknüpfungen (symbolische Links, Junctions, DFS-Verweise) werden nicht verfolgt";

  static final String UNUSABLE_NAME_NOTE =
      "Einträge mit Pfadtrennzeichen oder Steuerzeichen im Namen werden nicht gelesen";

  private static final int NAMED_UNREADABLE_FOLDERS = 5;
  private static final String UNLISTABLE_PAGE = "unlistable";
  private static final String CHECKPOINT_PREFIX = "smb1:";
  private static final JsonMapper CHECKPOINT_JSON = JsonMapper.builder().build();
  private static final Comparator<SmbShareClient.Item> BY_NAME =
      Comparator.comparing(SmbShareClient.Item::name);

  private final SmbShareClient smb;
  private final SmbAddress address;
  private final Set<String> folders = new LinkedHashSet<>();
  private final boolean folderRootChain;
  private final int pageSize;
  private final Map<String, Walk> walks = new HashMap<>();

  /** One folder, with its hierarchy path below the container. */
  private record Folder(String sharePath, String hierarchyPath, List<String> segments) {}

  /**
   * One folder of the walk: its entries in name order, how many of them are done, and - below the
   * container's own folder - the name and file id it was entered by.
   */
  private static final class Frame {
    private final Folder folder;
    private final String name;
    private final long fileId;
    private final List<SmbShareClient.Item> children;
    private int done;

    private Frame(Folder folder, String name, long fileId, List<SmbShareClient.Item> children) {
      this.folder = folder;
      this.name = name;
      this.fileId = fileId;
      this.children = children;
    }
  }

  /** The walk of one container: the open folders, the folders met and the findings so far. */
  private static final class Walk {
    private final Deque<Frame> stack = new ArrayDeque<>();
    private final Set<Long> visitedFolders = new HashSet<>();
    private final List<String> unreadable = new ArrayList<>();
    private int unreadableCount;
    private final List<String> tooDeep = new ArrayList<>();
    private int tooDeepCount;
    private int pages;

    /**
     * Whether the folder with {@code fileId} is met for the first time; {@code 0} and {@code -1}
     * mean the server tells no id.
     */
    private boolean firstVisit(long fileId) {
      return fileId == 0 || fileId == -1 || visitedFolders.add(fileId);
    }

    private boolean incomplete() {
      return unreadableCount > 0 || tooDeepCount > 0;
    }
  }

  /** The persisted form of a place in the walk; names are relative to the container. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record Checkpoint(
      List<Step> path,
      String last,
      boolean done,
      String visited,
      List<String> unreadable,
      int unreadableCount,
      List<String> tooDeep,
      int tooDeepCount) {}

  /** A folder entered on the way to the place, by name and file id. */
  record Step(String name, long id) {}

  SmbFileStore(SmbShareClient smb, SmbAddress address, SmbSourceSettings settings, int pageSize) {
    this.smb = smb;
    this.address = address;
    this.folders.addAll(settings.folders());
    this.folderRootChain = settings.folders().size() > 1;
    this.pageSize = pageSize;
  }

  @Override
  public List<FileContainer> containers() {
    return folders.stream().map(FileContainer::new).toList();
  }

  @Override
  public AbsenceProof absenceProof() {
    return AbsenceProof.LOCATION_IDENTITY;
  }

  @Override
  public FilePage list(FileContainer container, String continuation)
      throws FileAccessException, InterruptedException {
    String root = folder(container);
    Walk walk = walks.get(container.key());
    if (continuation == null) {
      walk = new Walk();
      walks.put(container.key(), walk);
      walk.stack.push(openRoot(walk, root));
    } else if (walk == null) {
      throw new IllegalStateException("no listing of " + container.key() + " to continue");
    }
    if (UNLISTABLE_PAGE.equals(continuation)) {
      walks.remove(container.key());
      throw unlistable(walk);
    }
    return walkOn(container, root, walk, new ArrayList<>());
  }

  /**
   * Opens the folders on the checkpoint's path again and goes on after its last entry, in name
   * order. A folder on the path that is gone counts as done; one replaced under its name by a
   * folder not met before is walked on. The folders met and the findings carry over, so a link and
   * an unreadable folder stay what they were.
   */
  @Override
  public FilePage resume(FileContainer container, String checkpoint)
      throws FileAccessException, InterruptedException {
    String root = folder(container);
    Checkpoint position = decode(checkpoint);
    Walk walk = new Walk();
    walk.visitedFolders.addAll(VisitedFolders.decode(position.visited()));
    walk.unreadable.addAll(position.unreadable());
    walk.unreadableCount = position.unreadableCount();
    walk.tooDeep.addAll(position.tooDeep());
    walk.tooDeepCount = position.tooDeepCount();
    walks.put(container.key(), walk);
    if (position.done()) {
      return finish(container, walk, new ArrayList<>());
    }
    List<FileEntry> entries = new ArrayList<>();
    Frame top = openRoot(walk, root);
    walk.stack.push(top);
    boolean reached = true;
    for (Step step : position.path()) {
      int at = atLeast(top.children, step.name());
      SmbShareClient.Item item = at < top.children.size() ? top.children.get(at) : null;
      if (item == null
          || !item.name().equals(step.name())
          || !item.directory()
          || item.link()
          || item.unusableName()
          || (item.fileId() != step.id() && !walk.firstVisit(item.fileId()))) {
        // gone, or replaced by a folder met before under another name: a link
        top.done = atMost(top.children, step.name());
        reached = false;
        break;
      }
      // the same folder, or one that replaced it under its name: walked on
      top.done = at;
      walk.firstVisit(item.fileId());
      Frame child = enter(container, walk, top, item, root, entries);
      if (child == null) {
        top.done = at + 1;
        reached = false;
        break;
      }
      walk.stack.push(child);
      top = child;
    }
    if (reached) {
      top.done = position.last() == null ? 0 : atMost(top.children, position.last());
    }
    return walkOn(container, root, walk, entries);
  }

  /** Walks on until the page is full or the walk is done. */
  private FilePage walkOn(FileContainer container, String root, Walk walk, List<FileEntry> entries)
      throws FileAccessException, InterruptedException {
    int opened = 0;
    while (entries.size() < pageSize && opened < pageSize) {
      Frame top = walk.stack.peek();
      if (top == null) {
        break;
      }
      if (top.done >= top.children.size()) {
        walk.stack.pop();
        Frame parent = walk.stack.peek();
        if (parent != null) {
          parent.done++;
        }
        continue;
      }
      SmbShareClient.Item item = top.children.get(top.done);
      if (item.unusableName()) {
        entries.add(skipped(container, root, top.folder, item, UNUSABLE_NAME_NOTE));
        top.done++;
      } else if (item.link() || (item.directory() && !walk.firstVisit(item.fileId()))) {
        // a folder met again under another name is a link the server resolved itself
        entries.add(skipped(container, root, top.folder, item, LINK_NOTE));
        top.done++;
      } else if (item.directory()) {
        opened++;
        Frame child = enter(container, walk, top, item, root, entries);
        if (child == null) {
          top.done++;
        } else {
          walk.stack.push(child);
        }
      } else {
        entries.add(entry(container, root, top.folder, item));
        top.done++;
      }
    }
    if (!walk.stack.isEmpty()) {
      return new FilePage(entries, Integer.toString(++walk.pages), checkpoint(walk, false));
    }
    return finish(container, walk, entries);
  }

  /** The last page of a walk; the gaps it found make the next call report the container. */
  private FilePage finish(FileContainer container, Walk walk, List<FileEntry> entries)
      throws FileAccessException {
    String done = checkpoint(walk, true);
    if (!walk.incomplete()) {
      walks.remove(container.key());
      return new FilePage(entries, null, done);
    }
    if (entries.isEmpty()) {
      walks.remove(container.key());
      throw unlistable(walk);
    }
    // the entries of this page are still delivered; the next call reports the gaps
    return new FilePage(entries, UNLISTABLE_PAGE, done);
  }

  @Override
  public FileEntry head(FileContainer container, String id)
      throws FileAccessException, InterruptedException {
    String root = folder(container);
    String rootPath = SmbSourceSettings.sharePath(root);
    if (!rootPath.isEmpty() && !id.startsWith(rootPath + "/")) {
      throw new FileAccessException.Gone("„" + id + "“ liegt nicht in „" + root + "“.");
    }
    Optional<SmbShareClient.Item> found;
    try {
      found = smb.find(id);
    } catch (SmbAccessException e) {
      throw translate(e);
    }
    if (found.isEmpty() || found.get().directory()) {
      throw new FileAccessException.Gone("„" + id + "“ ist keine Datei mehr.");
    }
    String relative = rootPath.isEmpty() ? id : id.substring(rootPath.length() + 1);
    List<String> segments = new ArrayList<>(List.of(relative.split("/")));
    segments.removeLast();
    String parent = id.contains("/") ? id.substring(0, id.lastIndexOf('/')) : "";
    Folder folder =
        new Folder(
            parent,
            String.join(SourceDocumentContext.HIERARCHY_SEPARATOR, segments),
            List.copyOf(segments));
    SmbShareClient.Item item = found.get();
    if (item.unusableName()) {
      return skipped(container, root, folder, item, UNUSABLE_NAME_NOTE);
    }
    return item.link()
        ? skipped(container, root, folder, item, LINK_NOTE)
        : entry(container, root, folder, item);
  }

  @Override
  public FetchedFile fetch(FileEntry entry, long maxBytes)
      throws FileAccessException, InterruptedException {
    if (entry.id().isEmpty()) {
      throw new FileAccessException.Unavailable("„" + entry.fileName() + "“ wird nicht abgerufen.");
    }
    Path file;
    try {
      file = smb.download(entry.id(), entry.fileName(), maxBytes);
    } catch (SmbAccessException e) {
      throw translate(e);
    }
    return new FetchedFile(file, file.toFile().length(), entry.changeMarker());
  }

  @Override
  public SourceRequestMeter meter() {
    return smb.meter();
  }

  @Override
  public void close() {
    walks.clear();
    smb.close();
  }

  /** The change feature: modification time (Windows ticks) and size. */
  static String changeMarker(SmbShareClient.Item item) {
    return "m:" + item.lastWriteTicks() + "|" + item.size();
  }

  /** The container's own folder, read in full; one it cannot read ends the listing. */
  private Frame openRoot(Walk walk, String root) throws FileAccessException, InterruptedException {
    Folder folder = new Folder(SmbSourceSettings.sharePath(root), "", List.of());
    List<SmbShareClient.Item> children;
    long folderId;
    try (SmbShareClient.Listing listing = smb.list(folder.sharePath())) {
      folderId = listing.folderId();
      children = readAll(listing);
    } catch (SmbAccessException e) {
      throw failure(e, true);
    } catch (SmbShareClient.ListingFailure e) {
      throw failure(e.failure(), true);
    }
    walk.firstVisit(folderId);
    return new Frame(folder, null, folderId, children);
  }

  /**
   * Opens the folder {@code item} of {@code parent}, read in full. A folder that its parent listed
   * but that cannot be opened as a folder - gone since, or a link the server resolved in the
   * listing - is skipped like a link and its files count as absent; one too deep or unreadable is
   * noted. Returns {@code null} for all of these.
   */
  private Frame enter(
      FileContainer container,
      Walk walk,
      Frame parent,
      SmbShareClient.Item item,
      String root,
      List<FileEntry> entries)
      throws FileAccessException, InterruptedException {
    List<String> segments = new ArrayList<>(parent.folder.segments());
    segments.add(item.name());
    Folder folder =
        new Folder(
            child(parent.folder.sharePath(), item.name()),
            hierarchy(parent.folder.hierarchyPath(), item.name()),
            List.copyOf(segments));
    if (segments.size() > MAX_DEPTH) {
      walk.tooDeepCount++;
      if (walk.tooDeep.size() < NAMED_UNREADABLE_FOLDERS) {
        walk.tooDeep.add(display(root, folder));
      }
      return null;
    }
    try (SmbShareClient.Listing listing = smb.list(folder.sharePath())) {
      return new Frame(folder, item.name(), item.fileId(), readAll(listing));
    } catch (SmbAccessException e) {
      if (e instanceof SmbAccessException.NotFound || e instanceof SmbAccessException.Link) {
        entries.add(
            skipped(
                container,
                root,
                parent.folder,
                new SmbShareClient.Item(item.name(), true, -1, 0, 0, 0, 0),
                LINK_NOTE));
        return null;
      }
      noteUnreadable(walk, folder, root, e);
      return null;
    } catch (SmbShareClient.ListingFailure e) {
      noteUnreadable(walk, folder, root, e.failure());
      return null;
    }
  }

  private static List<SmbShareClient.Item> readAll(SmbShareClient.Listing listing) {
    List<SmbShareClient.Item> items = new ArrayList<>();
    while (listing.hasNext()) {
      items.add(listing.next());
    }
    items.sort(BY_NAME);
    return items;
  }

  /**
   * A folder below the container that cannot be listed; a failure no later request mends ends it.
   */
  private static void noteUnreadable(
      Walk walk, Folder folder, String root, SmbAccessException failure)
      throws FileAccessException {
    FileAccessException ending = failure(failure, false);
    if (ending != null) {
      throw ending;
    }
    walk.unreadableCount++;
    if (walk.unreadable.size() < NAMED_UNREADABLE_FOLDERS) {
      walk.unreadable.add(display(root, folder));
    }
  }

  /**
   * What a failure to list a folder means: a refused sign-in or a lost server ends the run, the
   * container's own folder ({@code own}) ends its listing; else {@code null}.
   */
  private static FileAccessException failure(SmbAccessException failure, boolean own) {
    if (failure instanceof SmbAccessException.Authentication authentication) {
      return authentication.secretRejected()
          ? new FileAccessException.CredentialsRejected(failure.getMessage())
          : new FileAccessException.RunEnding(failure.getMessage());
    }
    if (failure instanceof SmbAccessException.Unreachable
        || failure instanceof SmbAccessException.ShareNotFound) {
      return new FileAccessException.RunEnding(failure.getMessage());
    }
    return own ? new FileAccessException.ContainerUnlistable(failure.getMessage()) : null;
  }

  /** The place after the last entry handed out; never the server, the share or a secret. */
  private static String checkpoint(Walk walk, boolean done) {
    List<Frame> frames = new ArrayList<>(walk.stack);
    java.util.Collections.reverse(frames);
    List<Step> path = new ArrayList<>();
    String last = null;
    for (int i = 0; i < frames.size(); i++) {
      Frame frame = frames.get(i);
      if (i > 0) {
        path.add(new Step(frame.name, frame.fileId));
      }
      if (i == frames.size() - 1 && frame.done > 0) {
        last = frame.children.get(Math.min(frame.done, frame.children.size()) - 1).name();
      }
    }
    Checkpoint checkpoint =
        new Checkpoint(
            path,
            last,
            done,
            VisitedFolders.encode(walk.visitedFolders),
            List.copyOf(walk.unreadable),
            walk.unreadableCount,
            List.copyOf(walk.tooDeep),
            walk.tooDeepCount);
    return CHECKPOINT_PREFIX + CHECKPOINT_JSON.writeValueAsString(checkpoint);
  }

  private static Checkpoint decode(String checkpoint) throws FileAccessException {
    if (checkpoint == null || !checkpoint.startsWith(CHECKPOINT_PREFIX)) {
      throw new FileAccessException.CheckpointExpired(
          "Der Fortsetzungspunkt stammt aus einer anderen Fassung.");
    }
    try {
      Checkpoint decoded =
          CHECKPOINT_JSON.readValue(
              checkpoint.substring(CHECKPOINT_PREFIX.length()), Checkpoint.class);
      if (decoded.path() == null || decoded.unreadable() == null || decoded.tooDeep() == null) {
        throw new FileAccessException.CheckpointExpired("Der Fortsetzungspunkt ist unlesbar.");
      }
      return decoded;
    } catch (JacksonException | IllegalArgumentException e) {
      throw new FileAccessException.CheckpointExpired("Der Fortsetzungspunkt ist unlesbar.");
    }
  }

  /** The index of the first entry whose name is not before {@code name}. */
  private static int atLeast(List<SmbShareClient.Item> items, String name) {
    int low = 0;
    int high = items.size();
    while (low < high) {
      int middle = (low + high) >>> 1;
      if (items.get(middle).name().compareTo(name) < 0) {
        low = middle + 1;
      } else {
        high = middle;
      }
    }
    return low;
  }

  /** The number of entries whose name is not after {@code name}. */
  private static int atMost(List<SmbShareClient.Item> items, String name) {
    int at = atLeast(items, name);
    return at < items.size() && items.get(at).name().equals(name) ? at + 1 : at;
  }

  private static FileAccessException.ContainerUnlistable unlistable(Walk walk) {
    List<String> findings = new ArrayList<>();
    if (walk.unreadableCount > 0) {
      findings.add(
          "Diese Ordner konnte das Dienstkonto nicht lesen: "
              + named(walk.unreadable, walk.unreadableCount));
    }
    if (walk.tooDeepCount > 0) {
      findings.add(
          "Diese Ordner liegen tiefer als "
              + MAX_DEPTH
              + " Ebenen und wurden nicht gelesen: "
              + named(walk.tooDeep, walk.tooDeepCount));
    }
    return new FileAccessException.ContainerUnlistable(String.join(" ", findings));
  }

  private static String named(List<String> named, int count) {
    String text = "„" + String.join("“, „", named) + "“";
    if (count > named.size()) {
      text += " und " + (count - named.size()) + " weitere";
    }
    return text + ".";
  }

  private FileEntry entry(
      FileContainer container, String root, Folder folder, SmbShareClient.Item item) {
    String id = child(folder.sharePath(), item.name());
    String filePath = address.filePath(id);
    Exclusion exclusion = null;
    if (item.offline()) {
      exclusion =
          new Exclusion.Unavailable(
              "„" + item.name() + "“ ist ausgelagert (offline); OPAA holt die Datei nicht zurück.");
    } else if (!FilePathLimit.fits(filePath)) {
      exclusion =
          new Exclusion.Unavailable(
              "Der Pfad von „"
                  + item.name()
                  + "“ ist länger als "
                  + FilePathLimit.MAX_CHARACTERS
                  + " Zeichen oder "
                  + FilePathLimit.MAX_BYTES
                  + " Byte (UTF-8); die Datei wird nicht gelesen.");
      filePath = FilePathLimit.cut(filePath);
    }
    return new FileEntry(
        container,
        exclusion == null ? id : "",
        filePath,
        item.name(),
        folderChain(root, folder),
        context(container, folder),
        item.size(),
        changeMarker(item),
        null,
        exclusion);
  }

  /** An entry counted as no document, never fetched: a link or a name that is no plain name. */
  private FileEntry skipped(
      FileContainer container, String root, Folder folder, SmbShareClient.Item item, String note) {
    String filePath = address.filePath(child(folder.sharePath(), item.name()));
    return new FileEntry(
        container,
        "",
        FilePathLimit.cut(filePath),
        item.name(),
        folderChain(root, folder),
        context(container, folder),
        -1,
        null,
        null,
        new Exclusion.NotADocument(note));
  }

  private SourceFolderPath folderChain(String root, Folder folder) {
    List<String> chain = new ArrayList<>();
    if (folderRootChain) {
      chain.addAll(SmbSourceSettings.segments(root));
    }
    chain.addAll(folder.segments());
    return SourceFolderPath.capped(chain);
  }

  private static SourceDocumentContext context(FileContainer container, Folder folder) {
    String hierarchy = folder.hierarchyPath();
    return new SourceDocumentContext(container.key(), hierarchy.isEmpty() ? null : hierarchy);
  }

  private String folder(FileContainer container) {
    if (!folders.contains(container.key())) {
      throw new IllegalArgumentException("not a folder of this library: " + container.key());
    }
    return container.key();
  }

  private static String child(String sharePath, String name) {
    return sharePath.isEmpty() ? name : sharePath + "/" + name;
  }

  private static String hierarchy(String hierarchyPath, String name) {
    return hierarchyPath.isEmpty()
        ? name
        : hierarchyPath + SourceDocumentContext.HIERARCHY_SEPARATOR + name;
  }

  private static String display(String root, Folder folder) {
    String below = String.join("/", folder.segments());
    if (below.isEmpty()) {
      return root;
    }
    return root.equals("/") ? "/" + below : root + "/" + below;
  }

  /** The neutral kind of a file-level failure; a refused sign-in or a lost server ends the run. */
  static FileAccessException translate(SmbAccessException e) {
    String message = e.getMessage();
    return switch (e) {
      case SmbAccessException.NotFound notFound -> new FileAccessException.Gone(message);
      case SmbAccessException.Link link -> new FileAccessException.Unavailable(message);
      case SmbAccessException.AccessDenied denied -> new FileAccessException.Unreadable(message);
      case SmbAccessException.TooLarge tooLarge -> new FileAccessException.TooLarge(message);
      case SmbAccessException.Authentication authentication ->
          authentication.secretRejected()
              ? new FileAccessException.CredentialsRejected(message)
              : new FileAccessException.RunEnding(message);
      case SmbAccessException.ShareNotFound shareNotFound ->
          new FileAccessException.RunEnding(message);
      case SmbAccessException.Unreachable unreachable -> new FileAccessException.RunEnding(message);
      default -> new FileAccessException.Transient(message);
    };
  }
}
