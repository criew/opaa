package io.opaa.indexing.source.googledrive;

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
import io.opaa.sourceaccess.SourceRequestMeter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import tools.jackson.databind.JsonNode;

/**
 * The {@link FileStore} and {@link ChangeFeed} of one Google Drive library for one run (ADR-0040,
 * Entscheidungen 5 to 9). A shared drive is listed flat after its folders, a folder level by level.
 * Every shared drive has its own change stream, every other scope shares the account's ({@value
 * #USER_STREAM}). Shortcuts and Google types without export are no documents; a file whose download
 * is locked is unavailable.
 */
final class DriveFileStore implements FileStore, ChangeFeed {

  private static final org.slf4j.Logger log =
      org.slf4j.LoggerFactory.getLogger(DriveFileStore.class);

  static final String USER_STREAM = "user";
  static final String OPEN_PREFIX = "https://drive.google.com/open?id=";

  static final String SHORTCUTS_NOTE =
      " Verknüpfungen übersprungen; ihr Ziel wird nur indexiert, wenn es selbst in einem"
          + " Geltungsbereich liegt";
  static final String NO_EXPORT_NOTE =
      " Google-Dateien ohne Exportformat (etwa Zeichnungen, Formulare, Websites) übersprungen";
  static final String DOWNLOAD_LOCKED =
      "Der Download dieser Datei ist in Google Drive gesperrt; sie wird übersprungen.";
  static final String EXPORTED_AS_TEXT =
      "Als Text exportiert: Die Office-Fassung überschreitet die Exportgrenze von Google Drive"
          + " (10 MB); Überschriften und Tabellen fehlen.";
  static final String SHEET_TOO_LARGE =
      "Tabelle übersprungen: Der Export überschreitet die Exportgrenze von Google Drive (10 MB),"
          + " und CSV enthielte nur das erste Blatt.";

  private static final String LIST_FIELDS = "nextPageToken,files(" + DriveFile.FIELDS + ")";
  private static final String CHANGE_FIELDS =
      "nextPageToken,newStartPageToken,changes(changeType,removed,fileId,file("
          + DriveFile.FIELDS
          + "))";

  private final DriveApi api;
  private final List<GoogleDriveScope> scopes;
  private final Map<String, GoogleDriveScope> scopesByKey = new LinkedHashMap<>();
  private final int pageSize;
  private final Map<String, String> containerNames = new HashMap<>();
  private final Map<String, Listing> listings = new HashMap<>();
  private final Map<String, DriveFile> folders = new ConcurrentHashMap<>();
  private final Map<String, GoogleFormats.Export> exports = new ConcurrentHashMap<>();
  private final java.util.Set<String> listedIds = ConcurrentHashMap.newKeySet();
  private String myDriveRoot;

  DriveFileStore(DriveApi api, GoogleDriveSettings settings, int pageSize) {
    this.api = api;
    this.scopes = settings.scopes();
    this.pageSize = pageSize;
    for (GoogleDriveScope scope : scopes) {
      scopesByKey.put(scope.key(), scope);
    }
  }

  @Override
  public List<FileContainer> containers() {
    return scopes.stream().map(GoogleDriveScope::container).toList();
  }

  // --- listing ---------------------------------------------------------------------------------

  @Override
  public FilePage list(FileContainer container, String continuation)
      throws FileAccessException, InterruptedException {
    GoogleDriveScope scope = scope(container);
    try {
      if (continuation == null) {
        requireReachable(container);
        listings.put(container.key(), new Listing(scope));
      }
      Listing listing = listings.get(container.key());
      if (listing == null) {
        throw new FileAccessException.ContainerUnlistable("Die Auflistung ist abgebrochen.");
      }
      return listing.nextPage();
    } catch (DriveApiException e) {
      throw switch (e.kind()) {
        case UNAUTHORIZED, SCOPE_MISSING, DAILY_LIMIT, BLOCKED ->
            new FileAccessException.RunEnding(e.getMessage());
        default -> new FileAccessException.ContainerUnlistable(e.getMessage());
      };
    }
  }

