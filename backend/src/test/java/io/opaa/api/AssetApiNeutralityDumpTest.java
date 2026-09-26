package io.opaa.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import io.opaa.api.types.DocumentSourceType;
import io.opaa.api.types.DocumentStatus;
import io.opaa.auth.DevAuthFilter;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.group.Group;
import io.opaa.group.GroupRepository;
import io.opaa.indexing.chunk.ChunkingService;
import io.opaa.indexing.chunk.VectorChunkStore;
import io.opaa.knowledge.DocumentRepository;
import io.opaa.llm.ActiveChatModelResolver;
import io.opaa.permission.GroupMembershipResolver;
import io.opaa.space.SpaceRepository;
import io.opaa.test.OpaaMockedChatModelIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.node.StringNode;

/**
 * Verhaltensneutralitaets-Harness of the asset shell (#1899/#1900): builds one fixed data set
 * through the HTTP API, then records every answer of the library list, the library detail, the
 * grant list, the Herleitung, the space associations and the search - normalized so two runs on
 * different code can be diffed line by line. Ids become stable labels, instants become {@code
 * <ts>}, the problem detail's {@code instance} path is dropped.
 *
 * <p>Runs only when {@code OPAA_NEUTRALITY_DUMP} names the output file; it asserts nothing, the
 * diff of two dumps is the result. {@link #path} is the one place a renamed endpoint is mapped.
 */
@OpaaMockedChatModelIntegrationTest
@EnabledIfEnvironmentVariable(named = "OPAA_NEUTRALITY_DUMP", matches = ".+")
class AssetApiNeutralityDumpTest {

  private static final UUID DEFAULT_ORGANIZATION_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final String USER = "dev-user";
  private static final String ADMIN = "dev-admin";
  private static final String LIBRARY_ASSET = "/api/v1/assets/KNOWLEDGE_LIBRARY/";
  private static final Pattern UUID_PATTERN =
      Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
  private static final Pattern INSTANT_PATTERN =
      Pattern.compile("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?(Z|[+-]\\d{2}:\\d{2})$");

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private UserRepository users;
  @Autowired private GroupRepository groups;
  @Autowired private GroupMembershipResolver membershipResolver;
  @Autowired private SpaceRepository spaces;
  @Autowired private DocumentRepository documents;
  @Autowired private VectorStore vectorStore;
  @Autowired private VectorChunkStore vectorChunkStore;
  @Autowired private ChatModel chatModel;
  @Autowired private ActiveChatModelResolver activeChatModelResolver;

  private final JsonMapper json = JsonMapper.builder().build();
  private final Map<String, String> labels = new LinkedHashMap<>();
  private final Map<String, JsonNode> dump = new TreeMap<>();
  private final List<UUID> libraryIds = new ArrayList<>();
  private UUID spaceId;
  private UUID groupId;
  private UUID thirdUserId;

  /** The endpoints of this refactoring, by logical name - the only place a path is spelled. */
  private static String path(String kind, UUID libraryId, UUID other) {
    return switch (kind) {
      case "grants" -> LIBRARY_ASSET + libraryId + "/grants";
      case "grant" -> LIBRARY_ASSET + libraryId + "/grants/" + other;
      case "grantedGroupMembers" ->
          LIBRARY_ASSET + libraryId + "/grants/groups/" + other + "/members";
      case "derivation" -> LIBRARY_ASSET + libraryId + "/access-derivation";
      case "librarySpaces" -> LIBRARY_ASSET + libraryId + "/spaces";
      case "spaceAssets" -> "/api/v1/spaces/" + other + "/assets";
      case "spaceAsset" -> "/api/v1/spaces/" + other + "/assets/" + libraryId;
      default -> throw new IllegalArgumentException(kind);
    };
  }

  /** The association request body - the one other place the refactoring renames a field. */
  private static String associationBody(UUID libraryId) {
    return "{\"assetType\":\"KNOWLEDGE_LIBRARY\",\"assetId\":\"" + libraryId + "\"}";
  }

