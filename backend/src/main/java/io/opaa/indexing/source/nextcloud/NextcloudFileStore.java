package io.opaa.indexing.source.nextcloud;

import io.opaa.indexing.filesync.Exclusion;
import io.opaa.indexing.filesync.FetchedFile;
import io.opaa.indexing.filesync.FileAccessException;
import io.opaa.indexing.filesync.FileContainer;
import io.opaa.indexing.filesync.FileEntry;
import io.opaa.indexing.filesync.FilePage;
import io.opaa.indexing.filesync.FileStore;
import io.opaa.indexing.source.SourceFolderPath;
import io.opaa.knowledge.SourceDocumentContext;
import io.opaa.sourceaccess.BoundedDownloader;
import io.opaa.sourceaccess.SourceRequestMeter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * The files of a Nextcloud user as the {@link FileStore} of a full sync: a container is a
 * configured folder, a page one {@code PROPFIND} with depth 1 on one folder, walked depth first in
 * name order, and a folder whose ETag equals the recalled one is reported unchanged instead of
 * listed - its whole tree then costs no request (ADR-0040, Nachtrag). A page's checkpoint is the
 * path of its folder; resuming lists the folders on that path once more to find what follows. A
 * file's identity is its {@code oc:fileid} behind the address that opens it in the web interface,
 * so renaming and moving keep the document.
 */
final class NextcloudFileStore implements FileStore {

  private static final Logger log = LoggerFactory.getLogger(NextcloudFileStore.class);

  private static final String CHECKPOINT_PREFIX = "nc1:";
  private static final JsonMapper CHECKPOINT_JSON = JsonMapper.builder().build();

  private final NextcloudDav dav;
  private final Set<String> folders = new LinkedHashSet<>();
  private final boolean folderRootChain;
  private final Map<String, Map<String, String>> recalled = new HashMap<>();
  private final Map<String, Deque<Folder>> pending = new HashMap<>();
  private int pages;

  /** One folder still to list, with its hierarchy path below the container. */
  private record Folder(String encodedPath, String hierarchyPath, List<String> segments) {}

  /** One folder's own resource and those of its direct entries. */
  private record Listed(DavResource self, List<DavResource> children) {}

  NextcloudFileStore(NextcloudDav dav, NextcloudSourceSettings settings) {
    this.dav = dav;
    folders.addAll(settings.folders());
    this.folderRootChain = settings.folders().size() > 1;
  }

  @Override
  public List<FileContainer> containers() {
    return folders.stream().map(FileContainer::new).toList();
  }

  @Override
  public void recall(FileContainer container, Map<String, String> subtreeMarkers) {
    recalled.put(container.key(), Map.copyOf(subtreeMarkers));
  }

  @Override
  public FilePage list(FileContainer container, String continuation)
      throws FileAccessException, InterruptedException {
    String root = folder(container);
    Deque<Folder> stack = pending.computeIfAbsent(container.key(), key -> new ArrayDeque<>());
    if (continuation == null) {
      stack.clear();
      stack.push(new Folder(encodedFolder(root), "", List.of()));
    }
    Folder folder = stack.poll();
    if (folder == null) {
      return new FilePage(List.of(), null);
    }
    return page(container, root, folder, stack, true);
  }

  /**
   * Lists the folders on the checkpoint's path again, from the container down: what follows its
   * folder in name order, and that folder's own subfolders, is listed next. A folder on the path
   * that is gone since ends the descent there; what follows it is still listed.
   */
  @Override
  public FilePage resume(FileContainer container, String checkpoint)
      throws FileAccessException, InterruptedException {
    String root = folder(container);
    List<String> position = position(checkpoint);
    Deque<Folder> stack = pending.computeIfAbsent(container.key(), key -> new ArrayDeque<>());
    stack.clear();
    Map<String, String> recalledHere = recalled.getOrDefault(container.key(), Map.of());
    List<String> unchanged = new ArrayList<>();
    Folder current = new Folder(encodedFolder(root), "", List.of());
    for (int depth = 0; ; depth++) {
      Listed listed = listFolder(container, root, current, depth > 0);
      if (listed == null) {
        // a folder on the path vanished: its rest is no longer there, what follows still is
        break;
      }
      String target = depth < position.size() ? position.get(depth) : null;
      Folder descend = null;
      List<Folder> later = new ArrayList<>();
      for (DavResource resource : listed.children()) {
        if (!resource.collection()) {
          continue;
        }
        Folder child = child(current, resource);
        if (target == null || resource.name().compareTo(target) > 0) {
          if (resource.etag() != null
              && resource.etag().equals(recalledHere.get(child.hierarchyPath()))) {
            unchanged.add(child.hierarchyPath());
          } else {
            later.add(child);
          }
        } else if (resource.name().equals(target)) {
          descend = child;
        }
      }
      pushInOrder(stack, later);
      if (target == null || descend == null) {
        break;
      }
      current = descend;
    }
    Folder next = stack.poll();
    if (next == null) {
      return new FilePage(List.of(), null, checkpoint, unchanged, Map.of());
    }
    FilePage page = page(container, root, next, stack, false);
    List<String> allUnchanged = new ArrayList<>(unchanged);
    allUnchanged.addAll(page.unchangedSubtrees());
    return new FilePage(
        page.entries(), page.next(), page.checkpoint(), allUnchanged, page.listedSubtrees());
  }

