package io.opaa.indexing.source.sharepoint;

import io.opaa.indexing.filesync.AbsenceProof;
import io.opaa.indexing.filesync.Change;
import io.opaa.indexing.filesync.ChangeFeed;
import io.opaa.indexing.filesync.ChangePage;
import io.opaa.indexing.filesync.Exclusion;
import io.opaa.indexing.filesync.FetchedFile;
import io.opaa.indexing.filesync.FileAccessException;
import io.opaa.indexing.filesync.FileContainer;
import io.opaa.indexing.filesync.FileEntry;
import io.opaa.indexing.filesync.FilePage;
import io.opaa.indexing.filesync.FileStore;
import io.opaa.indexing.source.SourceFolderPath;
import io.opaa.knowledge.SourceDocumentContext;
import io.opaa.msgraph.GraphClient;
import io.opaa.msgraph.GraphException;
import io.opaa.msgraph.GraphPage;
import io.opaa.sourceaccess.SourceRequestMeter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

/**
 * The {@link FileStore} and {@link ChangeFeed} of one SharePoint library for one run (ADR-0040,
 * Nachtrag „SharePoint“). Every document library is a container, listed and followed through {@code
 * /drives/<id>/root/delta}; a page token is both the continuation within a run and the checkpoint
 * across runs, a delta token the stream's cursor. Delta entries carry no path: the folder chain
 * follows the parents by id, each unknown folder read once per run. A folder filter keeps only
 * files below one of its folders. The identity is {@code sharepoint://<drive>/<item>}.
 */
final class SharePointFileStore implements FileStore, ChangeFeed {

  private static final Logger log = LoggerFactory.getLogger(SharePointFileStore.class);

  static final String PATH_PREFIX = "sharepoint://";
  static final String ONENOTE_NOTE = " OneNote-Notizbücher und ihre Abschnitte übersprungen";
  static final String OUTSIDE_FOLDERS_NOTE = " Dateien außerhalb der gewählten Ordner übersprungen";
  static final String MALWARE =
      "Microsoft meldet in dieser Datei Schadsoftware; sie wird übersprungen.";
  static final String REFUSED_DOWNLOAD_SUFFIX =
      " Die Datei wird übersprungen; ein späterer Vollabgleich versucht es erneut.";
  static final String NOT_A_LIBRARY =
      "Das Laufwerk ist keine SharePoint-Dokumentbibliothek; OneDrive und andere Laufwerke werden"
          + " nicht gelesen.";
  static final String LIBRARY_NOT_VISIBLE =
      "Die Dokumentbibliothek ist für die Anwendung nicht sichtbar. Bei der Berechtigung"
          + " Sites.Selected muss die Site für die App freigegeben sein.";
  static final String LISTING_RESUMES_SUFFIX =
      " Der nächste Lauf setzt die Auflistung am zuletzt gesicherten Punkt fort.";

  /**
   * The cursor of a library Graph refused at the start of a full sync. It is never sent: reading it
   * expires the stream, so the round proves no absence and the next run is a full sync.
   */
  static final String UNREACHABLE_AT_START = "~unreachable";

  /** Deeper chains are cut off: a cycle in the parents never ends a walk otherwise. */
  private static final int MAX_CHAIN = 500;

  /** Longer parts of a change marker are hashed; the marker column holds 64 characters. */
  private static final int MAX_MARKER_PART = 32;

  private final GraphClient graph;
  private final SourceRequestMeter meter;
  private final SharePointSettings settings;
  private final int pageSize;
  private final Map<String, SharePointLibrary> librariesByKey = new LinkedHashMap<>();
  private final Map<String, String> libraryNames = new ConcurrentHashMap<>();

  /** Folders, packages and roots seen or read in this run, by drive and item id. */
  private final Map<String, DriveItem> holders = new ConcurrentHashMap<>();

  /** Files and packages a listing of this run delivered, by drive and item id. */
  private final Set<String> delivered = ConcurrentHashMap.newKeySet();

  SharePointFileStore(
      GraphClient graph, SourceRequestMeter meter, SharePointSettings settings, int pageSize) {
    this.graph = graph;
    this.meter = meter;
    this.settings = settings;
    this.pageSize = pageSize;
    for (SharePointLibrary library : settings.libraries()) {
      librariesByKey.put(library.key(), library);
    }
  }