  /** The listing state of one container within the run. */
  private final class Listing {
    private final GoogleDriveScope scope;
    private final Deque<Level> levels = new ArrayDeque<>();
    private boolean foldersLoaded;
    private String pageToken;
    private int pages;

    private record Level(String folderId, List<String> segments) {}

    Listing(GoogleDriveScope scope) {
      this.scope = scope;
      if (scope.kind() != GoogleDriveScope.Kind.DRIVE) {
        levels.add(new Level(scope.id(), List.of()));
      }
    }

    FilePage nextPage() throws DriveApiException, InterruptedException {
      return scope.kind() == GoogleDriveScope.Kind.DRIVE ? drivePage() : folderPage();
    }

    private FilePage drivePage() throws DriveApiException, InterruptedException {
      if (!foldersLoaded) {
        loadDriveFolders();
        foldersLoaded = true;
      }
      Map<String, String> query = driveQuery();
      query.put("q", "trashed = false and mimeType != '" + DriveFile.FOLDER + "'");
      if (pageToken != null) {
        query.put("pageToken", pageToken);
      }
      JsonNode answer = api.get("files", query);
      List<FileEntry> entries = new ArrayList<>();
      for (DriveFile file : files(answer)) {
        if (firstListing(file)) {
          entries.add(entry(scope, file, placed(file, chain(file, scope.id()))));
        }
      }
      pageToken = text(answer, "nextPageToken");
      return new FilePage(entries, pageToken == null ? null : continuation());
    }

    private void loadDriveFolders() throws DriveApiException, InterruptedException {
      String token = null;
      do {
        Map<String, String> query = driveQuery();
        query.put("q", "trashed = false and mimeType = '" + DriveFile.FOLDER + "'");
        if (token != null) {
          query.put("pageToken", token);
        }
        JsonNode answer = api.get("files", query);
        for (DriveFile folder : files(answer)) {
          folders.put(folder.id(), folder);
        }
        token = text(answer, "nextPageToken");
      } while (token != null);
    }

    private Map<String, String> driveQuery() {
      Map<String, String> query = new LinkedHashMap<>();
      query.put("corpora", "drive");
      query.put("driveId", scope.id());
      query.put("includeItemsFromAllDrives", "true");
      query.put("supportsAllDrives", "true");
      query.put("pageSize", Integer.toString(pageSize));
      query.put("fields", LIST_FIELDS);
      return query;
    }

    private FilePage folderPage() throws DriveApiException, InterruptedException {
      Level level = levels.peek();
      if (level == null) {
        return new FilePage(List.of(), null);
      }
      Map<String, String> query = new LinkedHashMap<>();
      query.put("q", "'" + level.folderId() + "' in parents and trashed = false");
      query.put("corpora", scope.kind() == GoogleDriveScope.Kind.MY_DRIVE ? "user" : "allDrives");
      query.put("includeItemsFromAllDrives", "true");
      query.put("supportsAllDrives", "true");
      query.put("pageSize", Integer.toString(pageSize));
      query.put("fields", LIST_FIELDS);
      if (pageToken != null) {
        query.put("pageToken", pageToken);
      }
      JsonNode answer = api.get("files", query);
      List<FileEntry> entries = new ArrayList<>();
      for (DriveFile file : files(answer)) {
        if (file.isFolder()) {
          folders.put(file.id(), file);
          List<String> segments = new ArrayList<>(level.segments());
          segments.add(file.name());
          levels.add(new Level(file.id(), List.copyOf(segments)));
        } else if (firstListing(file)) {
          entries.add(entry(scope, file, level.segments()));
        }
      }
      pageToken = text(answer, "nextPageToken");
      if (pageToken == null) {
        levels.poll();
      }
      return new FilePage(entries, levels.isEmpty() ? null : continuation());
    }

