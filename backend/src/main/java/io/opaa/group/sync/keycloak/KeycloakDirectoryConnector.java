package io.opaa.group.sync.keycloak;

import io.opaa.group.sync.DirectoryAccount;
import io.opaa.group.sync.DirectoryGroup;
import io.opaa.group.sync.DirectorySnapshot;
import io.opaa.group.sync.DirectoryUnavailableException;
import java.net.http.HttpClient;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

/**
 * The first productive directory connector (#1817, ADR-0036 Entscheidung 3): the Keycloak Admin
 * REST API read as a {@link DirectorySnapshot}.
 *
 * <p><b>Why Keycloak first.</b> A Keycloak user's id <em>is</em> the {@code sub} of the token it
 * issues, so {@link DirectoryGroup#memberSubjects()} needs no mapping rule at all and the mismatch
 * ADR-0025 warns about - an account of one provider receiving the memberships of a same-named
 * subject of another - cannot arise here structurally. LDAP follows with an explicit mapping rule.
 *
 * <p><b>Direct members, never transitive.</b> {@code /groups/{id}/members} reports exactly the
 * users attached to that group, not those of its subgroups, which is also how OPAA's own model
 * reads membership ("Mitgliedschaft vererbt nicht"). A Keycloak department that only holds
 * subgroups therefore becomes an <em>empty</em> group in OPAA; the diff report names every group's
 * member count so an operator sees that before applying.
 *
 * <p><b>The hierarchy is walked, not asked for in one go.</b> Since Keycloak 23 {@code /groups}
 * returns top-level groups with an empty {@code subGroups} array and a {@code subGroupCount}; the
 * children come from {@code /groups/{id}/children}. Only groups that report children are asked for
 * them.
 *
 * <p><b>The account status comes from the same read</b> (#1818): {@code /users} reports every
 * account of the realm with its {@code enabled} flag, and the id it carries is the same {@code sub}
 * a membership names - so the account status needs no mapping rule either.
 *
 * <p><b>A truncated read is a failure, not a smaller directory.</b> Every ceiling of {@link
 * KeycloakDirectoryProperties} raises {@link DirectoryUnavailableException} instead of returning
 * what was read so far: a truncated group list is indistinguishable from a reorganisation that
 * dissolved everything beyond it, a truncated account list from a mass departure, and the run would
 * apply the latter.
 */
public class KeycloakDirectoryConnector {

  private static final Logger log = LoggerFactory.getLogger(KeycloakDirectoryConnector.class);

  private final HttpClient httpClient;
  private final KeycloakDirectoryProperties properties;
  private final Clock clock;

  public KeycloakDirectoryConnector(
      HttpClient httpClient, KeycloakDirectoryProperties properties, Clock clock) {
    this.httpClient = httpClient;
    this.properties = properties;
    this.clock = clock;
  }

  /**
   * German, user-facing outcome of one probe - the shape {@code OidcProviderConnectionTester} has.
   */
  public record ProbeOutcome(boolean success, String message) {}

  /**
   * Signs in with the service account and reads the realm's complete group tree with its direct
   * memberships, plus every account with its {@code enabled} flag (#1818).
   */
  public DirectorySnapshot fetchSnapshot(
      KeycloakRealmAddress address, String clientId, String clientSecret)
      throws DirectoryUnavailableException {
    KeycloakAdminApi api = api(address, clientId, clientSecret);
    List<DirectoryGroup> groups = new ArrayList<>();
    Deque<String> pending = new ArrayDeque<>();
    for (JsonNode node :
        pages(
            first -> api.topLevelGroups(first, properties.pageSize()),
            properties.maxGroups(),
            groupCeilingMessage())) {
      collect(api, node, null, groups, pending);
    }
    while (!pending.isEmpty()) {
      String parentId = pending.removeFirst();
      for (JsonNode node :
          pages(
              first -> api.childGroups(parentId, first, properties.pageSize()),
              properties.maxGroups(),
              groupCeilingMessage())) {
        collect(api, node, parentId, groups, pending);
      }
    }
    List<DirectoryAccount> accounts = accounts(api);
    log.info(
        "Keycloak directory: read {} group(s) and {} account(s) of realm {} at {}",
        groups.size(),
        accounts.size(),
        address.realm(),
        address.baseUrl());
    return new DirectorySnapshot(clock.instant(), groups, accounts);
  }