  static String filePath(String driveId, String itemId) {
    return PATH_PREFIX + driveId + "/" + itemId;
  }

  @Override
  public List<FileContainer> containers() {
    return settings.libraries().stream().map(SharePointLibrary::container).toList();
  }

  @Override
  public AbsenceProof absenceProof() {
    return AbsenceProof.CHANGE_FEED;
  }

  // --- listing ---------------------------------------------------------------------------------

  @Override
  public FilePage list(FileContainer container, String continuation)
      throws FileAccessException, InterruptedException {
    SharePointLibrary library = library(container);
    if (continuation == null) {
      requireReachable(container);
    }
    return listingPage(library, continuation);
  }

  @Override
  public FilePage resume(FileContainer container, String checkpoint)
      throws FileAccessException, InterruptedException {
    SharePointLibrary library = library(container);
    requireReachable(container);
    return listingPage(library, checkpoint);
  }

  /**
   * One page of the library's enumeration: its files and packages inside the folder filter, each
   * once per run. Its page token is continuation and checkpoint alike - never an address.
   */
  private FilePage listingPage(SharePointLibrary library, String token)
      throws FileAccessException, InterruptedException {
    try {
      GraphPage page = graph.page(deltaPath(library), query(), token);
      List<FileEntry> entries = new ArrayList<>();
      for (JsonNode node : page.value()) {
        DriveItem item = DriveItem.of(node);
        if (item.id() == null || item.deleted()) {
          continue;
        }
        remember(library, item);
        String key = library.driveId() + "/" + item.id();
        if (item.holdsItems() || delivered.contains(key)) {
          continue;
        }
        Chain chain = chain(library, item.parentId());
        if (chain == null) {
          log.info(
              "Folder chain of SharePoint item {} no longer reaches its library - not listed",
              item.id());
        } else if (chain.within(library) && delivered.add(key)) {
          // only a report that is listed counts: a stale earlier one must not hide the last
          entries.add(entry(library, item, chain, null));
        }
      }
      String next = page.nextToken();
      if (next == null && page.deltaToken() == null) {
        throw new FileAccessException.RunEnding(
            "Microsoft Graph hat die Auflistung ohne Folgeseite und ohne Abschluss beendet."
                + LISTING_RESUMES_SUFFIX);
      }
      return new FilePage(entries, next, next);
    } catch (GraphException e) {
      throw switch (e.kind()) {
        case RESYNC ->
            new FileAccessException.CheckpointExpired(
                "Microsoft Graph nimmt den Fortsetzungspunkt der Auflistung nicht mehr an.");
        case UNAUTHORIZED -> new FileAccessException.CredentialsRejected(e.getMessage());
        case NOT_FOUND, FORBIDDEN -> new FileAccessException.ContainerUnlistable(e.getMessage());
        // a page or folder Graph refuses for good starts the library over, bounded by the core
        case TRANSIENT ->
            durable(e.status())
                ? new FileAccessException.CheckpointExpired(e.getMessage())
                : new FileAccessException.RunEnding(e.getMessage() + LISTING_RESUMES_SUFFIX);
        default -> new FileAccessException.RunEnding(e.getMessage() + LISTING_RESUMES_SUFFIX);
      };
    }
  }

  // --- single file and download ----------------------------------------------------------------

  @Override
  public FileEntry head(FileContainer container, String id)
      throws FileAccessException, InterruptedException {
    SharePointLibrary library = library(container);
    if (!libraryNames.containsKey(container.key())) {
      requireReachable(container);
    }
    try {
      DriveItem item = holderOrItem(library, id);
      if (item == null || item.holdsItems()) {
        throw new FileAccessException.Gone("Die Datei existiert in SharePoint nicht mehr.");
      }
      Chain chain = chain(library, item.parentId());
      if (chain == null) {
        throw new FileAccessException.Gone("Die Datei liegt nicht mehr in der Bibliothek.");
      }
      return entry(
          library,
          item,
          chain,
          chain.within(library) ? null : new Exclusion.Deselected(OUTSIDE_FOLDERS_NOTE));
    } catch (GraphException e) {
      throw fileFailure(e);
    }
  }

