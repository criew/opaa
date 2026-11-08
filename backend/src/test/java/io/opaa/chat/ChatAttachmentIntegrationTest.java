package io.opaa.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.opaa.auth.DevAuthFilter;
import io.opaa.common.ValidationException;
import io.opaa.test.OpaaIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

/**
 * A chat attachment from upload to deletion: read on its own pool into text, offered to the
 * question it was sent with, bound to the chat with that turn and gone with the chat; an unbound
 * one is removed by the cleanup after its retention.
 */
@OpaaIntegrationTest
class ChatAttachmentIntegrationTest {

  private static final String TEXT = "Die Kündigungsfrist beträgt drei Monate zum Quartalsende.";

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private ChatAttachmentService attachmentService;
  @Autowired private ChatAttachmentCleanup cleanup;
  @Autowired private ChatRepository chatRepository;
  @Autowired private ChatService chatService;
  @Autowired private ChatAttachmentProperties properties;

  private final List<UUID> spaces = new ArrayList<>();
  private UUID devUser;
  private UUID organization;
  private UUID space;

  @BeforeEach
  void aSpaceWithTheDevUserAsMember() throws Exception {
    mockMvc.perform(as("dev-user", get("/api/v1/spaces"))).andExpect(status().isOk());
    mockMvc.perform(as("dev-admin", get("/api/v1/spaces"))).andExpect(status().isOk());
    Map<String, Object> row =
        jdbc.queryForMap("SELECT id, organization_id FROM users WHERE subject = 'dev-user'");
    devUser = (UUID) row.get("id");
    organization = (UUID) row.get("organization_id");
    UUID admin =
        jdbc.queryForObject("SELECT id FROM users WHERE subject = 'dev-admin'", UUID.class);
    String created =
        mockMvc
            .perform(
                as("dev-admin", post("/api/v1/spaces"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {"name": "Anhänge %s", "ownerId": "%s", "initialMembers": [
                          {"subjectType": "USER", "subjectId": "%s", "role": "MEMBER"}]}
                        """
                            .formatted(UUID.randomUUID(), admin, devUser)))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    space = UUID.fromString(JsonPath.read(created, "$.id"));
    spaces.add(space);
  }

  @AfterEach
  void removeOwnRows() throws Exception {
    for (UUID own : spaces) {
      for (UUID attachment :
          jdbc.queryForList(
              "SELECT id FROM chat_attachments WHERE space_id = ?", UUID.class, own)) {
        mockMvc.perform(as("dev-user", delete("/api/v1/chat-attachments/" + attachment)));
      }
      jdbc.update("DELETE FROM chat_attachments WHERE space_id = ?", own);
      jdbc.update("DELETE FROM chats WHERE space_id = ?", own);
      mockMvc.perform(as("dev-admin", delete("/api/v1/spaces/" + own)));
      jdbc.update("DELETE FROM space_membership_history WHERE space_id = ?", own);
      jdbc.update("DELETE FROM asset_ownership_history WHERE asset_id = ?", own);
      jdbc.update("DELETE FROM audit_log WHERE object_id = ?", own.toString());
    }
    spaces.clear();
  }

  @Test
  void anUploadIsReadIntoTextListedAndDeletedWithItsText() throws Exception {
    UUID attachment = upload("Vertrag.txt", TEXT);

    mockMvc
        .perform(as("dev-user", get("/api/v1/chat-attachments/" + attachment)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("READY"))
        .andExpect(jsonPath("$.mode").value("FULL_TEXT"))
        .andExpect(jsonPath("$.chatId").doesNotExist())
        .andExpect(jsonPath("$.deletionDueAt").exists());
    mockMvc
        .perform(as("dev-user", get("/api/v1/chat-attachments")))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$[?(@.id == '%s')].fileName".formatted(attachment)).value("Vertrag.txt"));
    mockMvc
        .perform(as("dev-user", get("/api/v1/chat-attachments/" + attachment + "/content")))
        .andExpect(status().isOk())
        .andExpect(content().bytes(TEXT.getBytes(StandardCharsets.UTF_8)));
    mockMvc
        .perform(as("dev-admin", get("/api/v1/chat-attachments/" + attachment)))
        .andExpect(status().isNotFound());

    mockMvc
        .perform(as("dev-user", delete("/api/v1/chat-attachments/" + attachment)))
        .andExpect(status().isNoContent());
    mockMvc
        .perform(as("dev-user", get("/api/v1/chat-attachments/" + attachment)))
        .andExpect(status().isNotFound());
    assertThat(chunkCount(attachment)).isZero();
  }

  @Test
  void anUnsupportedFormatOrAnEmptyFileIsRefusedBeforeAnythingStays() throws Exception {
    mockMvc
        .perform(
            as(
                "dev-user",
                multipart("/api/v1/spaces/" + space + "/chat-attachments")
                    .file(new MockMultipartFile("file", "Bild.exe", null, new byte[] {1, 2}))))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(
            as(
                "dev-user",
                multipart("/api/v1/spaces/" + space + "/chat-attachments")
                    .file(new MockMultipartFile("file", "Leer.txt", null, new byte[0]))))
        .andExpect(status().isBadRequest());

    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM chat_attachments WHERE space_id = ?", Integer.class, space))
        .isZero();
  }

  @Test
  void theQuestionGetsThePassagesAndTheTurnBindsTheAttachmentToTheChat() throws Exception {
    UUID attachment = upload("Vertrag.txt", TEXT);
    Chat chat = chatRepository.findById(aChat()).orElseThrow();

    ChatAttachmentContext context =
        attachmentService.contextFor(chat, List.of(attachment), "Wie lang ist die Frist?");
    assertThat(context.passages()).hasSize(1);
    assertThat(context.passages().getFirst().getText()).isEqualTo(TEXT);
    assertThat(context.newAttachmentIds()).containsExactly(attachment);

    chatService.appendTurn(
        chat,
        "Wie lang ist die Frist?",
        null,
        "Drei Monate.",
        List.of(),
        null,
        List.of(attachment));

    assertThat(
            jdbc.queryForObject(
                "SELECT chat_id FROM chat_attachments WHERE id = ?", UUID.class, attachment))
        .isEqualTo(chat.getId());
    ChatAttachmentContext followUp = attachmentService.contextFor(chat, List.of(), "Und danach?");
    assertThat(followUp.attachmentIds()).containsExactly(attachment);
    assertThat(followUp.newAttachmentIds()).isEmpty();
    assertThatThrownBy(() -> attachmentService.contextFor(chat, List.of(attachment), "Nochmal"))
        .isInstanceOf(ValidationException.class);

    jdbc.update("DELETE FROM chats WHERE id = ?", chat.getId());
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM chat_attachments WHERE id = ?", Integer.class, attachment))
        .isZero();
  }

  /**
   * A file a tool produced goes through the upload checks, is bound to the answer's message, and is
   * refused once the chat with the files of the same turn would exceed max-per-chat.
   */
  @Test
  void aGeneratedFileIsBoundToTheAnswerAndRefusedAboveTheChatsLimit() throws Exception {
    Chat chat = chatRepository.findById(aChat()).orElseThrow();
    ChatAttachmentView generated =
        attachmentService.uploadGenerated(
            space, chat.getId(), 0, "bericht.txt", TEXT.getBytes(StandardCharsets.UTF_8), devUser);

    chatService.appendTurn(
        chat,
        "Erstelle einen Bericht",
        null,
        "Erstellt.",
        List.of(),
        null,
        List.of(),
        List.of(generated.attachment().getId()));

    UUID answerMessage =
        jdbc.queryForObject(
            "SELECT id FROM chat_messages WHERE chat_id = ? AND role = 'ASSISTANT'",
            UUID.class,
            chat.getId());
    assertThat(
            jdbc.queryForObject(
                "SELECT message_id FROM chat_attachments WHERE id = ?",
                UUID.class,
                generated.attachment().getId()))
        .isEqualTo(answerMessage);
    assertThatThrownBy(
            () ->
                attachmentService.uploadGenerated(
                    space,
                    chat.getId(),
                    49,
                    "noch-einer.txt",
                    TEXT.getBytes(StandardCharsets.UTF_8),
                    devUser))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("50 Anhänge");
  }

  @Test
  void anotherPersonsAttachmentIsNoAttachmentOfTheQuestion() throws Exception {
    UUID attachment = upload("Vertrag.txt", TEXT);
    UUID admin =
        jdbc.queryForObject("SELECT id FROM users WHERE subject = 'dev-admin'", UUID.class);
    jdbc.update("UPDATE chat_attachments SET author_id = ? WHERE id = ?", admin, attachment);
    Chat chat = chatRepository.findById(aChat()).orElseThrow();

    assertThatThrownBy(() -> attachmentService.contextFor(chat, List.of(attachment), "Frage"))
        .isInstanceOf(ValidationException.class);
    jdbc.update("UPDATE chat_attachments SET author_id = ? WHERE id = ?", devUser, attachment);
  }

  @Test
  void theCleanupRemovesAnUnboundAttachmentPastItsRetention() throws Exception {
    UUID stale = upload("Alt.txt", TEXT);
    UUID fresh = upload("Neu.txt", TEXT);
    jdbc.update(
        "UPDATE chat_attachments SET created_at = now() - interval '2 days' WHERE id = ?", stale);

    cleanup.runOnce();

    assertThat(
            jdbc.queryForList(
                "SELECT id FROM chat_attachments WHERE id IN (?, ?)", UUID.class, stale, fresh))
        .containsExactly(fresh);
    assertThat(chunkCount(stale)).isZero();
  }

  /**
   * The account's quota counts bound and unbound attachments alike; an upload beyond it is a 413
   * and leaves no row behind.
   */
  @Test
  void anUploadBeyondTheAccountsQuotaIsRefusedAndNothingStays() throws Exception {
    byte[] file = TEXT.getBytes(StandardCharsets.UTF_8);
    occupy(aChat(), properties.accountQuotaBytes() - usageOfDevUser() - file.length + 1);

    mockMvc
        .perform(as("dev-user", uploadRequest("Vertrag.txt", file)))
        .andExpect(status().isPayloadTooLarge())
        .andExpect(
            jsonPath("$.error").value(containsString("Speicherkontingent Ihrer Chat-Anhänge")));

    assertThat(rowsOfSpace()).isEqualTo(1);
  }

  /** Attachments no question has named have a lower cap of their own. */
  @Test
  void anUploadBeyondTheUnboundCapIsRefusedAndNothingStays() throws Exception {
    byte[] file = TEXT.getBytes(StandardCharsets.UTF_8);
    long unbound =
        jdbc.queryForObject(
            "SELECT coalesce(sum(size_bytes), 0) FROM chat_attachments"
                + " WHERE author_id = ? AND chat_id IS NULL",
            Long.class,
            devUser);
    occupy(null, properties.unboundQuotaBytes() - unbound - file.length + 1);

    mockMvc
        .perform(as("dev-user", uploadRequest("Vertrag.txt", file)))
        .andExpect(status().isPayloadTooLarge())
        .andExpect(jsonPath("$.error").value(containsString("noch nicht gesendeten Anhänge")));

    assertThat(rowsOfSpace()).isEqualTo(1);
  }

  /** Concurrent uploads that each fit alone, but not together, are admitted only while room is. */
  @Test
  void concurrentUploadsNeverOvershootTheAccountsQuota() throws Exception {
    byte[] file = TEXT.getBytes(StandardCharsets.UTF_8);
    occupy(aChat(), properties.accountQuotaBytes() - usageOfDevUser() - file.length);

    int uploads = 3;
    ExecutorService pool = Executors.newFixedThreadPool(uploads);
    try {
      List<Callable<Integer>> calls = new ArrayList<>();
      for (int i = 0; i < uploads; i++) {
        String name = "Vertrag-" + i + ".txt";
        calls.add(
            () ->
                mockMvc
                    .perform(as("dev-user", uploadRequest(name, file)))
                    .andReturn()
                    .getResponse()
                    .getStatus());
      }
      List<Integer> statuses = new ArrayList<>();
      for (Future<Integer> result : pool.invokeAll(calls)) {
        statuses.add(result.get());
      }
      assertThat(statuses).containsOnlyOnce(201);
    } finally {
      pool.shutdownNow();
    }
    assertThat(usageOfDevUser()).isLessThanOrEqualTo(properties.accountQuotaBytes());
  }

  private UUID upload(String fileName, String text) throws Exception {
    String body =
        mockMvc
            .perform(
                as(
                    "dev-user",
                    multipart("/api/v1/spaces/" + space + "/chat-attachments")
                        .file(
                            new MockMultipartFile(
                                "file",
                                fileName,
                                "text/plain",
                                text.getBytes(StandardCharsets.UTF_8)))))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("READY"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    return UUID.fromString(JsonPath.read(body, "$.id"));
  }

  private MockMultipartHttpServletRequestBuilder uploadRequest(String fileName, byte[] content) {
    return multipart("/api/v1/spaces/" + space + "/chat-attachments")
        .file(new MockMultipartFile("file", fileName, "text/plain", content));
  }

  /** A row of {@code bytes} of the dev user in this space, without an original. */
  private void occupy(UUID chat, long bytes) {
    jdbc.update(
        "INSERT INTO chat_attachments (id, organization_id, space_id, author_id, chat_id,"
            + " file_name, size_bytes, status, created_at) VALUES (?, ?, ?, ?, ?, 'Gross.txt', ?,"
            + " 'READY', now())",
        UUID.randomUUID(),
        organization,
        space,
        devUser,
        chat,
        bytes);
  }

  private long usageOfDevUser() {
    return jdbc.queryForObject(
        "SELECT coalesce(sum(size_bytes), 0) FROM chat_attachments WHERE author_id = ?",
        Long.class,
        devUser);
  }

  private int rowsOfSpace() {
    return jdbc.queryForObject(
        "SELECT count(*) FROM chat_attachments WHERE space_id = ?", Integer.class, space);
  }

  private UUID aChat() {
    UUID chat = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO chats (id, space_id, author_id, organization_id, use_knowledge, status,"
            + " created_at, updated_at) VALUES (?, ?, ?, ?, true, 'PRIVATE', now(), now())",
        chat,
        space,
        devUser,
        organization);
    return chat;
  }

  private int chunkCount(UUID attachment) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM chat_attachment_chunks WHERE attachment_id = ?",
        Integer.class,
        attachment);
  }

  private static <B extends AbstractMockHttpServletRequestBuilder<B>> B as(String subject, B r) {
    return r.header(DevAuthFilter.DEV_USER_HEADER, subject);
  }
}