  /**
   * The realm's accounts with their state (#1818). A user without an id is skipped the way a group
   * without one is - it identifies nobody; {@code enabled} absent is read as enabled, so a Keycloak
   * version that omits the field for an enabled account never locks anyone.
   */
  private List<DirectoryAccount> accounts(KeycloakAdminApi api)
      throws DirectoryUnavailableException {
    List<DirectoryAccount> accounts = new ArrayList<>();
    for (JsonNode node :
        pages(
            first -> api.users(first, properties.pageSize()),
            properties.maxAccounts(),
            accountCeilingMessage())) {
      String id = node.path("id").asString(null);
      if (id == null || id.isBlank()) {
        log.warn("Keycloak directory: skipping an account the directory reported without id");
        continue;
      }
      accounts.add(new DirectoryAccount(id, node.path("enabled").asBoolean(true)));
    }
    return accounts;
  }

  /**
   * Probes the access without writing anything: the sign-in must succeed and the service account
   * must be allowed to count the realm's groups - the smallest call that needs the very rights a
   * run needs.
   */
  public ProbeOutcome probe(KeycloakRealmAddress address, String clientId, String clientSecret) {
    try {
      int count = api(address, clientId, clientSecret).groupCount();
      return new ProbeOutcome(
          true,
          "Verzeichnis erreichbar: Realm „"
              + address.realm()
              + "“ mit "
              + count
              + (count == 1 ? " Gruppe." : " Gruppen."));
    } catch (DirectoryUnavailableException e) {
      return new ProbeOutcome(false, e.getMessage());
    }
  }

  private KeycloakAdminApi api(KeycloakRealmAddress address, String clientId, String clientSecret) {
    return new KeycloakAdminApi(
        httpClient, address, clientId, clientSecret, properties.requestTimeout());
  }

  private void collect(
      KeycloakAdminApi api,
      JsonNode node,
      String parentExternalId,
      List<DirectoryGroup> groups,
      Deque<String> pending)
      throws DirectoryUnavailableException {
    String id = node.path("id").asString(null);
    String name = node.path("name").asString(null);
    if (id == null || id.isBlank() || name == null || name.isBlank()) {
      log.warn("Keycloak directory: skipping a group the directory reported without id or name");
      return;
    }
    if (groups.size() >= properties.maxGroups()) {
      throw new DirectoryUnavailableException(groupCeilingMessage());
    }
    String path = node.path("path").asString(null);
    groups.add(
        new DirectoryGroup(
            id,
            name,
            parentExternalId,
            path == null || path.isBlank() ? null : path,
            memberSubjects(api, id)));
    // Absent subGroupCount means "this version does not tell" - then ask, rather than silently
    // dropping a subtree.
    if (!node.has("subGroupCount") || node.path("subGroupCount").asInt(0) > 0) {
      pending.addLast(id);
    }
  }

  private Set<String> memberSubjects(KeycloakAdminApi api, String groupId)
      throws DirectoryUnavailableException {
    Set<String> subjects = new LinkedHashSet<>();
    for (JsonNode member :
        pages(
            first -> api.members(groupId, first, properties.pageSize()),
            properties.maxMembersPerGroup(),
            memberCeilingMessage())) {
      String subject = member.path("id").asString(null);
      if (subject != null && !subject.isBlank()) {
        subjects.add(subject);
      }
    }
    return subjects;
  }

  private String groupCeilingMessage() {
    return "Das Verzeichnis meldet mehr als "
        + properties.maxGroups()
        + " Gruppen. Der Lauf bricht ab, statt eine abgeschnittene Liste anzuwenden.";
  }

  private String accountCeilingMessage() {
    return "Das Verzeichnis meldet mehr als "
        + properties.maxAccounts()
        + " Konten. Der Lauf bricht ab, statt eine abgeschnittene Kontenliste anzuwenden.";
  }

  private String memberCeilingMessage() {
    return "Eine Verzeichnisgruppe meldet mehr als "
        + properties.maxMembersPerGroup()
        + " direkte Mitglieder. Der Lauf bricht ab, statt eine abgeschnittene Mitgliederliste"
        + " anzuwenden.";
  }

  /**
   * Walks {@code first}/{@code max} until a page comes back shorter than the page size - Keycloak
   * reports no total for these endpoints, so "a short page is the last page" is the only stop
   * condition it offers - and refuses to accumulate more than {@code ceiling} entries, so a runaway
   * source cannot be read into memory before the ceiling is noticed.
   */
  private List<JsonNode> pages(Page page, int ceiling, String ceilingMessage)
      throws DirectoryUnavailableException {
    List<JsonNode> all = new ArrayList<>();
    int first = 0;
    while (true) {
      List<JsonNode> batch = page.read(first);
      all.addAll(batch);
      if (all.size() > ceiling) {
        throw new DirectoryUnavailableException(ceilingMessage);
      }
      if (batch.size() < properties.pageSize()) {
        return all;
      }
      first += batch.size();
    }
  }

  @FunctionalInterface
  private interface Page {
    List<JsonNode> read(int first) throws DirectoryUnavailableException;
  }
}
