package io.opaa.indexing.source.dropbox;

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
import io.opaa.indexing.spi.SourceFolderPath;
import io.opaa.knowledge.SourceDocumentContext;
import io.opaa.sourceaccess.SourceRequestMeter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import tools.jackson.databind.JsonNode;

/**
 * The {@link FileStore} and {@link ChangeFeed} of one Dropbox library for one run. Every configured
 * folder - or, under a team app, every team folder - is a container, listed recursively with {@code
 * list_folder}; its change stream is the cursor of the same listing. A file's identity is its
 * lower-case path, behind the namespace for a team folder, so a rename or a move is a removal and a
 * new file. A reported deletion whose path was a folder is a change of structure that only a full
 * sync reconciles; Dropbox does not name the files below it. A Paper document is exported as
 * Markdown; any other file Dropbox does not hand out is no document.
 */
final class DropboxFileStore implements FileStore, ChangeFeed {

  /**
   * The prefix of a document identity, followed by the file's lower-case path; no address a reader
   * opens, since Dropbox documents no stable web address for a path.
   */
  static final String IDENTITY_PREFIX = "dropbox:";

  /** Follows {@link #IDENTITY_PREFIX} for a file of a team folder: {@code ns:<id>/<path>}. */
  static final String TEAM_FOLDER_PREFIX = "ns:";

  static final String NOT_DOWNLOADABLE_NOTE =
      " Dateien übersprungen, die Dropbox weder herausgibt noch exportiert";
  static final String ROOT_NAME = "Dropbox";

  private static final String MARKDOWN = "markdown";
  private static final String HTML = "html";

  /** Pages of {@code team/namespaces/list} a run reads at most to find its team folders. */
  private static final int MAX_NAMESPACE_PAGES = 100;

  /**
   * One container: its key, the folder it reads below its root ({@code /} for a team folder), the
   * name it heads the folder chain with, and the prefix of its files' identities.
   */
  private record Area(String key, String folder, String label, String identityPrefix) {}

  private final DropboxApi api;
  private final DropboxSettings settings;
  private final int pageSize;
  private final Map<String, Area> areas = new LinkedHashMap<>();
  private final Map<String, String> exports = new ConcurrentHashMap<>();

  /** Per team folder its API view as the admin, or why the run cannot read it. */
  private final Map<String, DropboxApi> teamViews = new ConcurrentHashMap<>();

  private final Map<String, String> unavailable = new ConcurrentHashMap<>();
  private boolean prepared;

  DropboxFileStore(DropboxApi api, DropboxSettings settings, int pageSize) {
    this.api = api;
    this.settings = settings;
    this.pageSize = pageSize;
    if (settings.readsTeamFolders()) {
      for (DropboxSettings.TeamFolder folder : settings.teamFolders()) {
        areas.put(
            folder.id(),
            new Area(
                folder.id(),
                "/",
                folder.label(),
                IDENTITY_PREFIX + TEAM_FOLDER_PREFIX + folder.id()));
      }
    } else {
      for (String folder : settings.folders()) {
        areas.put(folder, new Area(folder, folder, displayName(folder), IDENTITY_PREFIX));
      }
    }
  }

  @Override
  public List<FileContainer> containers() {
    return areas.keySet().stream().map(FileContainer::new).toList();
  }

  // --- listing ---------------------------------------------------------------------------------

  @Override
  public FilePage list(FileContainer container, String continuation)
      throws FileAccessException, InterruptedException {
    Area area = area(container.key());
    try {
      JsonNode answer;
      if (continuation == null) {
        requireReachable(container);
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("path", DropboxSettings.apiPath(area.folder()));
        args.put("recursive", true);
        args.put("include_deleted", false);
        args.put("include_non_downloadable_files", true);
        args.put("limit", pageSize);
        answer = apiOf(area).rpc("files/list_folder", args);
      } else {
        answer = apiOf(area).rpc("files/list_folder/continue", Map.of("cursor", continuation));
      }
      List<FileEntry> entries = new ArrayList<>();
      for (JsonNode item : items(answer)) {
        if ("file".equals(text(item, ".tag"))) {
          FileEntry entry = entry(area, item);
          if (entry != null) {
            entries.add(entry);
          }
        }
      }
      boolean more = item(answer, "has_more");
      return new FilePage(entries, more ? text(answer, "cursor") : null);
    } catch (DropboxApiException e) {
      throw switch (e.kind()) {
        case UNAUTHORIZED -> new FileAccessException.CredentialsRejected(e.getMessage());
        case SCOPE_MISSING -> new FileAccessException.PermissionMissing(e.getMessage());
        case BLOCKED -> new FileAccessException.RunEnding(e.getMessage());
        default -> new FileAccessException.ContainerUnlistable(e.getMessage());
      };
    }
  }

