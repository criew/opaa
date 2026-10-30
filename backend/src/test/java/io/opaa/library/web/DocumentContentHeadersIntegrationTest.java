package io.opaa.library.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.opaa.auth.DevAuthFilter;
import io.opaa.test.OpaaIntegrationTest;
import io.opaa.test.OwnLibraryFixtures;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The headers of a document download through the real filter chain and controller: {@code nosniff},
 * a frame protection and a {@code Content-Disposition} with both file name forms. A type outside
 * {@link #INLINE_PREVIEW_TYPES} may only be served inline under a sandboxing {@code
 * Content-Security-Policy}; tightening this to {@code attachment} means dropping that alternative
 * in {@link #dispositionFitsTheType}.
 */
@OpaaIntegrationTest
class DocumentContentHeadersIntegrationTest {

  /** The types the application may open in its own origin; everything else is a download. */
  static final Set<String> INLINE_PREVIEW_TYPES =
      Set.of("application/pdf", "image/png", "image/jpeg", "image/gif", "image/webp");

  @Autowired private MockMvc mockMvc;
  @Autowired private OwnLibraryFixtures libraryFixtures;

  private final List<UUID> libraries = new ArrayList<>();
  private UUID library;

  @BeforeEach
  void anUploadLibrary() throws Exception {
    String created =
        mockMvc
            .perform(
                post("/api/v1/libraries")
                    .header(DevAuthFilter.DEV_USER_HEADER, "dev-user")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\": \"Download-Header\", \"sourceType\": \"UPLOAD\"}"))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    library = UUID.fromString(JsonPath.read(created, "$.id"));
    libraries.add(library);
  }

  @AfterEach
  void removeLibraries() {
    libraryFixtures.removeLibraries(libraries.toArray(UUID[]::new));
    libraries.clear();
  }

  @ParameterizedTest
  @CsvSource({
    "notiz.txt, text/plain, Eine Notiz",
    "seite.html, text/html, <html><body><script>alert(1)</script>Seite</body></html>",
    "bericht-ä ü.txt, text/plain, Umlaut im Namen"
  })
  void theDownloadCarriesTheMandatoryHeaders(String fileName, String type, String text)
      throws Exception {
    String uploaded =
        mockMvc
            .perform(
                multipart("/api/v1/libraries/" + library + "/documents")
                    .file(
                        new MockMultipartFile(
                            "file", fileName, type, text.getBytes(StandardCharsets.UTF_8)))
                    .header(DevAuthFilter.DEV_USER_HEADER, "dev-user"))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    String document = JsonPath.read(uploaded, "$.id");

    MockHttpServletResponse response =
        mockMvc
            .perform(
                get("/api/v1/documents/" + document + "/content")
                    .header(DevAuthFilter.DEV_USER_HEADER, "dev-user"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse();

    assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
    assertThat(response.getHeader("X-Frame-Options")).isEqualTo("DENY");
    String disposition = response.getHeader(HttpHeaders.CONTENT_DISPOSITION);
    assertThat(disposition)
        .as("Content-Disposition of %s", fileName)
        .matches(
            "(inline|attachment); filename=\"[\\x20-\\x7e&&[^\"\\\\]]+\"; filename\\*=UTF-8''\\S+");
    assertThat(dispositionFitsTheType(response))
        .as(
            "%s served as %s with Content-Disposition '%s' and Content-Security-Policy '%s'",
            fileName,
            response.getContentType(),
            disposition,
            response.getHeader("Content-Security-Policy"))
        .isTrue();
  }

  /**
   * An allowlisted type may open inline; any other type is a download, or inline in a sandbox
   * without any source of its own.
   */
  private static boolean dispositionFitsTheType(MockHttpServletResponse response) {
    MediaType served = MediaType.parseMediaType(response.getContentType());
    String type = served.getType() + "/" + served.getSubtype();
    String disposition = response.getHeader(HttpHeaders.CONTENT_DISPOSITION);
    if (INLINE_PREVIEW_TYPES.contains(type) || disposition.startsWith("attachment")) {
      return true;
    }
    String policy = response.getHeader("Content-Security-Policy");
    return policy != null && policy.contains("default-src 'none'") && policy.contains("sandbox");
  }
}
