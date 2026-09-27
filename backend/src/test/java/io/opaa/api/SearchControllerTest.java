package io.opaa.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.opaa.api.types.SystemRole;
import io.opaa.auth.TestSecurityConfig;
import io.opaa.auth.User;
import io.opaa.auth.UserService;
import io.opaa.search.PassageFetchService;
import io.opaa.search.SearchOutcome;
import io.opaa.search.SearchService;
import java.util.List;
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

/** Request validation of {@link SearchController}: the question rule of the specification. */
@WebMvcTest(SearchController.class)
@ActiveProfiles({"test", "dev"})
@Import(TestSecurityConfig.class)
class SearchControllerTest {

  private static final String TEST_ISSUER = "test-issuer";
  private static final String TEST_SUBJECT = "test-subject";

  @Autowired private MockMvc mockMvc;
  @MockitoBean private SearchService searchService;
  @MockitoBean private PassageFetchService passageFetchService;
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

  // regression guard for #1993: the question pattern must accept line breaks
  @Test
  void aMultilineQuestionIsAccepted() throws Exception {
    when(searchService.search(any(), any(), any(), any(), any()))
        .thenReturn(new SearchOutcome(List.of(), List.of()));

    mockMvc
        .perform(
            post("/api/v1/search")
                .with(asTestUser())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\": \"Erste Zeile\\nzweite Zeile\\r\\n\"}"))
        .andExpect(status().isOk());

    verify(searchService).search(any(), eq("Erste Zeile\nzweite Zeile\r\n"), any(), any(), any());
  }

  @Test
  void aQuestionOfWhitespaceAndLineBreaksOnlyIsRejectedWith400() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/search")
                .with(asTestUser())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\": \" \\n\\t\\r\\n \"}"))
        .andExpect(status().isBadRequest());

    verifyNoInteractions(searchService);
  }
}