  // --- single file and download ----------------------------------------------------------------

  @Override
  public FileEntry head(FileContainer container, String id)
      throws FileAccessException, InterruptedException {
    Area area = area(container.key());
    try {
      JsonNode meta = apiOf(area).rpc("files/get_metadata", Map.of("path", id));
      if (!"file".equals(text(meta, ".tag"))) {
        throw new FileAccessException.Gone("Die Datei gibt es in Dropbox nicht mehr.");
      }
      FileEntry entry = entry(area, meta);
      if (entry == null) {
        throw new FileAccessException.Gone("Die Datei liegt nicht mehr im Ordner.");
      }
      return entry;
    } catch (DropboxApiException e) {
      if (e.kind() == DropboxApiException.Kind.NOT_FOUND) {
        throw new FileAccessException.Gone("Die Datei gibt es in Dropbox nicht mehr.");
      }
      throw fileFailure(e);
    }
  }

  @Override
  public FetchedFile fetch(FileEntry entry, long maxBytes)
      throws FileAccessException, InterruptedException {
    String format = exports.get(entry.id());
    try {
      DropboxApi reader = apiOf(area(entry.container().key()));
      Path file =
          format == null
              ? reader.download("files/download", Map.of("path", entry.id()), maxBytes)
              : reader.download(
                  "files/export", Map.of("path", entry.id(), "export_format", format), maxBytes);
      return new FetchedFile(file, Files.size(file), null);
    } catch (DropboxApiException e) {
      if (e.kind() == DropboxApiException.Kind.NOT_FOUND) {
        throw new FileAccessException.Gone("Die Datei gibt es in Dropbox nicht mehr.");
      }
      throw fileFailure(e);
    } catch (IOException e) {
      throw new FileAccessException.Transient("Die geladene Datei ist nicht lesbar.");
    }
  }

  // --- change log ------------------------------------------------------------------------------

  @Override
  public Optional<ChangeFeed> changes() {
    return Optional.of(this);
  }

  @Override
  public String feedKey(FileContainer container) {
    return area(container.key()).key();
  }

  @Override
  public String startCursor(String feedKey) throws FileAccessException, InterruptedException {
    Area area = area(feedKey);
    try {
      prepare();
      if (unavailable.containsKey(area.key())) {
        return UNREACHABLE_AT_START;
      }
      Map<String, Object> args = new LinkedHashMap<>();
      args.put("path", DropboxSettings.apiPath(area.folder()));
      args.put("recursive", true);
      args.put("include_deleted", true);
      args.put("include_non_downloadable_files", true);
      args.put("limit", pageSize);
      String cursor = text(apiOf(area).rpc("files/list_folder/get_latest_cursor", args), "cursor");
      if (cursor == null) {
        throw new FileAccessException.RunEnding(
            "Dropbox hat keinen Startpunkt des Änderungsprotokolls geliefert.");
      }
      return cursor;
    } catch (DropboxApiException e) {
      if (e.kind() == DropboxApiException.Kind.NOT_FOUND
          || e.kind() == DropboxApiException.Kind.CONFLICT
          || e.kind() == DropboxApiException.Kind.FORBIDDEN) {
        // the listing names the folder unreachable; the run goes on without a finding there
        return UNREACHABLE_AT_START;
      }
      throw e.kind() == DropboxApiException.Kind.UNAUTHORIZED
          ? new FileAccessException.CredentialsRejected(e.getMessage())
          : new FileAccessException.RunEnding(e.getMessage());
    }
  }