    private String continuation() {
      return Integer.toString(++pages);
    }
  }

  // --- single file and download ----------------------------------------------------------------

  @Override
  public FileEntry head(FileContainer container, String id)
      throws FileAccessException, InterruptedException {
    GoogleDriveScope scope = scope(container);
    DriveFile file;
    try {
      file = getFile(id);
    } catch (DriveApiException e) {
      throw fileFailure(e);
    }
    if (file.trashed()) {
      throw new FileAccessException.Gone("Die Datei liegt im Papierkorb von Google Drive.");
    }
    try {
      List<String> segments = chain(file, rootOf(scope));
      return entry(scope, file, segments == null ? List.of() : segments);
    } catch (DriveApiException e) {
      throw fileFailure(e);
    }
  }

  @Override
  public FetchedFile fetch(FileEntry entry, long maxBytes)
      throws FileAccessException, InterruptedException {
    GoogleFormats.Export export = exports.get(entry.id());
    try {
      if (export == null) {
        Path file =
            api.download(
                "files/" + entry.id(),
                Map.of("alt", "media", "supportsAllDrives", "true"),
                maxBytes);
        return new FetchedFile(file, Files.size(file), null);
      }
      try {
        Path file = exportFile(entry.id(), export, maxBytes);
        return new FetchedFile(file, Files.size(file), null);
      } catch (DriveApiException e) {
        if (e.kind() != DriveApiException.Kind.EXPORT_LIMIT) {
          throw e;
        }
        if (!export.textFallback()) {
          throw new FileAccessException.TooLarge(SHEET_TOO_LARGE);
        }
        Path file = exportFile(entry.id(), GoogleFormats.TEXT, maxBytes);
        return new FetchedFile(
            file,
            Files.size(file),
            null,
            withoutExtension(entry.fileName()) + "." + GoogleFormats.TEXT.extension(),
            EXPORTED_AS_TEXT);
      }
    } catch (DriveApiException e) {
      throw fileFailure(e);
    } catch (java.io.IOException e) {
      throw new FileAccessException.Transient("Die geladene Datei ist nicht lesbar.");
    }
  }

  private Path exportFile(String id, GoogleFormats.Export export, long maxBytes)
      throws DriveApiException, InterruptedException {
    return api.download(
        "files/" + id + "/export", Map.of("mimeType", export.mediaType()), maxBytes);
  }

  // --- change log ------------------------------------------------------------------------------

  @Override
  public Optional<ChangeFeed> changes() {
    return Optional.of(this);
  }

  @Override
  public String feedKey(FileContainer container) {
    GoogleDriveScope scope = scope(container);
    return scope.kind() == GoogleDriveScope.Kind.DRIVE ? scope.key() : USER_STREAM;
  }

  @Override
  public String startCursor(String feedKey) throws FileAccessException, InterruptedException {
    Map<String, String> query = new LinkedHashMap<>();
    query.put("supportsAllDrives", "true");
    driveIdOf(feedKey).ifPresent(driveId -> query.put("driveId", driveId));
    try {
      String cursor = text(api.get("changes/startPageToken", query), "startPageToken");
      if (cursor == null) {
        throw new FileAccessException.RunEnding(
            "Google Drive hat keinen Startpunkt des Änderungsprotokolls geliefert.");
      }
      return cursor;
    } catch (DriveApiException e) {
      throw new FileAccessException.RunEnding(e.getMessage());
    }
  }