  @Override
  public FetchedFile fetch(FileEntry entry, long maxBytes)
      throws FileAccessException, InterruptedException {
    SharePointLibrary library = library(entry.container());
    Path file;
    try {
      file = graph.download(itemPath(library, entry.id()) + "/content", maxBytes);
    } catch (GraphException e) {
      throw fileFailure(e);
    }
    try {
      return new FetchedFile(file, Files.size(file), null);
    } catch (IOException e) {
      deleteQuietly(file);
      throw new FileAccessException.Transient("Die geladene Datei ist nicht lesbar.");
    }
  }

  /**
   * A failed read of one file. Graph answers every non-2xx of the download host, and a 4xx without
   * a kind of its own, as transient; a 4xx other than a throttle or a timeout is durable, so one
   * broken file neither holds a stream's cursor nor keeps a round open.
   */
  static FileAccessException fileFailure(GraphException e) {
    return switch (e.kind()) {
      case UNAUTHORIZED -> new FileAccessException.CredentialsRejected(e.getMessage());
      case BLOCKED -> new FileAccessException.RunEnding(e.getMessage());
      case NOT_FOUND -> new FileAccessException.Gone(e.getMessage());
      case FORBIDDEN -> new FileAccessException.Unreadable(e.getMessage());
      case TOO_LARGE -> new FileAccessException.TooLarge(e.getMessage());
      case RESYNC -> new FileAccessException.Transient(e.getMessage());
      case TRANSIENT ->
          durable(e.status())
              ? new FileAccessException.Unavailable(e.getMessage() + REFUSED_DOWNLOAD_SUFFIX)
              : new FileAccessException.Transient(e.getMessage());
    };
  }

  private static boolean durable(int status) {
    return status >= 400 && status < 500 && status != 408 && status != 429;
  }

  // --- change log ------------------------------------------------------------------------------

  @Override
  public Optional<ChangeFeed> changes() {
    return Optional.of(this);
  }

  @Override
  public String feedKey(FileContainer container) {
    return library(container).key();
  }

  @Override
  public String startCursor(String feedKey) throws FileAccessException, InterruptedException {
    SharePointLibrary library = library(new FileContainer(feedKey));
    try {
      String cursor = graph.page(deltaPath(library), Map.of(), "latest").deltaToken();
      if (cursor == null) {
        throw new FileAccessException.RunEnding(
            "Microsoft Graph hat keinen Startpunkt des Änderungsprotokolls geliefert.");
      }
      return cursor;
    } catch (GraphException e) {
      if (e.kind() == GraphException.Kind.NOT_FOUND || e.kind() == GraphException.Kind.FORBIDDEN) {
        // the listing names the library unreachable; the run goes on without a finding there
        log.info("SharePoint library {} refused its start cursor", library.driveId());
        return UNREACHABLE_AT_START;
      }
      throw e.kind() == GraphException.Kind.UNAUTHORIZED
          ? new FileAccessException.CredentialsRejected(e.getMessage())
          : new FileAccessException.RunEnding(
              "Dokumentbibliothek „" + displayName(library) + "“: " + e.getMessage());
    }
  }

  /**
   * One page of the library's changes. A file inside the folder filter is an update, a deleted item
   * or one no longer below the filter or the library a removal; a folder only renews the chain. A
   * repeated item is reported each time - the last report is its state.
   */
  @Override
  public ChangePage read(String feedKey, String cursor)
      throws FileAccessException, InterruptedException {
    SharePointLibrary library = library(new FileContainer(feedKey));
    if (UNREACHABLE_AT_START.equals(cursor)) {
      throw new FileAccessException.CursorExpired(
          "Die Dokumentbibliothek war beim Beginn des letzten Vollabgleichs nicht erreichbar.");
    }
    try {
      GraphPage page = graph.page(deltaPath(library), query(), cursor);
      List<Change> changes = new ArrayList<>();
      for (JsonNode node : page.value()) {
        DriveItem item = DriveItem.of(node);
        if (item.id() == null) {
          continue;
        }
        String filePath = filePath(library.driveId(), item.id());
        if (item.deleted()) {
          holders.remove(library.driveId() + "/" + item.id());
          changes.add(new Change.Removed(filePath));
          continue;
        }
        remember(library, item);
        if (item.holdsItems()) {
          continue;
        }
        Chain chain = chain(library, item.parentId());
        changes.add(
            chain == null || !chain.within(library)
                ? new Change.Removed(filePath)
                : new Change.Updated(entry(library, item, chain, null)));
      }
      if (page.nextToken() != null) {
        return new ChangePage(changes, page.nextToken(), null, false);
      }
      if (page.deltaToken() == null) {
        throw new FileAccessException.Transient(
            "Microsoft Graph hat das Änderungsprotokoll ohne neuen Startpunkt beendet.");
      }
      return new ChangePage(changes, null, page.deltaToken(), false);
    } catch (GraphException e) {
      throw switch (e.kind()) {
        case RESYNC ->
            new FileAccessException.CursorExpired(
                "Microsoft Graph nimmt den gespeicherten Stand des Änderungsprotokolls nicht mehr"
                    + " an.");
        case UNAUTHORIZED -> new FileAccessException.CredentialsRejected(e.getMessage());
        case BLOCKED -> new FileAccessException.RunEnding(e.getMessage());
        default -> new FileAccessException.Transient(e.getMessage());
      };
    }
  }