  @Override
  public ChangePage read(String feedKey, String cursor)
      throws FileAccessException, InterruptedException {
    Area area = area(feedKey);
    if (UNREACHABLE_AT_START.equals(cursor)) {
      throw new FileAccessException.CursorExpired(
          "Der Ordner „"
              + area.label()
              + "“ war beim Beginn des letzten Vollabgleichs nicht erreichbar.");
    }
    List<Change> changes = new ArrayList<>();
    boolean structure = false;
    JsonNode answer;
    try {
      DropboxApi reader = apiOf(area);
      answer = reader.rpc("files/list_folder/continue", Map.of("cursor", cursor));
      for (JsonNode item : items(answer)) {
        switch (text(item, ".tag") == null ? "" : text(item, ".tag")) {
          case "file" -> {
            FileEntry entry = entry(area, item);
            if (entry != null) {
              changes.add(new Change.Updated(entry));
            }
          }
          case "deleted" -> {
            String pathLower = text(item, "path_lower");
            if (pathLower == null) {
              structure = true;
              continue;
            }
            changes.add(new Change.Removed(area.identityPrefix() + pathLower));
            if (!structure && !wasFile(reader, pathLower)) {
              structure = true;
            }
          }
          default -> {
            // a folder that appeared or changed carries nothing to fetch itself
          }
        }
      }
    } catch (DropboxApiException e) {
      if (e.kind() == DropboxApiException.Kind.RESET) {
        throw new FileAccessException.CursorExpired(e.getMessage());
      }
      throw switch (e.kind()) {
        case UNAUTHORIZED -> new FileAccessException.CredentialsRejected(e.getMessage());
        case SCOPE_MISSING -> new FileAccessException.PermissionMissing(e.getMessage());
        case BLOCKED -> new FileAccessException.RunEnding(e.getMessage());
        default -> new FileAccessException.Transient(e.getMessage());
      };
    } catch (FileAccessException.ContainerUnlistable e) {
      // a team folder gone from the team since the last run: only a full sync can tell
      throw new FileAccessException.CursorExpired(e.getMessage());
    }
    String next = text(answer, "cursor");
    if (next == null) {
      throw new FileAccessException.Transient(
          "Dropbox hat das Änderungsprotokoll ohne neuen Stand beendet.");
    }
    return item(answer, "has_more")
        ? new ChangePage(changes, next, null, structure)
        : new ChangePage(changes, null, next, structure);
  }

  /**
   * Whether the deleted {@code pathLower} was a file: a file keeps its revisions after the
   * deletion, a folder has none. Unknown counts as a folder, so a full sync follows.
   */
  private static boolean wasFile(DropboxApi reader, String pathLower)
      throws DropboxApiException, InterruptedException {
    Map<String, Object> args = new LinkedHashMap<>();
    args.put("path", pathLower);
    args.put("mode", "path");
    args.put("limit", 1);
    try {
      reader.rpc("files/list_revisions", args);
      return true;
    } catch (DropboxApiException e) {
      if (e.kind() == DropboxApiException.Kind.NOT_FOUND
          || e.kind() == DropboxApiException.Kind.CONFLICT) {
        return false;
      }
      throw e;
    }
  }

  /** The identity is the path: a file met nowhere in a round over several runs is gone. */
  @Override
  public AbsenceProof absenceProof() {
    return AbsenceProof.LOCATION_IDENTITY;
  }

