package io.opaa.indexing.source.smb;

import io.opaa.indexing.filesync.Exclusion;
import io.opaa.indexing.filesync.FetchedFile;
import io.opaa.indexing.filesync.FileAccessException;
import io.opaa.indexing.filesync.FileContainer;
import io.opaa.indexing.filesync.FileEntry;
import io.opaa.indexing.filesync.FilePage;
import io.opaa.indexing.filesync.FileStore;
import io.opaa.indexing.source.SourceFolderPath;
import io.opaa.knowledge.SourceDocumentContext;
import io.opaa.sourceaccess.SourceRequestMeter;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The files of a share as the {@link FileStore} of a full sync: a container is a configured folder,
 * walked breadth first, a page at most {@code pageSize} entries - a large folder is read batch by
 * batch across pages. A file's identity is its path below the share; the change feature is its
 * modification time and size. SMB has no folder feature that changes with everything below it, so
 * no folder is reported unchanged and every run lists everything.
 *
 * <p>A link is listed but neither fetched nor followed. A folder below the container that cannot be
 * read is named and the rest is listed; the container then counts as incompletely listed, so
 * nothing in it is removed.
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

  private static final int MAX_FILE_PATH_LENGTH = 2000;
  private static final int NAMED_UNREADABLE_FOLDERS = 5;
  private static final String UNLISTABLE_PAGE = "unlistable";

  private final SmbShareClient smb;
  private final SmbAddress address;
  private final Set<String> folders = new LinkedHashSet<>();
  private final boolean folderRootChain;
  private final int pageSize;
  private final Map<String, Walk> walks = new HashMap<>();

  /** One folder still to list, with its hierarchy path below the container. */
  private record Folder(String sharePath, String hierarchyPath, List<String> segments) {}

  /** The walk of one container within this run. */
  private static final class Walk {
    private final Deque<Folder> queue = new ArrayDeque<>();
    private final List<String> unreadable = new ArrayList<>();
    private final List<String> tooDeep = new ArrayList<>();
    private final Set<Long> visitedFolders = new HashSet<>();
    private Folder current;
    private SmbShareClient.Listing listing;
    private int pages;

    /**
     * Whether the folder with {@code fileId} is met for the first time; {@code 0} and {@code -1}
     * mean the server tells no id.
     */
    private boolean firstVisit(long fileId) {
      return fileId == 0 || fileId == -1 || visitedFolders.add(fileId);
    }

    private void closeListing() {
      if (listing != null) {
        listing.close();
        listing = null;
        current = null;
      }
    }
  }

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
  public FilePage list(FileContainer container, String continuation)
      throws FileAccessException, InterruptedException {
    String root = folder(container);
    Walk walk = walks.get(container.key());
    if (continuation == null) {
      if (walk != null) {
        walk.closeListing();
      }
      walk = new Walk();
      walks.put(container.key(), walk);
      walk.queue.add(new Folder(SmbSourceSettings.sharePath(root), "", List.of()));
    } else if (walk == null) {
      throw new IllegalStateException("no listing of " + container.key() + " to continue");
    }
    if (UNLISTABLE_PAGE.equals(continuation)) {
      walks.remove(container.key());
      throw unlistable(walk);
    }
    List<FileEntry> entries = new ArrayList<>();
    while (entries.size() < pageSize) {
      if (walk.listing == null) {
        Folder next = walk.queue.poll();
        if (next == null) {
          break;
        }
        FileEntry vanished = open(container, walk, next, root);
        if (vanished != null) {
          entries.add(vanished);
        }
        continue;
      }
      SmbShareClient.Item item;
      try {
        if (!walk.listing.hasNext()) {
          walk.closeListing();
          continue;
        }
        item = walk.listing.next();
      } catch (SmbShareClient.ListingFailure failure) {
        Folder broken = walk.current;
        walk.closeListing();
        unreadable(walk, broken, root, failure.failure());
        continue;
      }
      Folder folder = walk.current;
      if (item.unusableName()) {
        entries.add(skipped(container, root, folder, item, UNUSABLE_NAME_NOTE));
      } else if (item.link() || (item.directory() && !walk.firstVisit(item.fileId()))) {
        // a folder met again under another name is a link the server resolved itself
        entries.add(skipped(container, root, folder, item, LINK_NOTE));
      } else if (item.directory()) {
        List<String> segments = new ArrayList<>(folder.segments());
        segments.add(item.name());
        Folder child =
            new Folder(
                child(folder.sharePath(), item.name()),
                hierarchy(folder.hierarchyPath(), item.name()),
                List.copyOf(segments));
        if (segments.size() > MAX_DEPTH) {
          walk.tooDeep.add(display(root, child));
        } else {
          walk.queue.add(child);
        }
      } else {
        entries.add(entry(container, root, folder, item));
      }
    }
    boolean exhausted = walk.listing == null && walk.queue.isEmpty();
    if (!exhausted) {
      return new FilePage(entries, Integer.toString(++walk.pages));
    }
    if (walk.unreadable.isEmpty() && walk.tooDeep.isEmpty()) {
      walks.remove(container.key());
      return new FilePage(entries, null);
    }
    if (entries.isEmpty()) {
      walks.remove(container.key());
      throw unlistable(walk);
    }
    // the entries of this page are still delivered; the next call reports the gaps
    return new FilePage(entries, UNLISTABLE_PAGE);
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
    for (Walk walk : walks.values()) {
      walk.closeListing();
    }
    walks.clear();
    smb.close();
  }

  /** The change feature: modification time (Windows ticks) and size. */
  static String changeMarker(SmbShareClient.Item item) {
    return "m:" + item.lastWriteTicks() + "|" + item.size();
  }

  /**
   * Opens {@code next} for listing. A folder below the container that its parent listed but that
   * cannot be opened as a folder - gone since, or a link the server resolved in the listing - is
   * skipped like a link: its files count as absent. Returns that skipped entry, else {@code null}.
   */
  private FileEntry open(FileContainer container, Walk walk, Folder next, String root)
      throws FileAccessException, InterruptedException {
    try {
      walk.listing = smb.list(next.sharePath());
      walk.current = next;
      if (next.hierarchyPath().isEmpty()) {
        walk.firstVisit(walk.listing.folderId());
      }
      return null;
    } catch (SmbAccessException e) {
      walk.closeListing();
      if (!next.hierarchyPath().isEmpty()
          && (e instanceof SmbAccessException.NotFound || e instanceof SmbAccessException.Link)) {
        List<String> parentSegments = next.segments().subList(0, next.segments().size() - 1);
        String name = next.segments().getLast();
        int slash = next.sharePath().lastIndexOf('/');
        Folder parent =
            new Folder(
                slash < 0 ? "" : next.sharePath().substring(0, slash),
                String.join(SourceDocumentContext.HIERARCHY_SEPARATOR, parentSegments),
                parentSegments);
        return skipped(
            container,
            root,
            parent,
            new SmbShareClient.Item(name, true, -1, 0, 0, 0, 0),
            LINK_NOTE);
      }
      unreadable(walk, next, root, e);
      return null;
    }
  }

  /**
   * Notes a folder that cannot be listed; the container's own folder, or a failure no later request
   * will mend, ends the listing at once.
   */
  private void unreadable(Walk walk, Folder folder, String root, SmbAccessException failure)
      throws FileAccessException {
    if (failure instanceof SmbAccessException.Authentication authentication) {
      throw authentication.secretRejected()
          ? new FileAccessException.CredentialsRejected(failure.getMessage())
          : new FileAccessException.RunEnding(failure.getMessage());
    }
    if (failure instanceof SmbAccessException.Unreachable
        || failure instanceof SmbAccessException.ShareNotFound) {
      throw new FileAccessException.RunEnding(failure.getMessage());
    }
    if (folder.hierarchyPath().isEmpty()) {
      walk.closeListing();
      walk.queue.clear();
      throw new FileAccessException.ContainerUnlistable(failure.getMessage());
    }
    walk.unreadable.add(display(root, folder));
  }

  private static FileAccessException.ContainerUnlistable unlistable(Walk walk) {
    List<String> findings = new ArrayList<>();
    if (!walk.unreadable.isEmpty()) {
      findings.add("Diese Ordner konnte das Dienstkonto nicht lesen: " + named(walk.unreadable));
    }
    if (!walk.tooDeep.isEmpty()) {
      findings.add(
          "Diese Ordner liegen tiefer als "
              + MAX_DEPTH
              + " Ebenen und wurden nicht gelesen: "
              + named(walk.tooDeep));
    }
    return new FileAccessException.ContainerUnlistable(String.join(" ", findings));
  }

  private static String named(List<String> folders) {
    List<String> named = folders.subList(0, Math.min(NAMED_UNREADABLE_FOLDERS, folders.size()));
    String text = "„" + String.join("“, „", named) + "“";
    if (folders.size() > named.size()) {
      text += " und " + (folders.size() - named.size()) + " weitere";
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
    } else if (filePath.length() > MAX_FILE_PATH_LENGTH) {
      exclusion =
          new Exclusion.Unavailable(
              "Der Pfad von „"
                  + item.name()
                  + "“ ist länger als "
                  + MAX_FILE_PATH_LENGTH
                  + " Zeichen; die Datei wird nicht gelesen.");
      filePath = filePath.substring(0, MAX_FILE_PATH_LENGTH);
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
        filePath.length() > MAX_FILE_PATH_LENGTH
            ? filePath.substring(0, MAX_FILE_PATH_LENGTH)
            : filePath,
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