  /**
   * One folder as a page: its files, its subfolders onto the stack in name order - or reported
   * unchanged - and its path as the checkpoint. The container's own folder may be reported
   * unchanged as a whole only on a first page.
   */
  private FilePage page(
      FileContainer container, String root, Folder folder, Deque<Folder> stack, boolean first)
      throws FileAccessException, InterruptedException {
    Listed listed = listFolder(container, root, folder, false);
    DavResource self = listed.self();
    Map<String, String> recalledHere = recalled.getOrDefault(container.key(), Map.of());
    if (first && folder.hierarchyPath().isEmpty() && self.etag().equals(recalledHere.get(""))) {
      stack.clear();
      return new FilePage(List.of(), null, checkpoint(folder), List.of(""), Map.of());
    }
    List<FileEntry> entries = new ArrayList<>();
    List<String> unchanged = new ArrayList<>();
    List<Folder> subfolders = new ArrayList<>();
    for (DavResource resource : listed.children()) {
      if (!resource.collection()) {
        entries.add(
            dav.connection().isOwnFilePath(resource.href(), filesRoot())
                ? entry(container, root, folder, resource)
                : foreign(container, root, folder, resource));
        continue;
      }
      Folder child = child(folder, resource);
      if (resource.etag() != null
          && resource.etag().equals(recalledHere.get(child.hierarchyPath()))) {
        unchanged.add(child.hierarchyPath());
      } else {
        subfolders.add(child);
      }
    }
    pushInOrder(stack, subfolders);
    return new FilePage(
        entries,
        next(stack),
        checkpoint(folder),
        unchanged,
        Map.of(folder.hierarchyPath(), self.etag()));
  }

  /**
   * One {@code PROPFIND} with depth 1 on {@code folder}; a folder without an ETag or behind a
   * foreign address cannot be listed, nor one gone since - unless {@code vanishedIsNull}, then it
   * is {@code null}.
   */
  private Listed listFolder(
      FileContainer container, String root, Folder folder, boolean vanishedIsNull)
      throws FileAccessException, InterruptedException {
    List<DavResource> resources;
    try {
      resources =
          dav.propfind(folder.encodedPath(), 1, "den Ordner „" + display(root, folder) + "“");
    } catch (NextcloudAccessException.NotFound e) {
      if (vanishedIsNull) {
        return null;
      }
      // a folder gone since its parent's listing may only have been renamed: no deletion finding
      throw new FileAccessException.ContainerUnlistable(e.getMessage());
    } catch (NextcloudAccessException.Authentication e) {
      throw new FileAccessException.CredentialsRejected(e.getMessage());
    } catch (NextcloudAccessException.Unreachable e) {
      throw new FileAccessException.RunEnding(e.getMessage());
    } catch (NextcloudAccessException e) {
      throw new FileAccessException.ContainerUnlistable(e.getMessage());
    }
    String selfPath = stripSlash(DavPaths.decode(DavPaths.pathOf(folder.encodedPath())));
    DavResource self =
        resources.stream()
            .filter(resource -> resource.path().equals(selfPath))
            .findFirst()
            .orElse(null);
    if (self == null || self.etag() == null) {
      throw new FileAccessException.ContainerUnlistable(
          "Nextcloud nannte für den Ordner „"
              + display(root, folder)
              + "“ keine Prüfsumme (ETag); er wird nicht abgeglichen.");
    }
    String filesRoot = filesRoot();
    List<DavResource> children = new ArrayList<>();
    for (DavResource resource : resources) {
      if (resource == self) {
        continue;
      }
      if (resource.collection() && !dav.connection().isOwnFilePath(resource.href(), filesRoot)) {
        // credentials only ever go to this instance; a folder behind such an address keeps its
        // bestand, a file is skipped like an unavailable one
        log.warn("Nextcloud answered a foreign address for an entry of {}", container.key());
        throw new FileAccessException.ContainerUnlistable(
            "Nextcloud nannte für einen Ordner in „"
                + display(root, folder)
                + "“ eine Adresse außerhalb der Instanz; er wird nicht abgeglichen.");
      }
      children.add(resource);
    }
    return new Listed(self, children);
  }

