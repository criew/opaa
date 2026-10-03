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
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The files of a Nextcloud user as the {@link FileStore} of a full sync: a container is a
 * configured folder, a page one {@code PROPFIND} with depth 1 on one folder, and a folder whose
 * ETag equals the recalled one is reported unchanged instead of listed - its whole tree then costs
 * no request (ADR-0040, Nachtrag). A file's identity is its {@code oc:fileid} behind the address
 * that opens it in the web interface, so renaming and moving keep the document.
 */
final class NextcloudFileStore implements FileStore {

  private static final Logger log = LoggerFactory.getLogger(NextcloudFileStore.class);

  private final NextcloudDav dav;
  private final Set<String> folders = new LinkedHashSet<>();
  private final boolean folderRootChain;
  private final Map<String, Map<String, String>> recalled = new HashMap<>();
  private final Map<String, Deque<Folder>> pending = new HashMap<>();
  private int pages;

  /** One folder still to list, with its hierarchy path below the container. */
  private record Folder(String encodedPath, String hierarchyPath, List<String> segments) {}

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
    Deque<Folder> queue = pending.computeIfAbsent(container.key(), key -> new ArrayDeque<>());
    if (continuation == null) {
      queue.clear();
      queue.add(new Folder(encodedFolder(root), "", List.of()));
    }
    Folder folder = queue.poll();
    if (folder == null) {
      return new FilePage(List.of(), null);
    }
    List<DavResource> resources;
    try {
      resources =
          dav.propfind(folder.encodedPath(), 1, "den Ordner „" + display(root, folder) + "“");
    } catch (NextcloudAccessException.NotFound e) {
      // a folder gone since its parent's listing may only have been renamed: no deletion finding
      throw new FileAccessException.ContainerUnlistable(e.getMessage());
    } catch (NextcloudAccessException.Authentication | NextcloudAccessException.Unreachable e) {
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
    Map<String, String> recalledHere = recalled.getOrDefault(container.key(), Map.of());
    if (folder.hierarchyPath().isEmpty() && self.etag().equals(recalledHere.get(""))) {
      queue.clear();
      return new FilePage(List.of(), null, List.of(""), Map.of());
    }
    List<FileEntry> entries = new ArrayList<>();
    List<String> unchanged = new ArrayList<>();
    String filesRoot = filesRoot();
    for (DavResource resource : resources) {
      if (resource == self) {
        continue;
      }
      String name = resource.name();
      if (!dav.connection().isOwnFilePath(resource.href(), filesRoot)) {
        // credentials only ever go to this instance; a folder behind such an address keeps its
        // bestand, a file is skipped like an unavailable one
        log.warn("Nextcloud answered a foreign address for an entry of {}", container.key());
        if (resource.collection()) {
          throw new FileAccessException.ContainerUnlistable(
              "Nextcloud nannte für einen Ordner in „"
                  + display(root, folder)
                  + "“ eine Adresse außerhalb der Instanz; er wird nicht abgeglichen.");
        }
        entries.add(foreign(container, root, folder, resource));
        continue;
      }
      if (resource.collection()) {
        String hierarchy = child(folder.hierarchyPath(), name);
        if (resource.etag() != null && resource.etag().equals(recalledHere.get(hierarchy))) {
          unchanged.add(hierarchy);
        } else {
          List<String> segments = new ArrayList<>(folder.segments());
          segments.add(name);
          queue.add(new Folder(resource.href(), hierarchy, List.copyOf(segments)));
        }
      } else {
        entries.add(entry(container, root, folder, resource));
      }
    }
    return new FilePage(
        entries, next(queue), unchanged, Map.of(folder.hierarchyPath(), self.etag()));
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

  private String next(Deque<Folder> queue) {
    return queue.isEmpty() ? null : Integer.toString(++pages);
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
   * The neutral kind of a file-level failure; a refused login or an unreachable host ends the run.
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
          new FileAccessException.RunEnding(message);
      case NextcloudAccessException.Unreachable unreachable ->
          new FileAccessException.RunEnding(message);
      default -> new FileAccessException.Transient(message);
    };
  }
}
