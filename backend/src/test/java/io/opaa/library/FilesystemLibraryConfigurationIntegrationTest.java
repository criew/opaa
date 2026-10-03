package io.opaa.library;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.opaa.auth.DevAuthFilter;
import io.opaa.indexing.source.ConnectorData;
import io.opaa.indexing.source.filesystem.FilesystemSourceSettings;
import io.opaa.knowledge.KnowledgeLibraryRepository;
import io.opaa.test.OpaaIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The exclusion patterns of a {@code FILESYSTEM} library (#2184) through the API: stored on
 * creation, replaced and cleared on update, and an invalid glob refused with a German 400 before
 * anything is stored. Saving does not touch the disk, so {@code /data} needs not exist.
 */
@OpaaIntegrationTest
class FilesystemLibraryConfigurationIntegrationTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private KnowledgeLibraryRepository libraryRepository;

  @Test
  void exclusionPatternsAreStoredOnCreationAndReplacedOrClearedOnUpdate() throws Exception {
    String created =
        send(
            post("/api/v1/libraries"),
            """
            {
              "name": "Verzeichnis mit Ausschlüssen",
              "sourceType": "FILESYSTEM",
              "sourcePath": "/data/dokumente",
              "sourceSettings": {"excludePatterns": [" Archiv/** ", "**/*.tmp"]}
            }
            """,
            201);
    UUID libraryId =
        UUID.fromString(created.replaceAll("(?s).*\"id\":\"([0-9a-f-]{36})\".*", "$1"));
    try {
      assertThat(storedPatterns(libraryId)).containsExactly("Archiv/**", "**/*.tmp");
      assertThat(created).contains("\"excludePatterns\":[\"Archiv/**\",\"**/*.tmp\"]");

      send(
          put("/api/v1/libraries/" + libraryId),
          """
          {"name": "Verzeichnis mit Ausschlüssen", "sourceSettings": {"excludePatterns": ["Entwürfe/**"]}}
          """,
          200);
      assertThat(storedPatterns(libraryId)).containsExactly("Entwürfe/**");

      send(
          put("/api/v1/libraries/" + libraryId),
          """
          {"name": "Umbenannt"}
          """,
          200);
      assertThat(storedPatterns(libraryId)).containsExactly("Entwürfe/**");

      send(
          put("/api/v1/libraries/" + libraryId),
          """
          {"name": "Umbenannt", "sourcePath": "/data/dokumente", "sourceSettings": {"excludePatterns": []}}
          """,
          200);
      assertThat(storedPatterns(libraryId)).isEmpty();
    } finally {
      mockMvc
          .perform(
              delete("/api/v1/libraries/" + libraryId)
                  .header(DevAuthFilter.DEV_USER_HEADER, "dev-user"))
          .andExpect(status().isNoContent());
    }
  }

  @Test
  void anInvalidGlobIsRefusedWithAGermanBadRequest() throws Exception {
    String name = "Ungültiges Muster " + UUID.randomUUID();

    String response =
        send(
            post("/api/v1/libraries"),
            """
            {
              "name": "%s",
              "sourceType": "FILESYSTEM",
              "sourcePath": "/data/dokumente",
              "sourceSettings": {"excludePatterns": ["Archiv/[2020"]}
            }
            """
                .formatted(name),
            400);

    assertThat(response).contains("„Archiv/[2020“ ist kein gültiges Glob-Muster.");
    assertThat(libraryRepository.findAll()).noneMatch(library -> name.equals(library.getName()));
  }

  private String send(
      org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
      String body,
      int expectedStatus)
      throws Exception {
    return mockMvc
        .perform(
            request
                .header(DevAuthFilter.DEV_USER_HEADER, "dev-user")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().is(expectedStatus))
        .andReturn()
        .getResponse()
        .getContentAsString(StandardCharsets.UTF_8);
  }

  private java.util.List<String> storedPatterns(UUID libraryId) {
    return FilesystemSourceSettings.of(
            ConnectorData.storedIn(libraryRepository.findById(libraryId).orElseThrow()))
        .excludePatterns();
  }
}