  @Override
  public void requireReachable(FileContainer container)
      throws FileAccessException, InterruptedException {
    Area area = area(container.key());
    try {
      DropboxApi reader = apiOf(area);
      if (settings.readsTeamFolders()) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("path", "");
        args.put("recursive", false);
        args.put("limit", 1);
        reader.rpc("files/list_folder", args);
        return;
      }
      if (area.folder().equals("/")) {
        reader.rpc("users/get_current_account", null);
        return;
      }
      JsonNode meta = reader.rpc("files/get_metadata", Map.of("path", area.folder()));
      if (!"folder".equals(text(meta, ".tag"))) {
        throw new FileAccessException.ContainerUnlistable(
            "„" + area.folder() + "“ ist kein Ordner.");
      }
    } catch (DropboxApiException e) {
      throw switch (e.kind()) {
        case UNAUTHORIZED -> new FileAccessException.CredentialsRejected(e.getMessage());
        case SCOPE_MISSING -> new FileAccessException.PermissionMissing(e.getMessage());
        case BLOCKED -> new FileAccessException.RunEnding(e.getMessage());
        case NOT_FOUND, FORBIDDEN, CONFLICT ->
            new FileAccessException.ContainerUnlistable(
                settings.readsTeamFolders()
                    ? "Der Team-Ordner „" + area.label() + "“ ist nicht lesbar."
                    : "Der Ordner „" + area.folder() + "“ ist für das Konto nicht sichtbar.");
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
   * The API that reads {@code area}: the account's, turned to the team space where asked, or the
   * admin's view of the team folder.
   *
   * @throws FileAccessException.ContainerUnlistable for a team folder the team no longer names
   */
  private DropboxApi apiOf(Area area)
      throws DropboxApiException, InterruptedException, FileAccessException {
    prepare();
    if (!settings.readsTeamFolders()) {
      return api;
    }
    DropboxApi view = teamViews.get(area.key());
    if (view == null) {
      throw new FileAccessException.ContainerUnlistable(unavailable.get(area.key()));
    }
    return view;
  }

  /**
   * Once per run: turns every call of a library in the team space to the team space's root, or,
   * under a team app, asks Dropbox for the consenting admin and which chosen namespaces are still
   * team folders. A namespace that is none - gone, or a member's folder - is never read.
   *
   * @throws DropboxApiException as the account or team lookup fails
   */
  private synchronized void prepare()
      throws DropboxApiException, InterruptedException, FileAccessException {
    if (prepared) {
      return;
    }
    if (settings.readsTeamFolders()) {
      String admin;
      DropboxTeam.TeamFolders listed;
      try {
        admin = text(DropboxTeam.admin(api), "team_member_id");
        listed =
            DropboxTeam.teamFolders(api, new LinkedHashSet<>(areas.keySet()), MAX_NAMESPACE_PAGES);
      } catch (DropboxApiException e) {
        throw switch (e.kind()) {
          case UNAUTHORIZED -> new FileAccessException.CredentialsRejected(e.getMessage());
          case SCOPE_MISSING -> new FileAccessException.PermissionMissing(e.getMessage());
          default -> new FileAccessException.RunEnding(e.getMessage());
        };
      }
      for (Area area : areas.values()) {
        boolean teamFolder =
            listed.folders().stream().anyMatch(folder -> folder.id().equals(area.key()));
        if (teamFolder) {
          teamViews.put(area.key(), api.asAdminIn(area.key(), admin));
        } else {
          unavailable.put(
              area.key(),
              "„"
                  + area.label()
                  + "“ ist "
                  + (listed.complete() ? "kein Team-Ordner des Teams." : "nicht auffindbar.")
                  + " OPAA liest nur Team-Ordner.");
        }
      }
    } else if (settings.teamSpace()) {
      String namespace = teamSpaceOf(api.rpc("users/get_current_account", null));
      if (namespace == null) {
        throw new FileAccessException.RunEnding(
            "Das verbundene Dropbox-Konto gehört zu keinem Team mit Team-Bereich.");
      }
      api.rootAt(namespace);
    }
    prepared = true;
  }

  /** The root namespace of {@code account}'s team space, {@code null} without one. */
  static String teamSpaceOf(JsonNode account) {
    JsonNode info = account == null ? null : account.get("root_info");
    if (info == null || !"team".equals(text(info, ".tag"))) {
      return null;
    }
    String root = text(info, "root_namespace_id");
    String home = text(info, "home_namespace_id");
    return root == null || root.equals(home) ? null : root;
  }

  /**
   * The entry of the file {@code meta} below {@code area}'s folder, {@code null} when it lies
   * outside - the folder chain mirrors the folders below it, behind the area's name when the
   * library reads several.
   */
  private FileEntry entry(Area area, JsonNode meta) {
    String folder = area.folder();
    String id = text(meta, "id");
    String pathLower = text(meta, "path_lower");
    String pathDisplay = text(meta, "path_display");
    if (id == null || pathLower == null) {
      return null;
    }
    if (pathDisplay == null || pathDisplay.length() != pathLower.length()) {
      pathDisplay = pathLower;
    }
    String folderLower = DropboxSettings.lower(folder);
    String below;
    if (folder.equals("/")) {
      below = pathDisplay;
    } else if (pathLower.startsWith(folderLower + "/")) {
      below = pathDisplay.substring(folder.length());
    } else {
      return null;
    }
    List<String> parts = Arrays.stream(below.split("/")).filter(part -> !part.isEmpty()).toList();
    if (parts.isEmpty()) {
      return null;
    }
    List<String> chain = parts.subList(0, parts.size() - 1);
    String name = text(meta, "name");
    if (name == null || name.isBlank()) {
      name = parts.getLast();
    }
    List<String> segments = new ArrayList<>();
    if (areas.size() > 1) {
      segments.add(area.label());
    }
    segments.addAll(chain);
    String fileName = name;
    long size = meta.get("size") == null ? -1 : meta.get("size").asLong();
    String hash = text(meta, "content_hash");
    String marker = hash != null ? "h:" + hash : "r:" + text(meta, "rev");
    Exclusion exclusion = null;
    JsonNode downloadable = meta.get("is_downloadable");
    if (downloadable != null && !downloadable.asBoolean()) {
      String format = exportFormat(meta);
      if (format == null) {
        exclusion = new Exclusion.NotADocument(NOT_DOWNLOADABLE_NOTE);
      } else {
        exports.put(id, format);
        fileName = withoutExtension(name) + (format.equals(MARKDOWN) ? ".md" : ".html");
        size = -1;
        marker = "e:" + text(meta, "rev") + "|" + format;
      }
    }
    return new FileEntry(
        new FileContainer(area.key()),
        id,
        area.identityPrefix() + pathLower,
        fileName,
        SourceFolderPath.capped(segments),
        new SourceDocumentContext(
            area.key(),
            chain.isEmpty() ? null : String.join(SourceDocumentContext.HIERARCHY_SEPARATOR, chain)),
        size,
        marker,
        null,
        exclusion);
  }

  /** The format a file Dropbox does not hand out is exported in, {@code null} for none. */
  private static String exportFormat(JsonNode meta) {
    JsonNode info = meta.get("export_info");
    if (info == null || info.isNull()) {
      return null;
    }
    JsonNode options = info.get("export_options");
    List<String> offered = new ArrayList<>();
    if (options != null && options.isArray()) {
      options.forEach(option -> offered.add(option.asString()));
    }
    String exportAs = text(info, "export_as");
    if (offered.contains(MARKDOWN) || "md".equals(exportAs) || MARKDOWN.equals(exportAs)) {
      return MARKDOWN;
    }
    if (offered.contains(HTML) || HTML.equals(exportAs)) {
      return HTML;
    }
    return null;
  }

  /** The name a folder is shown under at the top of the chain. */
  static String displayName(String folder) {
    if (folder.equals("/")) {
      return ROOT_NAME;
    }
    return folder.substring(folder.lastIndexOf('/') + 1);
  }

  private Area area(String key) {
    Area area = areas.get(key);
    if (area == null) {
      throw new IllegalArgumentException("no folder " + key);
    }
    return area;
  }

  private static FileAccessException fileFailure(DropboxApiException e) {
    return switch (e.kind()) {
      case UNAUTHORIZED -> new FileAccessException.CredentialsRejected(e.getMessage());
      case SCOPE_MISSING -> new FileAccessException.PermissionMissing(e.getMessage());
      case BLOCKED -> new FileAccessException.RunEnding(e.getMessage());
      case TOO_LARGE -> new FileAccessException.TooLarge(e.getMessage());
      case NOT_FOUND, FORBIDDEN, CONFLICT, BAD_REQUEST, RESET ->
          new FileAccessException.Unreadable(e.getMessage());
      case TRANSIENT -> new FileAccessException.Transient(e.getMessage());
    };
  }

  private static List<JsonNode> items(JsonNode answer) {
    List<JsonNode> items = new ArrayList<>();
    JsonNode entries = answer == null ? null : answer.get("entries");
    if (entries != null && entries.isArray()) {
      entries.forEach(items::add);
    }
    return items;
  }

  private static boolean item(JsonNode node, String field) {
    JsonNode value = node == null ? null : node.get(field);
    return value != null && value.asBoolean();
  }

  static String text(JsonNode node, String field) {
    JsonNode value = node == null ? null : node.get(field);
    return value == null || value.isNull() ? null : value.asString();
  }

  private static String withoutExtension(String name) {
    int dot = name.lastIndexOf('.');
    return dot > 0 ? name.substring(0, dot) : name;
  }
}