  /**
   * The library is a document library the application sees, and each folder of its filter still
   * exists in it - a filter folder gone would otherwise turn every file into a removal.
   */
  @Override
  public void requireReachable(FileContainer container)
      throws FileAccessException, InterruptedException {
    SharePointLibrary library = library(container);
    try {
      JsonNode drive =
          graph.get("drives/" + library.driveId(), Map.of("$select", "id,name,driveType"));
      if (!"documentLibrary".equals(text(drive, "driveType"))) {
        throw new FileAccessException.ContainerUnlistable(NOT_A_LIBRARY);
      }
      String name = text(drive, "name");
      libraryNames.put(
          container.key(),
          name != null && !name.isBlank()
              ? name
              : library.name() != null ? library.name() : container.key());
      for (String folder : library.folders()) {
        DriveItem item = holderOrItem(library, folder);
        if (item == null || !item.folder()) {
          throw new FileAccessException.ContainerUnlistable(
              "Der gewählte Ordner „"
                  + library.folderNames().getOrDefault(folder, folder)
                  + "“ ist in der Dokumentbibliothek nicht mehr vorhanden; bitte die Ordnerauswahl"
                  + " anpassen.");
        }
      }
    } catch (GraphException e) {
      throw switch (e.kind()) {
        case UNAUTHORIZED -> new FileAccessException.CredentialsRejected(e.getMessage());
        case BLOCKED -> new FileAccessException.RunEnding(e.getMessage());
        case NOT_FOUND, FORBIDDEN ->
            new FileAccessException.ContainerUnlistable(LIBRARY_NOT_VISIBLE);
        default -> new FileAccessException.ContainerUnlistable(e.getMessage());
      };
    }
  }

  @Override
  public SourceRequestMeter meter() {
    return meter;
  }

  @Override
  public void close() {}

  // --- folder chain ----------------------------------------------------------------------------

  /**
   * The folders from below the library's root down to an item's parent.
   *
   * @param ids every folder and package on the way, the root included
   * @param inPackage whether one of them is a package (a OneNote notebook)
   */
  private record Chain(List<String> names, Set<String> ids, boolean inPackage) {

    boolean within(SharePointLibrary library) {
      return library.folders().isEmpty() || library.folders().stream().anyMatch(ids::contains);
    }

    String hierarchyPath() {
      return names.isEmpty() ? null : String.join(SourceDocumentContext.HIERARCHY_SEPARATOR, names);
    }
  }

  /**
   * The chain above {@code parentId}, {@code null} when it does not reach the library's root - a
   * parent deleted or the walk too deep. A parent Graph refuses ({@code 403}) fails the walk.
   */
  private Chain chain(SharePointLibrary library, String parentId)
      throws GraphException, InterruptedException {
    List<String> names = new ArrayList<>();
    Set<String> ids = new HashSet<>();
    boolean inPackage = false;
    String current = parentId;
    for (int depth = 0; current != null && depth < MAX_CHAIN; depth++) {
      DriveItem holder = holderOrItem(library, current);
      if (holder == null || !ids.add(holder.id())) {
        return null;
      }
      if (holder.root()) {
        Collections.reverse(names);
        return new Chain(List.copyOf(names), Set.copyOf(ids), inPackage);
      }
      inPackage |= holder.packageType() != null;
      names.add(holder.name() == null || holder.name().isBlank() ? holder.id() : holder.name());
      current = holder.parentId();
    }
    return null;
  }