  @Override
  public ChangePage read(String feedKey, String cursor)
      throws FileAccessException, InterruptedException {
    Map<String, String> query = new LinkedHashMap<>();
    query.put("pageToken", cursor);
    query.put("pageSize", Integer.toString(pageSize));
    query.put("supportsAllDrives", "true");
    query.put("includeItemsFromAllDrives", "true");
    query.put("fields", CHANGE_FIELDS);
    driveIdOf(feedKey).ifPresent(driveId -> query.put("driveId", driveId));
    JsonNode answer;
    try {
      answer = api.get("changes", query);
    } catch (DriveApiException e) {
      if (e.status() == 410 || (e.status() == 400 && "invalid".equals(e.reason()))) {
        throw new FileAccessException.CursorExpired(
            "Google Drive nimmt den gespeicherten Stand des Änderungsprotokolls nicht mehr an.");
      }
      throw switch (e.kind()) {
        case UNAUTHORIZED, SCOPE_MISSING, DAILY_LIMIT, BLOCKED ->
            new FileAccessException.RunEnding(e.getMessage());
        default -> new FileAccessException.Transient(e.getMessage());
      };
    }
    List<Change> changes = new ArrayList<>();
    boolean structure = false;
    JsonNode items = answer.get("changes");
    try {
      if (items != null && items.isArray()) {
        for (JsonNode item : items) {
          if (!"file".equals(text(item, "changeType")) && item.get("fileId") == null) {
            continue;
          }
          String fileId = text(item, "fileId");
          JsonNode fileNode = item.get("file");
          boolean removed = item.get("removed") != null && item.get("removed").asBoolean();
          DriveFile file;
          if (removed || fileNode == null || fileNode.isNull()) {
            // gone from this stream - deleted, out of reach, or moved into another scope
            file = stillPresent(fileId);
            if (file == null) {
              changes.add(new Change.Removed(OPEN_PREFIX + fileId));
              continue;
            }
          } else {
            file = DriveFile.of(fileNode);
          }
          if (file.isFolder()) {
            structure = true;
            folders.remove(file.id());
            continue;
          }
          if (file.trashed()) {
            changes.add(new Change.Removed(OPEN_PREFIX + file.id()));
            continue;
          }
          Change located = locate(feedKey, file);
          if (located != null) {
            changes.add(located);
          }
        }
      }
    } catch (DriveApiException e) {
      throw switch (e.kind()) {
        case UNAUTHORIZED, SCOPE_MISSING, DAILY_LIMIT, BLOCKED ->
            new FileAccessException.RunEnding(e.getMessage());
        default -> new FileAccessException.Transient(e.getMessage());
      };
    }
    String next = text(answer, "nextPageToken");
    if (next != null) {
      return new ChangePage(changes, next, null, structure);
    }
    String newStart = text(answer, "newStartPageToken");
    if (newStart == null) {
      throw new FileAccessException.Transient(
          "Google Drive hat das Änderungsprotokoll ohne neuen Startpunkt beendet.");
    }
    return new ChangePage(changes, null, newStart, structure);
  }

  /**
   * The change {@code feedKey}'s stream reports for {@code file}, judged against every scope of the
   * library (ADR-0040, Entscheidung 6): an update when the first scope holding it is one of this
   * stream's, nothing when it belongs to another stream's scope - that stream reports it -, and a
   * removal when no scope holds it any more.
   */
  private Change locate(String feedKey, DriveFile file)
      throws DriveApiException, InterruptedException {
    for (GoogleDriveScope scope : scopes) {
      if (scope.kind() == GoogleDriveScope.Kind.DRIVE && !scope.id().equals(file.driveId())) {
        continue;
      }
      List<String> segments = chain(file, rootOf(scope));
      if (segments != null) {
        return feedKey(scope.container()).equals(feedKey)
            ? new Change.Updated(entry(scope, file, segments))
            : null;
      }
    }
    return new Change.Removed(OPEN_PREFIX + file.id());
  }

  /** The file a stream reported removed, if it still exists and is not trashed. */
  private DriveFile stillPresent(String fileId) throws DriveApiException, InterruptedException {
    if (fileId == null) {
      return null;
    }
    try {
      DriveFile file = getFile(fileId);
      return file.trashed() || file.isFolder() ? null : file;
    } catch (DriveApiException e) {
      if (e.kind() == DriveApiException.Kind.NOT_FOUND
          || e.kind() == DriveApiException.Kind.FORBIDDEN) {
        return null;
      }
      throw e;
    }
  }

