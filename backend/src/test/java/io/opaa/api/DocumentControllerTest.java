package io.opaa.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.opaa.auth.CurrentUser;
import io.opaa.auth.SystemRole;
import io.opaa.auth.TestSecurityConfig;
import io.opaa.auth.User;
import io.opaa.auth.UserService;
import io.opaa.common.NotFoundException;
import io.opaa.library.DocumentContent;
import io.opaa.library.LibraryDocumentService;
import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Controller-level wiring test for {@link DocumentController} (#736): the HTTP request is
 * translated into a {@link LibraryDocumentService#loadContent} call, and its result into the
 * expected status code, body and headers. The access/sourceType/traversal decisions themselves are
 * covered at the service level in {@code LibraryDocumentServiceIntegrationTest} - mirrors {@code
 * LibraryControllerDocumentTest}'s own split between HTTP wiring and business logic.
 */
@WebMvcTest(DocumentController.class)
@ActiveProfiles({"test", "dev"})
@Import(TestSecurityConfig.class)
class DocumentControllerTest {

  private static final String TEST_ISSUER = "test-issuer";
  private static final String TEST_SUBJECT = "test-subject";

  @Autowired private MockMvc mockMvc;
  @MockitoBean private LibraryDocumentService documentService;
  @MockitoBean private UserService userService;

  private final UUID currentUserId = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    User user = new User(TEST_SUBJECT, TEST_ISSUER, "test@example.com", "Test User");
    user.setSystemRole(SystemRole.USER);
    setId(user, currentUserId);
    when(userService.findOrCreateUser(eq(TEST_SUBJECT), eq(TEST_ISSUER), any(), any()))
        .thenReturn(user);
  }

  private RequestPostProcessor asTestUser() {
    return jwt().jwt(builder -> builder.subject(TEST_SUBJECT).claim("iss", TEST_ISSUER));
  }

  private void setId(User user, UUID id) {
    try {
      var field = User.class.getDeclaredField("id");
      field.setAccessible(true);
      field.set(user, id);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
  }

  @Test
  void streamsTheResolvedFileWithInlineDispositionAndItsOwnContentType(@TempDir Path tempDir)
      throws Exception {
    UUID documentId = UUID.randomUUID();
    Path file = tempDir.resolve("original.txt");
    Files.writeString(file, "Originalinhalt", StandardCharsets.UTF_8);
    when(documentService.loadContent(eq(documentId), any(CurrentUser.class)))
        .thenReturn(new DocumentContent(file, "bericht 2026.txt", "text/plain"));

    mockMvc
        .perform(get("/api/v1/documents/" + documentId + "/content").with(asTestUser()))
        .andExpect(status().isOk())
        .andExpect(content().contentType(MediaType.parseMediaType("text/plain")))
        .andExpect(content().string("Originalinhalt"))
        .andExpect(
            header()
                .string(
                    "Content-Disposition",
                    "inline; filename=\"bericht 2026.txt\"; filename*=UTF-8''bericht%202026.txt"))
        .andExpect(header().string("X-Content-Type-Options", "nosniff"))
        .andExpect(header().string("Content-Security-Policy", "default-src 'none'; sandbox"));
  }

  @Test
  void encodesAnUmlautFileNameAsAnRfc5987ExtValueWithAnAsciiFallback(@TempDir Path tempDir)
      throws Exception {
    // #742 review, nit 4: filename* (RFC 5987/8187) is the whole point for a name outside ASCII -
    // the earlier test's "bericht 2026.txt" only exercises the space-encoding case, never a
    // genuinely non-ASCII byte.
    UUID documentId = UUID.randomUUID();
    Path file = tempDir.resolve("original.txt");
    Files.writeString(file, "Originalinhalt", StandardCharsets.UTF_8);
    when(documentService.loadContent(eq(documentId), any(CurrentUser.class)))
        .thenReturn(new DocumentContent(file, "Prüfbericht *2026*.txt", "text/plain"));

    mockMvc
        .perform(get("/api/v1/documents/" + documentId + "/content").with(asTestUser()))
        .andExpect(status().isOk())
        .andExpect(
            header()
                .string(
                    "Content-Disposition",
                    "inline; filename=\"Pr_fbericht *2026*.txt\";"
                        + " filename*=UTF-8''Pr%C3%BCfbericht%20%2A2026%2A.txt"));
  }

  @Test
  void aStreamedRemoteContentBodyIsClosedOnceItHasBeenWritten() throws Exception {
    // #748 review, finding 3: HTTP_DIRECTORY/RSS_FEED content is streamed straight from
    // LibraryDocumentService's still-open remote response - there is no temp file for this
    // controller to delete any more (see DocumentContent's Javadoc for why the previous
    // temp-file-plus-delete-on-close approach was replaced). What this controller still owes the
    // stream is closing it exactly once the response body has been written - asserted here via a
    // wrapper that records whether close() was called.
    UUID documentId = UUID.randomUUID();
    AtomicBoolean closed = new AtomicBoolean(false);
    InputStream remoteBody =
        new FilterInputStream(
            new ByteArrayInputStream(
                "Originalinhalt vom entfernten Quellsystem".getBytes(StandardCharsets.UTF_8))) {
          @Override
          public void close() throws java.io.IOException {
            closed.set(true);
            super.close();
          }
        };
    when(documentService.loadContent(eq(documentId), any(CurrentUser.class)))
        .thenReturn(DocumentContent.ofStream(remoteBody, "original.pdf", "application/pdf"));

    mockMvc
        .perform(get("/api/v1/documents/" + documentId + "/content").with(asTestUser()))
        .andExpect(status().isOk())
        .andExpect(content().string("Originalinhalt vom entfernten Quellsystem"));

    assertThat(closed.get()).isTrue();
  }

  @Test
  void aContentTypeTheSourceDeclaresThatIsNotAValidMediaTypeFallsBackToOctetStream(
      @TempDir Path tempDir) throws Exception {
    // #748 review, finding 2a: a stored/remote-declared Content-Type this class does not control
    // the syntax of must never turn MediaType.parseMediaType's IllegalArgumentException into a 500
    // - "the source sent something odd" stays a generic, successfully-served download instead of
    // an OPAA-side server error.
    UUID documentId = UUID.randomUUID();
    Path file = tempDir.resolve("original.bin");
    Files.writeString(file, "Originalinhalt", StandardCharsets.UTF_8);
    when(documentService.loadContent(eq(documentId), any(CurrentUser.class)))
        .thenReturn(new DocumentContent(file, "original.bin", "pdf"));

    mockMvc
        .perform(get("/api/v1/documents/" + documentId + "/content").with(asTestUser()))
        .andExpect(status().isOk())
        .andExpect(content().contentType(MediaType.APPLICATION_OCTET_STREAM))
        .andExpect(content().string("Originalinhalt"));
  }

  @Test
  void aDocumentTheServiceRefusesAnswers404() throws Exception {
    UUID documentId = UUID.randomUUID();
    when(documentService.loadContent(eq(documentId), any(CurrentUser.class)))
        .thenThrow(new NotFoundException("Dokument nicht gefunden"));

    mockMvc
        .perform(get("/api/v1/documents/" + documentId + "/content").with(asTestUser()))
        .andExpect(status().isNotFound());
  }
}
