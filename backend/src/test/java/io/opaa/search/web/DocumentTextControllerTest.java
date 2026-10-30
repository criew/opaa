package io.opaa.search.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.opaa.api.types.SystemRole;
import io.opaa.auth.TestSecurityConfig;
import io.opaa.auth.User;
import io.opaa.auth.UserService;
import io.opaa.format.chunk.ChunkAnchor;
import io.opaa.search.DocumentOutline;
import io.opaa.search.DocumentText;
import io.opaa.search.DocumentTextService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** {@link DocumentTextController}: parameters reach the service and the response is mapped. */
@WebMvcTest(DocumentTextController.class)
@ActiveProfiles({"test", "dev"})
@Import(TestSecurityConfig.class)
class DocumentTextControllerTest {

  private static final String TEST_ISSUER = "test-issuer";
  private static final String TEST_SUBJECT = "test-subject";

  @Autowired private MockMvc mockMvc;
  @MockitoBean private DocumentTextService documentTextService;
  @MockitoBean private UserService userService;

  @BeforeEach
  void setUp() {
    User user = new User(TEST_SUBJECT, TEST_ISSUER, "test@example.com", "Test User");
    user.setSystemRole(SystemRole.USER);
    when(userService.provisionFromToken(
            org.mockito.ArgumentMatchers.argThat(
                token -> token != null && TEST_SUBJECT.equals(token.getSubject()))))
        .thenReturn(user);
  }

  private RequestPostProcessor asTestUser() {
    return jwt().jwt(builder -> builder.subject(TEST_SUBJECT).claim("iss", TEST_ISSUER));
  }

  @Test
  void aWindowIsMappedWithPassagesAnchorsAndSections() throws Exception {
    UUID documentId = UUID.randomUUID();
    when(documentTextService.read(any(), eq(documentId), eq(4), eq(8), eq(true)))
        .thenReturn(
            new DocumentText(
                documentId,
                "vortrag.pptx",
                0,
                12,
                List.of(
                    new DocumentText.Passage(
                        4,
                        "Die Frist beträgt vier Wochen.",
                        "Folie 3",
                        ChunkAnchor.EMPTY.withSlide(3).withQuotes("Die Frist", null)),
                    new DocumentText.Passage(5, "Ohne Anker.", null, null)),
                List.of(new DocumentOutline.Section(4, "Folie 3", 1)),
                false));

    mockMvc
        .perform(
            get("/api/v1/documents/{id}/text", documentId)
                .param("from", "4")
                .param("to", "8")
                .param("sections", "true")
                .with(asTestUser()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.fileName").value("vortrag.pptx"))
        .andExpect(jsonPath("$.firstChunkIndex").value(0))
        .andExpect(jsonPath("$.lastChunkIndex").value(12))
        .andExpect(jsonPath("$.passages[0].chunkIndex").value(4))
        .andExpect(jsonPath("$.passages[0].location").value("Folie 3"))
        .andExpect(jsonPath("$.passages[0].anchor.slide").value(3))
        .andExpect(jsonPath("$.passages[0].anchor.quoteStart").value("Die Frist"))
        .andExpect(jsonPath("$.passages[1].anchor").doesNotExist())
        .andExpect(jsonPath("$.sections[0].label").value("Folie 3"))
        .andExpect(jsonPath("$.sections[0].level").value(1))
        .andExpect(jsonPath("$.sectionsTruncated").value(false));
  }

  @Test
  void anUnauthenticatedRequestIsRefused() throws Exception {
    mockMvc
        .perform(get("/api/v1/documents/{id}/text", UUID.randomUUID()))
        .andExpect(status().isUnauthorized());
  }
}