  @Override
  public void requireReachable(FileContainer container)
      throws FileAccessException, InterruptedException {
    GoogleDriveScope scope = scope(container);
    try {
      String name =
          switch (scope.kind()) {
            case DRIVE ->
                text(api.get("drives/" + scope.id(), Map.of("fields", "id,name")), "name");
            case FOLDER -> {
              DriveFile folder = getFile(scope.id());
              if (!folder.isFolder() || folder.trashed()) {
                throw new FileAccessException.ContainerUnlistable(
                    "Der Bereich ist kein Ordner oder liegt im Papierkorb.");
              }
              yield folder.name();
            }
            case MY_DRIVE -> {
              myDriveRoot = getFile("root").id();
              yield "Meine Ablage";
            }
          };
      containerNames.put(container.key(), name == null ? container.key() : name);
    } catch (DriveApiException e) {
      throw switch (e.kind()) {
        case UNAUTHORIZED, SCOPE_MISSING, DAILY_LIMIT, BLOCKED ->
            new FileAccessException.RunEnding(e.getMessage());
        case NOT_FOUND, FORBIDDEN ->
            new FileAccessException.ContainerUnlistable(
                scope.kind() == GoogleDriveScope.Kind.DRIVE
                    ? "Die geteilte Ablage ist für das Konto nicht sichtbar."
                    : "Der Ordner ist für das Konto nicht sichtbar.");
        default -> new FileAccessException.ContainerUnlistable(e.getMessage());
      };
    }
  }

  @Override
  public SourceRequestMeter meter() {
    return api.budget().meter();
  }

  @Override
  public void close() {}

  // --- helpers ---------------------------------------------------------------------------------

  /**
   * The folder names from below {@code rootId} down to {@code file}'s parent, {@code null} when the
   * chain does not reach {@code rootId}. Unknown folders are asked once and kept for the run.
   */
  private List<String> chain(DriveFile file, String rootId)
      throws DriveApiException, InterruptedException {
    List<String> names = new ArrayList<>();
    String parent = file.parent();
    for (int depth = 0; parent != null && depth < 200; depth++) {
      if (parent.equals(rootId)) {
        Collections.reverse(names);
        return names;
      }
      if (parent.equals(file.driveId())) {
        return null;
      }
      DriveFile folder = folders.get(parent);
      if (folder == null) {
        try {
          folder = getFile(parent);
        } catch (DriveApiException e) {
          if (e.kind() == DriveApiException.Kind.NOT_FOUND
              || e.kind() == DriveApiException.Kind.FORBIDDEN) {
            return null;
          }
          throw e;
        }
        folders.put(parent, folder);
      }
      names.add(folder.name());
      parent = folder.parent();
    }
    return null;
  }

  private String rootOf(GoogleDriveScope scope) throws DriveApiException, InterruptedException {
    if (scope.kind() != GoogleDriveScope.Kind.MY_DRIVE) {
      return scope.id();
    }
    if (myDriveRoot == null) {
      myDriveRoot = getFile("root").id();
    }
    return myDriveRoot;
  }

  private DriveFile getFile(String id) throws DriveApiException, InterruptedException {
    return DriveFile.of(
        api.get("files/" + id, Map.of("fields", DriveFile.FIELDS, "supportsAllDrives", "true")));
  }

