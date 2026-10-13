package io.opaa.metadata.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.opaa.api.types.SystemRole;
import io.opaa.auth.TestSecurityConfig;
import io.opaa.auth.User;
import io.opaa.auth.UserService;
import io.opaa.indexing.web.MetadataMaintenanceAdminController;
import io.opaa.query.web.MetadataFilterOptionsController;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * With the metadata feature off (ADR-0044) every metadata route is absent and answers like any
 * unknown address: the schema and value endpoints, the filter options of the chat and the two
 * maintenance runs of the indexing administration. None of the controllers' services is provided
 * here - an absent route must not need them.
 */
@WebMvcTest(
    controllers = {
      DocumentMetadataController.class,
      LibraryMetadataFieldController.class,
      MetadataFilterOptionsController.class,
      MetadataMaintenanceAdminController.class
    },
    properties = "opaa.features.metadata.enabled=false")
@ActiveProfiles({"test", "dev"})
@Import(TestSecurityConfig.class)
class MetadataRoutesSwitchedOffTest {

  private static final UUID LIBRARY = UUID.randomUUID();
  private static final UUID DOCUMENT = UUID.randomUUID();

  @Autowired private MockMvc mockMvc;
  @MockitoBean private UserService userService;

  @BeforeEach
  void setUp() {
    User admin = new User("test-subject", "test-issuer", "admin@example.com", "Admin");
    admin.setSystemRole(SystemRole.SYSTEM_ADMIN);
    when(userService.provisionFromToken(any())).thenReturn(admin);
  }

  private RequestPostProcessor asAdmin() {
    return jwt()
        .jwt(builder -> builder.subject("test-subject").claim("iss", "test-issuer"))
        .authorities(new SimpleGrantedAuthority("ROLE_SYSTEM_ADMIN"));
  }

  @Test
  void theLibraryAndDocumentMetadataRoutesAreAbsent() throws Exception {
    String library = "/api/v1/libraries/" + LIBRARY;
    String document = library + "/documents/" + DOCUMENT;
    expectAbsent(get(library + "/metadata/extraction-settings"));
    expectAbsent(put(library + "/metadata/extraction-settings").content("{}"));
    expectAbsent(get(library + "/metadata/quality"));
    expectAbsent(get(library + "/metadata/sample"));
    expectAbsent(get(library + "/metadata/maintenance"));
    expectAbsent(get(document + "/metadata"));
    expectAbsent(put(document + "/metadata/document_type").content("{}"));
    expectAbsent(delete(document + "/metadata/document_type"));
    expectAbsent(post(library + "/documents/metadata/bulk").content("{}"));
    expectAbsent(get("/api/v1/metadata/document-types"));
  }

  @Test
  void theLibraryFieldRoutesAreAbsent() throws Exception {
    String fields = "/api/v1/libraries/" + LIBRARY + "/metadata-fields";
    expectAbsent(get(fields));
    expectAbsent(post(fields).content("{}"));
    expectAbsent(get(fields + "/change-impact"));
    expectAbsent(put(fields + "/core-context-prefix").content("{}"));
    expectAbsent(put(fields + "/fassung").content("{}"));
    expectAbsent(delete(fields + "/fassung"));
    expectAbsent(post(fields + "/schema-changes/run").content("{}"));
    expectAbsent(get(fields + "/fassung/usage"));
    expectAbsent(post(fields + "/fassung/values").content("{}"));
  }

  @Test
  void theFilterOptionsOfTheChatAreAbsent() throws Exception {
    expectAbsent(get("/api/v1/search/metadata-filter-options"));
  }

  @Test
  void theMaintenanceRunsOfTheIndexingAdministrationAreAbsent() throws Exception {
    String body = "{\"libraryId\":\"" + LIBRARY + "\"}";
    expectAbsent(post("/api/v1/admin/indexing/metadata-backfill").content(body));
    expectAbsent(post("/api/v1/admin/indexing/context-prefix-rerun").content(body));
  }

  private void expectAbsent(MockHttpServletRequestBuilder request) throws Exception {
    mockMvc
        .perform(request.contentType(MediaType.APPLICATION_JSON).with(asAdmin()))
        .andExpect(status().isNotFound());
  }
}
