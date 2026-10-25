package io.opaa.chat.web;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.opaa.api.types.SystemRole;
import io.opaa.auth.TestSecurityConfig;
import io.opaa.auth.User;
import io.opaa.auth.UserService;
import io.opaa.chat.ChatExportService;
import io.opaa.chat.ChatPdf;
import io.opaa.common.AccessDeniedException;
import io.opaa.common.NotFoundException;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * HTTP-level coverage for {@link ChatExportController} with {@link ChatExportService} mocked; the
 * read check itself is covered against a real database in {@code ChatServiceIntegrationTest}.
 */
@WebMvcTest(ChatExportController.class)
@ActiveProfiles({"test", "dev"})
@Import(TestSecurityConfig.class)
class ChatExportControllerTest {

  private static final String TEST_ISSUER = "test-issuer";
  private static final String TEST_SUBJECT = "test-subject";

  @Autowired private MockMvc mockMvc;
  @MockitoBean private ChatExportService chatExportService;
  @MockitoBean private UserService userService;

  private User currentUser;

  @BeforeEach
  void setUp() {
    currentUser = new User(TEST_SUBJECT, TEST_ISSUER, "test@example.com", "Test User");
    currentUser.setSystemRole(SystemRole.USER);
    when(userService.provisionFromToken(
            org.mockito.ArgumentMatchers.argThat(
                token -> token != null && TEST_SUBJECT.equals(token.getSubject()))))
        .thenReturn(currentUser);
  }

  private RequestPostProcessor asTestUser() {
    return jwt().jwt(builder -> builder.subject(TEST_SUBJECT).claim("iss", TEST_ISSUER));
  }

  @Test
  void exportReturnsThePdfAsAnAttachmentNamedAfterTheChat() throws Exception {
    UUID chatId = UUID.randomUUID();
    byte[] bytes = "%PDF-1.7 test".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    when(chatExportService.exportPdf(chatId, currentUser.getId()))
        .thenReturn(new ChatPdf("Frist für Widerspruch.pdf", bytes));

    mockMvc
        .perform(get("/api/v1/chats/{chatId}/export/pdf", chatId).with(asTestUser()))
        .andExpect(status().isOk())
        .andExpect(content().contentType(MediaType.APPLICATION_PDF))
        .andExpect(
            header()
                .string(
                    "Content-Disposition",
                    org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.startsWith("attachment;"),
                        org.hamcrest.Matchers.containsString(
                            "filename*=UTF-8''Frist%20f%C3%BCr%20Widerspruch.pdf"))))
        .andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(content().bytes(bytes));
  }

  @Test
  void aChatTheCallerCannotSeeAnswers404() throws Exception {
    UUID chatId = UUID.randomUUID();
    when(chatExportService.exportPdf(chatId, currentUser.getId()))
        .thenThrow(new NotFoundException("Chat nicht gefunden"));

    mockMvc
        .perform(get("/api/v1/chats/{chatId}/export/pdf", chatId).with(asTestUser()))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error").value("Chat nicht gefunden"));
  }

  @Test
  void anAuthorNoLongerInTheSpaceAnswers403() throws Exception {
    UUID chatId = UUID.randomUUID();
    when(chatExportService.exportPdf(chatId, currentUser.getId()))
        .thenThrow(new AccessDeniedException("Sie sind kein Mitglied dieses Space mehr"));

    mockMvc
        .perform(get("/api/v1/chats/{chatId}/export/pdf", chatId).with(asTestUser()))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error").value("Sie sind kein Mitglied dieses Space mehr"));
  }

  @Test
  void anAnonymousRequestIsRejected() throws Exception {
    mockMvc
        .perform(get("/api/v1/chats/{chatId}/export/pdf", UUID.randomUUID()))
        .andExpect(status().isUnauthorized());
  }
}