  private FileEntry entry(GoogleDriveScope scope, DriveFile file, List<String> known) {
    List<String> chain = known == null ? List.of() : known;
    List<String> segments = new ArrayList<>();
    if (scopes.size() > 1) {
      segments.add(containerNames.getOrDefault(scope.key(), scope.key()));
    }
    segments.addAll(chain);
    String name = file.name() == null || file.name().isBlank() ? file.id() : file.name();
    String fileName = name;
    long size = file.size();
    String marker;
    String mediaType = file.mimeType();
    Exclusion exclusion = null;
    if (DriveFile.SHORTCUT.equals(file.mimeType())) {
      exclusion = new Exclusion.NotADocument(SHORTCUTS_NOTE);
      marker = null;
    } else if (GoogleFormats.isGoogleFile(file.mimeType())) {
      GoogleFormats.Export export = GoogleFormats.exportOf(file.mimeType());
      size = -1;
      if (export == null) {
        exclusion = new Exclusion.NotADocument(NO_EXPORT_NOTE);
        marker = null;
      } else {
        exports.put(file.id(), export);
        fileName = GoogleFormats.withExtension(name, export.extension());
        mediaType = export.mediaType();
        marker = "g:" + millis(file) + "|" + export.extension();
      }
    } else {
      marker =
          file.md5() != null
              ? "m:" + file.md5() + "|" + file.size()
              : "t:" + millis(file) + "|" + file.size();
    }
    if (exclusion == null && !file.canDownload()) {
      exclusion = new Exclusion.Unavailable(DOWNLOAD_LOCKED);
    }
    return new FileEntry(
        scope.container(),
        file.id(),
        OPEN_PREFIX + file.id(),
        fileName,
        SourceFolderPath.capped(segments),
        new SourceDocumentContext(
            scope.key(),
            chain.isEmpty() ? null : String.join(SourceDocumentContext.HIERARCHY_SEPARATOR, chain)),
        size,
        marker,
        mediaType,
        exclusion);
  }

  /**
   * Whether this run meets {@code file} for the first time: a file in two overlapping scopes keeps
   * the folder of the first scope that lists it (ADR-0040, Entscheidung 5).
   */
  private boolean firstListing(DriveFile file) {
    return listedIds.add(file.id());
  }

  /** The chain of a listed file, its scope's root when a parent folder cannot be read. */
  private static List<String> placed(DriveFile file, List<String> chain) {
    if (chain == null) {
      log.warn(
          "Folder chain of Drive file {} cannot be read - placing it at its scope's root",
          file.id());
      return List.of();
    }
    return chain;
  }

  private static long millis(DriveFile file) {
    return file.modifiedTime() == null ? 0 : file.modifiedTime().toEpochMilli();
  }

  private GoogleDriveScope scope(FileContainer container) {
    GoogleDriveScope scope = scopesByKey.get(container.key());
    if (scope == null) {
      throw new IllegalArgumentException("no scope " + container.key());
    }
    return scope;
  }

  private Optional<String> driveIdOf(String feedKey) {
    GoogleDriveScope scope = scopesByKey.get(feedKey);
    return scope != null && scope.kind() == GoogleDriveScope.Kind.DRIVE
        ? Optional.of(scope.id())
        : Optional.empty();
  }

  private static FileAccessException fileFailure(DriveApiException e) {
    return switch (e.kind()) {
      case UNAUTHORIZED, SCOPE_MISSING, DAILY_LIMIT, BLOCKED ->
          new FileAccessException.RunEnding(e.getMessage());
      case TOO_LARGE -> new FileAccessException.TooLarge(e.getMessage());
      case NOT_FOUND, FORBIDDEN, EXPORT_LIMIT -> new FileAccessException.Unreadable(e.getMessage());
      case TRANSIENT -> new FileAccessException.Transient(e.getMessage());
    };
  }

  private static List<DriveFile> files(JsonNode answer) {
    List<DriveFile> files = new ArrayList<>();
    JsonNode items = answer.get("files");
    if (items != null && items.isArray()) {
      items.forEach(item -> files.add(DriveFile.of(item)));
    }
    return files;
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node == null ? null : node.get(field);
    return value == null || value.isNull() ? null : value.asString();
  }

  private static String withoutExtension(String name) {
    int dot = name.lastIndexOf('.');
    return dot > 0 ? name.substring(0, dot) : name;
  }
}