  private static Folder child(Folder parent, DavResource resource) {
    List<String> segments = new ArrayList<>(parent.segments());
    segments.add(resource.name());
    return new Folder(
        resource.href(), child(parent.hierarchyPath(), resource.name()), List.copyOf(segments));
  }

  /** Pushes {@code folders} so that the first in name order is listed next. */
  private static void pushInOrder(Deque<Folder> stack, List<Folder> folders) {
    List<Folder> sorted = new ArrayList<>(folders);
    sorted.sort(Comparator.comparing((Folder folder) -> folder.segments().getLast()).reversed());
    sorted.forEach(stack::push);
  }

  /** The checkpoint after {@code folder}: its path below the container, never an address. */
  private static String checkpoint(Folder folder) {
    return CHECKPOINT_PREFIX + CHECKPOINT_JSON.writeValueAsString(folder.segments());
  }

  private static List<String> position(String checkpoint) throws FileAccessException {
    if (checkpoint == null || !checkpoint.startsWith(CHECKPOINT_PREFIX)) {
      throw new FileAccessException.CheckpointExpired(
          "Der Fortsetzungspunkt stammt aus einer anderen Fassung.");
    }
    try {
      List<String> segments =
          CHECKPOINT_JSON.readValue(
              checkpoint.substring(CHECKPOINT_PREFIX.length()),
              new TypeReference<List<String>>() {});
      if (segments == null || segments.stream().anyMatch(s -> s == null || s.isEmpty())) {
        throw new FileAccessException.CheckpointExpired("Der Fortsetzungspunkt ist unlesbar.");
      }
      return segments;
    } catch (JacksonException e) {
      throw new FileAccessException.CheckpointExpired("Der Fortsetzungspunkt ist unlesbar.");
    }
  }

  @Override
  public FileEntry head(FileContainer container, String id)
      throws FileAccessException, InterruptedException {
    String root = folder(container);
    List<DavResource> resources;
    try {
      resources = dav.propfind(id, 0, "die Datei „" + DavPaths.decode(id) + "“");
    } catch (NextcloudAccessException e) {
      throw translate(e);
    }
    DavResource resource =
        resources.stream()
            .filter(candidate -> !candidate.collection())
            .findFirst()
            .orElseThrow(
                () ->
                    new FileAccessException.Gone(
                        "„" + DavPaths.decode(id) + "“ ist keine Datei mehr."));
    String rootPath = DavPaths.decode(encodedFolder(root));
    String relative = stripSlash(resource.path()).substring(stripSlash(rootPath).length());
    List<String> segments = new ArrayList<>();
    for (String segment : relative.split("/")) {
      if (!segment.isEmpty()) {
        segments.add(segment);
      }
    }
    segments.removeLast();
    Folder folder =
        new Folder(
            "",
            String.join(SourceDocumentContext.HIERARCHY_SEPARATOR, segments),
            List.copyOf(segments));
    return dav.connection().isOwnFilePath(resource.href(), filesRoot())
        ? entry(container, root, folder, resource)
        : foreign(container, root, folder, resource);
  }

  @Override
  public FetchedFile fetch(FileEntry entry, long maxBytes)
      throws FileAccessException, InterruptedException {
    if (!dav.connection().isOwnFilePath(entry.id(), filesRoot())) {
      throw new FileAccessException.Unavailable(
          "„" + entry.fileName() + "“ liegt nicht unter der Adresse der Nextcloud.");
    }
    BoundedDownloader.DownloadedFile file;
    try {
      file = dav.download(entry.id(), entry.fileName(), maxBytes);
    } catch (NextcloudAccessException e) {
      throw translate(e);
    }
    return new FetchedFile(file.path(), file.path().toFile().length(), entry.changeMarker());
  }