  /** Keeps a folder, package or root for the chains of this run. */
  private void remember(SharePointLibrary library, DriveItem item) {
    if (item.holdsItems() || item.packageType() != null) {
      holders.put(library.driveId() + "/" + item.id(), item);
    }
  }

  /**
   * The item {@code id} of the library: a folder from this run's chains, else read once; {@code
   * null} when it is deleted or unknown ({@code 404}); every other failure is thrown.
   */
  private DriveItem holderOrItem(SharePointLibrary library, String id)
      throws GraphException, InterruptedException {
    DriveItem known = holders.get(library.driveId() + "/" + id);
    if (known != null) {
      return known;
    }
    DriveItem item;
    try {
      item = DriveItem.of(graph.get(itemPath(library, id), Map.of("$select", DriveItem.SELECT)));
    } catch (GraphException e) {
      // within a visible library a 403 means access lost, not absence: it fails the caller
      if (e.kind() == GraphException.Kind.NOT_FOUND) {
        return null;
      }
      throw e;
    }
    if (item.deleted() || item.id() == null) {
      return null;
    }
    remember(library, item);
    return item;
  }

  // --- entries ---------------------------------------------------------------------------------

  private FileEntry entry(
      SharePointLibrary library, DriveItem item, Chain chain, Exclusion exclusion) {
    List<String> segments = new ArrayList<>();
    if (settings.libraries().size() > 1) {
      segments.add(displayName(library));
    }
    segments.addAll(chain.names());
    String name = item.name() == null || item.name().isBlank() ? item.id() : item.name();
    String marker = null;
    if (exclusion == null) {
      if (item.packageType() != null || chain.inPackage()) {
        exclusion = new Exclusion.NotADocument(ONENOTE_NOTE);
      } else {
        marker = marker(item);
        if (item.malware()) {
          exclusion = new Exclusion.Unavailable(MALWARE);
        }
      }
    }
    return new FileEntry(
        library.container(),
        item.id(),
        filePath(library.driveId(), item.id()),
        name,
        SourceFolderPath.capped(segments),
        new SourceDocumentContext(library.key(), chain.hierarchyPath()),
        item.size(),
        marker,
        item.mimeType(),
        exclusion);
  }

  /**
   * Content hash, else {@code cTag}, else the change time - each with the size and the item's place
   * (name and parent), so a renamed or moved file is fetched once and its title and path follow.
   */
  static String marker(DriveItem item) {
    String place = digest(item.name() + "\u0000" + item.parentId()).substring(0, 12);
    String tail = "|" + item.size() + "|" + place;
    if (item.quickXorHash() != null) {
      return "x:" + compact(item.quickXorHash()) + tail;
    }
    if (item.cTag() != null) {
      return "c:" + compact(item.cTag()) + tail;
    }
    return "t:" + compact(String.valueOf(item.lastModified())) + tail;
  }

  private static String compact(String part) {
    return part.length() <= MAX_MARKER_PART ? part : digest(part).substring(0, 24);
  }

  private static String digest(String text) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is always available", e);
    }
  }

  // --- helpers ---------------------------------------------------------------------------------

  private String displayName(SharePointLibrary library) {
    String name = libraryNames.get(library.key());
    if (name != null) {
      return name;
    }
    return library.name() != null ? library.name() : library.key();
  }

  private Map<String, String> query() {
    Map<String, String> query = new LinkedHashMap<>();
    query.put("$top", Integer.toString(pageSize));
    query.put("$select", DriveItem.SELECT);
    return query;
  }

  private static String deltaPath(SharePointLibrary library) {
    return "drives/" + library.driveId() + "/root/delta";
  }

  private static String itemPath(SharePointLibrary library, String id) {
    return "drives/" + library.driveId() + "/items/" + id;
  }

  private SharePointLibrary library(FileContainer container) {
    SharePointLibrary library = librariesByKey.get(container.key());
    if (library == null) {
      throw new IllegalArgumentException("no library " + container.key());
    }
    return library;
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node == null ? null : node.get(field);
    return value == null || value.isNull() ? null : value.asString();
  }

  private static void deleteQuietly(Path file) {
    try {
      Files.deleteIfExists(file);
    } catch (IOException e) {
      log.warn("Failed to delete temp file {}", file);
    }
  }
}
