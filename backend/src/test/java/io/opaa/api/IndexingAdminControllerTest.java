package io.opaa.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.opaa.auth.AdminTestSecurityConfig;
import io.opaa.auth.User;
import io.opaa.auth.UserService;
import io.opaa.indexing.LowChunkDocumentAuditService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * {@link IndexingAdminController} in isolation, {@link LowChunkDocumentAuditService} mocked -
 * proves the {@code SYSTEM_ADMIN} access bar and that the caller's own organizationId, not a
 * request parameter, drives the query (#1090).
 */
@WebMvcTest(IndexingAdminController.class)
@ActiveProfiles("dev")
@Import(AdminTestSecurityConfig.class)
class IndexingAdminControllerTest {

  private static final String TEST_ISSUER = "test-issuer";
  private static final String TEST_SUBJECT = "test-subject";

  @Autowired private MockMvc mockMvc;
  @MockitoBean private LowChunkDocumentAuditService lowChunkDocumentAuditService;
  @MockitoBean private UserService userService;

  private final UUID actingAdminId = UUID.randomUUID();
  private final UUID actingAdminOrganizationId = UUID.randomUUID();

  private RequestPostProcessor asAdmin() {
    return jwt()
        .jwt(builder -> builder.subject(TEST_SUBJECT).claim("iss", TEST_ISSUER))
        .authorities(new SimpleGrantedAuthority("ROLE_SYSTEM_ADMIN"));
  }

  private RequestPostProcessor asRegularUser() {
    return jwt()
        .jwt(builder -> builder.subject(TEST_SUBJECT).claim("iss", TEST_ISSUER))
        .authorities(new SimpleGrantedAuthority("ROLE_USER"));
  }

  @BeforeEach
  void setUp() {
    User actingAdmin = new User(TEST_SUBJECT, TEST_ISSUER, "admin@example.com", "Admin");
    actingAdmin.setOrganizationId(actingAdminOrganizationId);
    setId(actingAdmin, actingAdminId);
    when(userService.findOrCreateUser(eq(TEST_SUBJECT), eq(TEST_ISSUER), any(), any()))
        .thenReturn(actingAdmin);
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
  void listLowChunkDocumentsAsRegularUserReturns403() throws Exception {
    mockMvc
        .perform(get("/api/v1/admin/indexing/low-chunk-documents").with(asRegularUser()))
        .andExpect(status().isForbidden());
  }

  @Test
  void listLowChunkDocumentsScopesToTheCallersOwnOrganization() throws Exception {
    Pageable pageable =
        PageRequest.of(0, 20, Sort.by(Sort.Order.asc("libraryId"), Sort.Order.asc("fileName")));
    var entry =
        new LowChunkDocumentAuditService.LowChunkDocumentEntry(
            UUID.randomUUID(), UUID.randomUUID(), "Satzungen", "scan.pdf", 12_345L, 0);
    when(lowChunkDocumentAuditService.findLowChunkDocuments(actingAdminOrganizationId, 0, pageable))
        .thenReturn(new PageImpl<>(List.of(entry), pageable, 1));

    mockMvc
        .perform(get("/api/v1/admin/indexing/low-chunk-documents").with(asAdmin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].fileName").value("scan.pdf"))
        .andExpect(jsonPath("$.items[0].libraryName").value("Satzungen"))
        .andExpect(jsonPath("$.items[0].chunkCount").value(0))
        .andExpect(jsonPath("$.totalElements").value(1));

    verify(lowChunkDocumentAuditService)
        .findLowChunkDocuments(actingAdminOrganizationId, 0, pageable);
  }

  @Test
  void listLowChunkDocumentsPassesThroughChunkCountThresholdAndPaging() throws Exception {
    Pageable pageable =
        PageRequest.of(1, 5, Sort.by(Sort.Order.asc("libraryId"), Sort.Order.asc("fileName")));
    when(lowChunkDocumentAuditService.findLowChunkDocuments(actingAdminOrganizationId, 3, pageable))
        .thenReturn(new PageImpl<>(List.of(), pageable, 0));

    mockMvc
        .perform(
            get("/api/v1/admin/indexing/low-chunk-documents")
                .param("chunkCountThreshold", "3")
                .param("page", "1")
                .param("size", "5")
                .with(asAdmin()))
        .andExpect(status().isOk());

    verify(lowChunkDocumentAuditService)
        .findLowChunkDocuments(actingAdminOrganizationId, 3, pageable);
  }

  @Test
  void listLowChunkDocumentsRejectsAnOversizedPageWith400() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/admin/indexing/low-chunk-documents").param("size", "101").with(asAdmin()))
        .andExpect(status().isBadRequest());
  }
}