  @Test
  void dumpNormalizedApiAnswers() throws Exception {
    scriptChatModel();
    UUID userId = provision(USER, "dev-user@opaa.local");
    UUID adminId = provision(ADMIN, "admin@opaa.local");
    labels.put(userId.toString(), "USER");
    labels.put(adminId.toString(), "ADMIN");
    labels.put(DEFAULT_ORGANIZATION_ID.toString(), "ORG");

    User third =
        new User("neutral-" + UUID.randomUUID(), "neutral-issuer", "dritte@example.com", "Dritte");
    third.setOrganizationId(DEFAULT_ORGANIZATION_ID);
    thirdUserId = users.save(third).getId();
    labels.put(thirdUserId.toString(), "THIRD");

    groupId =
        groups
            .save(Group.internal(DEFAULT_ORGANIZATION_ID, "Neutral Referat", "Gruppe", null))
            .getId();
    labels.put(groupId.toString(), "GROUP");
    addMember(groupId, userId);
    addMember(groupId, thirdUserId);

    UUID l1 = createLibrary(USER, "N1 Privat", "PRIVATE", null);
    UUID l2 = createLibrary(ADMIN, "N2 Organisation", "ORGANIZATION", null);
    UUID l3 = createLibrary(ADMIN, "N3 Gruppe", "SHARED", null);
    UUID l4 = createLibrary(ADMIN, "N4 Direkt", "PRIVATE", null);
    UUID l5 = createLibrary(ADMIN, "N5 Verborgen", "PRIVATE", null);
    UUID l6 = createLibrary(USER, "N6 Gruppeneigen", "PRIVATE", groupId);

    record(
        "mutation.grant.l3.group",
        send(ADMIN, post(path("grants", l3, null)), grantBody("GROUP", groupId, "VIEWER", null)));
    record(
        "mutation.grant.l4.user",
        send(ADMIN, post(path("grants", l4, null)), grantBody("USER", userId, "EDITOR", null)));
    JsonNode thirdGrant =
        record(
            "mutation.grant.l1.third",
            send(
                USER,
                post(path("grants", l1, null)),
                grantBody("USER", thirdUserId, "VIEWER", "2099-01-01T00:00:00Z")));
    record(
        "mutation.grant.l1.third.raise",
        send(USER, post(path("grants", l1, null)), grantBody("USER", thirdUserId, "EDITOR", null)));
    record(
        "mutation.grant.l4.escalation",
        send(USER, post(path("grants", l4, null)), grantBody("USER", thirdUserId, "OWNER", null)));
    record(
        "mutation.update.l1",
        send(
            USER,
            put("/api/v1/libraries/" + l1),
            "{\"name\":\"N1 Privat\",\"description\":\"geteilt\",\"visibility\":\"SHARED\","
                + "\"listed\":true}"));

    for (UUID library : List.of(l1, l2, l3, l4, l5)) {
      insertDocument(library, labels.get(library.toString()) + ".md", "Widerspruchsfrist Monat");
    }

    JsonNode space =
        record(
            "mutation.space.create",
            send(USER, post("/api/v1/spaces"), "{\"name\":\"Neutral Space\"}"));
    spaceId = UUID.fromString(space.path("body").path("id").asString());
    labels.put(spaceId.toString(), "SPACE");
    for (UUID library : List.of(l1, l2, l3, l4)) {
      record(
          "mutation.associate." + labels.get(library.toString()),
          send(USER, post(path("spaceAssets", null, spaceId)), associationBody(library)));
    }
    record(
        "mutation.associate.admin.N5",
        send(ADMIN, post(path("spaceAssets", null, spaceId)), associationBody(l5)));
    record(
        "mutation.associate.user.N5",
        send(USER, post(path("spaceAssets", null, spaceId)), associationBody(l5)));

    for (String caller : List.of(USER, ADMIN)) {
      record("list." + caller, send(caller, get("/api/v1/libraries"), null));
      record("searchLibraries." + caller, send(caller, get("/api/v1/search/libraries"), null));
      record(
          "search." + caller,
          send(
              caller,
              post("/api/v1/search"),
              "{\"question\":\"Welche Frist gilt für den Widerspruch?\"}"));
      record("spaceAssets." + caller, send(caller, get(path("spaceAssets", null, spaceId)), null));
      for (UUID library : libraryIds) {
        String name = labels.get(library.toString());
        record(
            "detail." + name + "." + caller,
            send(caller, get("/api/v1/libraries/" + library), null));
        record(
            "grants." + name + "." + caller,
            send(caller, get(path("grants", library, null)), null));
        record(
            "derivation." + name + "." + caller,
            send(caller, get(path("derivation", library, null)), null));
        record(
            "librarySpaces." + name + "." + caller,
            send(caller, get(path("librarySpaces", library, null)), null));
      }
    }
    record(
        "grantedGroupMembers.N3.ADMIN",
        send(ADMIN, get(path("grantedGroupMembers", l3, groupId)), null));
    record(
        "grantedGroupMembers.N3.USER",
        send(USER, get(path("grantedGroupMembers", l3, groupId)), null));

    record("mutation.detach.N4", send(USER, delete(path("spaceAsset", l4, spaceId)), null));
    record(
        "spaceAssets.afterDetach." + USER,
        send(USER, get(path("spaceAssets", null, spaceId)), null));
    UUID thirdGrantId = UUID.fromString(thirdGrant.path("body").path("id").asString());
    record("mutation.revoke.l1.third", send(USER, delete(path("grant", l1, thirdGrantId)), null));
    record("grants.afterRevoke.N1." + USER, send(USER, get(path("grants", l1, null)), null));
    record("mutation.delete.N6", send(USER, delete("/api/v1/libraries/" + l6), null));
    libraryIds.remove(l6);
    record("list.afterDelete." + USER, send(USER, get("/api/v1/libraries"), null));

    Path out = Path.of(System.getenv("OPAA_NEUTRALITY_DUMP"));
    Files.createDirectories(out.toAbsolutePath().getParent());
    Files.writeString(
        out,
        json.writer(SerializationFeature.INDENT_OUTPUT).writeValueAsString(normalized()),
        StandardCharsets.UTF_8);
  }

