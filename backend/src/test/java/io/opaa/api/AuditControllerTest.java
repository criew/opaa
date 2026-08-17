package io.opaa.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.opaa.audit.ActorKind;
import io.opaa.audit.AuditEventType;
import io.opaa.audit.AuditIncidentScopeService;
import io.opaa.audit.AuditLogEntry;
import io.opaa.audit.AuditObjectType;
import io.opaa.audit.AuditOutcome;
import io.opaa.audit.AuditQueryService;
import io.opaa.auth.AdminTestSecurityConfig;
import io.opaa.auth.SystemRole;
import io.opaa.auth.User;
import io.opaa.auth.UserService;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * #393 acceptance criterion: "Ein Test gegen sämtliche Auswertungsendpunkte belegt, dass keiner
 * nach Person filtert, gruppiert oder sortiert." {@link
 * #noEndpointAcceptsAnActorOrSortRequestParameter()} is that cross-cutting proof, at the HTTP layer
 * - it reflects over every {@code @RequestParam} of every method {@link AuditController} declares
 * and fails if any of them could be used to name, filter, group or sort by a person. {@code
 * io.opaa.audit.AuditQueryServiceIntegrationTest#noAccessPathAcceptsOrSortsByActor()} makes the
 * same proof one layer down, against the service the controller delegates to.
 */
@WebMvcTest(AuditController.class)
@ActiveProfiles("dev")
@Import(AdminTestSecurityConfig.class)
class AuditControllerTest {

  private static final String TEST_ISSUER = "test-issuer";
  private static final String TEST_SUBJECT = "test-subject";

  @Autowired private MockMvc mockMvc;
  @MockitoBean private AuditQueryService queryService;
  @MockitoBean private AuditIncidentScopeService incidentScopeService;
  @MockitoBean private UserService userService;

  private final UUID organizationId = UUID.randomUUID();

  private RequestPostProcessor asAuditor() {
    return jwt()
        .jwt(builder -> builder.subject(TEST_SUBJECT).claim("iss", TEST_ISSUER))
        .authorities(new SimpleGrantedAuthority("ROLE_AUDITOR"));
  }

  private RequestPostProcessor asRegularUser() {
    return jwt()
        .jwt(builder -> builder.subject(TEST_SUBJECT).claim("iss", TEST_ISSUER))
        .authorities(new SimpleGrantedAuthority("ROLE_USER"));
  }

  @BeforeEach
  void setUp() {
    User auditor = new User(TEST_SUBJECT, TEST_ISSUER, "auditor@example.com", "Auditor");
    auditor.setSystemRole(SystemRole.AUDITOR);
    auditor.setOrganizationId(organizationId);
    setId(auditor, UUID.randomUUID());
    when(userService.findBySubjectAndIssuer(TEST_SUBJECT, TEST_ISSUER))
        .thenReturn(Optional.of(auditor));
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
  void listByObjectAsAuditorSucceeds() throws Exception {
    AuditLogEntry entry =
        AuditLogEntry.withoutSubject(
            organizationId,
            ActorKind.USER,
            "pseud-1",
            AuditEventType.LIBRARY_CREATED,
            AuditObjectType.KNOWLEDGE_LIBRARY,
            "lib-1",
            "Bibliothek",
            null,
            null,
            AuditOutcome.SUCCESS,
            null,
            null);
    when(queryService.byObject(any(), any(), any(), any(), any(), anyInt(), anyInt()))
        .thenReturn(new PageImpl<>(List.of(entry)));

    mockMvc
        .perform(
            get("/api/v1/audit/events/by-object")
                .with(asAuditor())
                .param("objectType", "KNOWLEDGE_LIBRARY")
                .param("objectId", "lib-1")
                .param("from", "2026-02-01T00:00:00Z")
                .param("to", "2026-02-28T00:00:00Z"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.events[0].actorRef").value("pseud-1"))
        .andExpect(jsonPath("$.events[0].objectId").value("lib-1"));
  }

  @Test
  void listByObjectAsRegularUserReturns403() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/audit/events/by-object")
                .with(asRegularUser())
                .param("objectType", "KNOWLEDGE_LIBRARY")
                .param("objectId", "lib-1")
                .param("from", "2026-02-01T00:00:00Z")
                .param("to", "2026-02-28T00:00:00Z"))
        .andExpect(status().isForbidden());
  }

  @Test
  void listByTimeRangeWithoutMandatoryFromIsRejected() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/audit/events/by-time-range")
                .with(asAuditor())
                .param("to", "2026-02-28T00:00:00Z"))
        .andExpect(status().is4xxClientError());
  }

  @Test
  void approveIncidentScopeAsRegularUserReturns403() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/audit/incident-scopes/" + UUID.randomUUID() + "/approve")
                .with(asRegularUser()))
        .andExpect(status().isForbidden());
  }

  /**
   * The dedicated cross-cutting proof this issue's acceptance criteria require: every
   * {@code @RequestParam} on every method {@link AuditController} declares, across every one of its
   * revision access paths, is inspected by name - none may be usable to name, filter, group or sort
   * by the acting person.
   */
  @Test
  void noEndpointAcceptsAnActorOrSortRequestParameter() {
    // "subjectUserId" (the one named exception, set in advance in the incident-scope request
    // body, not a per-call filter) is on a request body DTO, never a @RequestParam, so it is
    // structurally outside what this check even scans - no exception list needed here.
    List<String> forbiddenSubstrings = List.of("actor", "sort", "person", "user", "subject");

    for (Method method : AuditController.class.getDeclaredMethods()) {
      for (Parameter parameter : method.getParameters()) {
        RequestParam requestParam = parameter.getAnnotation(RequestParam.class);
        if (requestParam == null) {
          continue;
        }
        String name = !requestParam.name().isEmpty() ? requestParam.name() : parameter.getName();
        String lowerName = name.toLowerCase(Locale.ROOT);
        boolean forbidden = forbiddenSubstrings.stream().anyMatch(lowerName::contains);
        if (forbidden) {
          throw new AssertionError(
              "Method "
                  + method.getName()
                  + " declares @RequestParam \""
                  + name
                  + "\" - actor/person must never be a request parameter (#393)");
        }
      }
    }
  }
}