  @Override
  public SourceRequestMeter meter() {
    return dav.meter();
  }

  @Override
  public void close() {
    dav.close();
  }

  private FileEntry entry(FileContainer container, String root, Folder folder, DavResource resource)
      throws FileAccessException {
    if (resource.fileId() == null) {
      throw new FileAccessException.ContainerUnlistable(
          "Nextcloud nannte für „"
              + resource.path()
              + "“ keine Datei-ID (oc:fileid); ohne sie lässt sich die Datei nicht verfolgen.");
    }
    List<String> chain = new ArrayList<>();
    if (folderRootChain) {
      chain.addAll(NextcloudSourceSettings.segments(root));
    }
    chain.addAll(folder.segments());
    String hierarchy = folder.hierarchyPath();
    return new FileEntry(
        container,
        resource.href(),
        dav.connection().deepLink(resource.fileId()),
        resource.name(),
        SourceFolderPath.capped(chain),
        new SourceDocumentContext(container.key(), hierarchy.isEmpty() ? null : hierarchy),
        resource.size(),
        NextcloudChangeMarker.of(resource.etag(), resource.size()),
        resource.contentType(),
        null);
  }

  /** A file behind a foreign address: present, never requested, named in the protocol. */
  private FileEntry foreign(
      FileContainer container, String root, Folder folder, DavResource resource)
      throws FileAccessException {
    FileEntry entry = entry(container, root, folder, resource);
    return new FileEntry(
        container,
        "",
        entry.filePath(),
        entry.fileName(),
        entry.folder(),
        entry.context(),
        entry.size(),
        entry.changeMarker(),
        entry.mediaType(),
        new Exclusion.Unavailable(
            "Nextcloud nannte für „"
                + entry.fileName()
                + "“ eine Adresse außerhalb der Instanz; die Datei wird nicht abgerufen."));
  }

  private String filesRoot() throws FileAccessException, InterruptedException {
    try {
      return dav.filesRoot();
    } catch (NextcloudAccessException e) {
      throw translate(e);
    }
  }

  /** The encoded absolute path of the configured {@code folder} below the user's files. */
  private String encodedFolder(String folder) throws FileAccessException, InterruptedException {
    String filesRoot;
    try {
      filesRoot = dav.filesRoot();
    } catch (NextcloudAccessException e) {
      throw translate(e);
    }
    return folder.equals("/") ? filesRoot + "/" : filesRoot + DavPaths.encodePath(folder) + "/";
  }

  private String folder(FileContainer container) {
    if (!folders.contains(container.key())) {
      throw new IllegalArgumentException("not a folder of this library: " + container.key());
    }
    return container.key();
  }

  private String next(Deque<Folder> stack) {
    return stack.isEmpty() ? null : Integer.toString(++pages);
  }

  private static String child(String hierarchy, String name) {
    return hierarchy.isEmpty()
        ? name
        : hierarchy + SourceDocumentContext.HIERARCHY_SEPARATOR + name;
  }

  private static String display(String root, Folder folder) {
    String below = String.join("/", folder.segments());
    if (below.isEmpty()) {
      return root;
    }
    return root.equals("/") ? "/" + below : root + "/" + below;
  }

  private static String stripSlash(String path) {
    return path.length() > 1 && path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
  }

  /**
   * The neutral kind of a file-level failure; a rejected or refused sign-in or an unreachable host
   * ends the run.
   */
  private static FileAccessException translate(NextcloudAccessException e) {
    String message = e.getMessage();
    return switch (e) {
      case NextcloudAccessException.NotFound notFound -> new FileAccessException.Gone(message);
      case NextcloudAccessException.Forbidden forbidden ->
          new FileAccessException.Unreadable(message);
      case NextcloudAccessException.Unopenable unopenable ->
          new FileAccessException.Unreadable(message);
      case NextcloudAccessException.TooLarge tooLarge -> new FileAccessException.TooLarge(message);
      case NextcloudAccessException.Authentication authentication ->
          new FileAccessException.CredentialsRejected(message);
      case NextcloudAccessException.Unreachable unreachable ->
          new FileAccessException.RunEnding(message);
      case NextcloudAccessException.SignInRefused refused ->
          new FileAccessException.RunEnding(message);
      default -> new FileAccessException.Transient(message);
    };
  }
}