  @AfterEach
  void tearDown() {
    for (UUID library : libraryIds) {
      vectorChunkStore.deleteByLibraryId(library);
    }
    if (spaceId != null) {
      jdbc.update("DELETE FROM space_asset_associations WHERE space_id = ?", spaceId);
      jdbc.update("DELETE FROM chats WHERE space_id = ?", spaceId);
      jdbc.update("DELETE FROM space_membership_history WHERE space_id = ?", spaceId);
      jdbc.update("DELETE FROM asset_ownership_history WHERE asset_id = ?", spaceId);
      spaces.deleteById(spaceId);
    }
    for (UUID library : libraryIds) {
      jdbc.update("DELETE FROM documents WHERE library_id = ?", library);
      jdbc.update("DELETE FROM assets WHERE id = ?", library);
    }
    List<UUID> all = new ArrayList<>(libraryIds);
    for (UUID library : all) {
      jdbc.update("DELETE FROM asset_grants WHERE asset_id = ?", library);
      jdbc.update("DELETE FROM asset_grant_history WHERE asset_id = ?", library);
      jdbc.update("DELETE FROM asset_ownership_history WHERE asset_id = ?", library);
      jdbc.update("DELETE FROM asset_visibility_history WHERE asset_id = ?", library);
    }
    if (thirdUserId != null) {
      jdbc.update(
          "DELETE FROM asset_grant_history WHERE subject_user_id = ? OR actor_user_id = ?",
          thirdUserId,
          thirdUserId);
    }
    if (groupId != null) {
      jdbc.update("DELETE FROM asset_grant_history WHERE subject_group_id = ?", groupId);
      jdbc.update("DELETE FROM asset_ownership_history WHERE owner_group_id = ?", groupId);
      jdbc.update("DELETE FROM group_membership_history WHERE group_id = ?", groupId);
      jdbc.update("DELETE FROM group_memberships WHERE group_id = ?", groupId);
      groups.deleteById(groupId);
    }
    if (thirdUserId != null) {
      membershipResolver.invalidateUser(thirdUserId);
      users.deleteById(thirdUserId);
    }
  }

  private void scriptChatModel() {
    when(chatModel.getOptions()).thenReturn(ChatOptions.builder().build());
    when(activeChatModelResolver.resolveChatClient())
        .thenReturn(ChatClient.builder(chatModel).build());
    when(chatModel.call(any(Prompt.class)))
        .thenReturn(
            new ChatResponse(
                List.of(new Generation(new AssistantMessage("Welche Frist gilt?"))),
                ChatResponseMetadata.builder().model("test-model").build()));
  }

  private UUID provision(String subject, String email) throws Exception {
    mockMvc.perform(get("/api/v1/auth/me").header(DevAuthFilter.DEV_USER_HEADER, subject));
    return users.findAll().stream()
        .filter(user -> email.equals(user.getEmail()))
        .map(User::getId)
        .findFirst()
        .orElseThrow();
  }

  private void addMember(UUID group, UUID user) {
    jdbc.update(
        "INSERT INTO group_memberships (id, user_id, group_id, organization_id, created_at)"
            + " VALUES (?, ?, ?, ?, now())",
        UUID.randomUUID(),
        user,
        group,
        DEFAULT_ORGANIZATION_ID);
    membershipResolver.invalidateUser(user);
  }

  private UUID createLibrary(String caller, String name, String visibility, UUID ownerGroup)
      throws Exception {
    String owner =
        ownerGroup == null ? "" : ",\"ownerType\":\"GROUP\",\"ownerId\":\"" + ownerGroup + "\"";
    JsonNode created =
        record(
            "mutation.create." + name.substring(0, 2),
            send(
                caller,
                post("/api/v1/libraries"),
                "{\"name\":\""
                    + name
                    + "\",\"visibility\":\""
                    + visibility
                    + "\",\"sourceType\":\"UPLOAD\""
                    + owner
                    + "}"));
    UUID id = UUID.fromString(created.path("body").path("id").asString());
    labels.put(id.toString(), name.substring(0, 2));
    libraryIds.add(id);
    return id;
  }

  private static String grantBody(String type, UUID subject, String role, String expiresAt) {
    return "{\"subjectType\":\""
        + type
        + "\",\"subjectId\":\""
        + subject
        + "\",\"role\":\""
        + role
        + "\""
        + (expiresAt == null ? "" : ",\"expiresAt\":\"" + expiresAt + "\"")
        + "}";
  }

  private void insertDocument(UUID library, String fileName, String text) {
    io.opaa.knowledge.Document document =
        new io.opaa.knowledge.Document(
            fileName, "/" + fileName, "text/markdown", 100L, DocumentSourceType.UPLOAD);
    document.setLibraryId(library);
    document.setOrganizationId(DEFAULT_ORGANIZATION_ID);
    document.setStatus(DocumentStatus.INDEXED);
    document.setChunkCount(1);
    document.setIndexedAt(Instant.now());
    documents.save(document);
    labels.put(document.getId().toString(), "DOC-" + fileName);
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("document_id", document.getId().toString());
    metadata.put("library_id", library.toString());
    metadata.put("file_name", fileName);
    metadata.put("chunk_index", 0);
    metadata.put(ChunkingService.LOCATION_METADATA_KEY, "Abschn. " + fileName);
    vectorStore.add(List.of(new Document(text + " in " + fileName, metadata)));
  }

  private JsonNode send(String caller, MockHttpServletRequestBuilder request, String body)
      throws Exception {
    request.header(DevAuthFilter.DEV_USER_HEADER, caller);
    if (body != null) {
      request.contentType(MediaType.APPLICATION_JSON).content(body);
    }
    MockHttpServletResponse response = mockMvc.perform(request).andReturn().getResponse();
    ObjectNode node = json.createObjectNode();
    node.put("status", response.getStatus());
    String content = response.getContentAsString(StandardCharsets.UTF_8);
    node.set("body", content.isBlank() ? json.nullNode() : json.readTree(content));
    return node;
  }

  private JsonNode record(String key, JsonNode answer) {
    dump.put(key, answer);
    return answer;
  }

  private JsonNode normalized() {
    ObjectNode root = json.createObjectNode();
    Map<String, String> anonymous = new LinkedHashMap<>();
    dump.forEach((key, value) -> root.set(key, normalize(value, anonymous)));
    return root;
  }

  private JsonNode normalize(JsonNode node, Map<String, String> anonymous) {
    if (node.isObject()) {
      ObjectNode copy = json.createObjectNode();
      new TreeMap<>(asMap(node))
          .forEach(
              (field, value) -> {
                if (!field.equals("instance")) {
                  copy.set(field, normalize(value, anonymous));
                }
              });
      return copy;
    }
    if (node.isArray()) {
      ArrayNode copy = json.createArrayNode();
      node.forEach(element -> copy.add(normalize(element, anonymous)));
      return copy;
    }
    if (node.isString()) {
      String text = node.asString();
      if (INSTANT_PATTERN.matcher(text).matches() && !text.startsWith("2099")) {
        return StringNode.valueOf("<ts>");
      }
      Matcher matcher = UUID_PATTERN.matcher(text);
      StringBuilder replaced = new StringBuilder();
      while (matcher.find()) {
        String id = matcher.group();
        String label =
            labels.containsKey(id)
                ? labels.get(id)
                : anonymous.computeIfAbsent(id, unknown -> "<id" + anonymous.size() + ">");
        matcher.appendReplacement(replaced, Matcher.quoteReplacement(label));
      }
      matcher.appendTail(replaced);
      return StringNode.valueOf(replaced.toString());
    }
    return node;
  }

  private static Map<String, JsonNode> asMap(JsonNode node) {
    Map<String, JsonNode> fields = new LinkedHashMap<>();
    node.properties().forEach(entry -> fields.put(entry.getKey(), entry.getValue()));
    return fields;
  }
}
