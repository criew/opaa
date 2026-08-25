package io.opaa.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.opaa.api.types.ActorKind;
import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditObjectType;
import io.opaa.api.types.AuditOutcome;
import io.opaa.api.types.SystemRole;
import io.opaa.audit.AuditIncidentScopeService;
import io.opaa.audit.AuditLogEntry;
import io.opaa.audit.AuditQueryService;
import io.opaa.auth.AdminTestSecurityConfig;
import io.opaa.auth.User;
import io.opaa.auth.UserService;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * #393 acceptance criterion: "Ein Test gegen sämtliche Auswertungsendpunkte belegt, dass keiner
 * nach Person filtert, gruppiert oder sortiert." {@link
 * #noEndpointAcceptsAnActorOrSortRequestParameter()} is that cross-cutting proof, at the HTTP layer
 * - it reflects over every {@code @RequestParam} of every method {@link AuditController} declares
 * and fails if any of them could be used to name, filter, group or sort by a person. {@code
 * io.opaa.audit.AuditQueryServiceIntegrationTest#noAccessPathAcceptsOrSortsByActor()} makes the
 * same proof one layer down, against the service the controller delegates to.
 *
 * <p>#394: {@link AuditQueryService} is mocked here (this is a {@code @WebMvcTest} slice), so the
 * real role/reason enforcement this class now performs is exercised by {@code
 * AuditQueryServiceIntegrationTest} instead, against the real bean. {@link
 * #listByObjectAsRegularUserReturns403()} below stubs the mock to throw {@link
 * AccessDeniedException} - what the real {@link AuditQueryService} now does for a non-AUDITOR
 * caller - rather than relying on {@code @PreAuthorize}, which no controller method below declares
 * any more (see {@link AuditController}'s own Javadoc for why).
 */
@WebMvcTest(AuditController.class)
@ActiveProfiles("dev")
@Import(AdminTestSecurityConfig.class)
class AuditControllerTest {

  private static final String TEST_ISSUER = "test-issuer";
  private static final String TEST_SUBJECT = "test-subject";
  private static final String REGULAR_USER_SUBJECT = "test-subject-regular";
  private static final String REASON = "Quartalsrevision Q1 2026";

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
        .jwt(builder -> builder.subject(REGULAR_USER_SUBJECT).claim("iss", TEST_ISSUER))
        .authorities(new SimpleGrantedAuthority("ROLE_USER"));
  }

  @BeforeEach
  void setUp() {
    User auditor = new User(TEST_SUBJECT, TEST_ISSUER, "auditor@example.com", "Auditor");
    auditor.setSystemRole(SystemRole.AUDITOR);
    auditor.setOrganizationId(organizationId);
    setId(auditor, UUID.randomUUID());
    when(userService.findOrCreateUser(eq(TEST_SUBJECT), eq(TEST_ISSUER), any(), any()))
        .thenReturn(auditor);

    // UserProvisioningFilter (wired below via AdminTestSecurityConfig) re-derives authorities
    // from the provisioned User's real SystemRole - a distinct subject/user is required here so
    // that enrichment does not silently grant ROLE_AUDITOR to a request meant to simulate a
    // regular, non-AUDITOR caller.
    User regularUser = new User(REGULAR_USER_SUBJECT, TEST_ISSUER, "user@example.com", "User");
    regularUser.setSystemRole(SystemRole.USER);
    regularUser.setOrganizationId(organizationId);
    setId(regularUser, UUID.randomUUID());
    when(userService.findOrCreateUser(eq(REGULAR_USER_SUBJECT), eq(TEST_ISSUER), any(), any()))
        .thenReturn(regularUser);
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
    when(queryService.byObject(any(), any(), any(), any(), any(), any(), any(), anyInt(), anyInt()))
        .thenReturn(new PageImpl<>(List.of(entry)));

    mockMvc
        .perform(
            get("/api/v1/audit/events/by-object")
                .with(asAuditor())
                .param("objectType", "KNOWLEDGE_LIBRARY")
                .param("objectId", "lib-1")
                .param("from", "2026-02-01T00:00:00Z")
                .param("to", "2026-02-28T00:00:00Z")
                .param("reason", REASON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.events[0].actorRef").value("pseud-1"))
        .andExpect(jsonPath("$.events[0].objectId").value("lib-1"));
  }

  /**
   * #394: unlike before, this no longer relies on {@code @PreAuthorize} short-circuiting before the
   * controller method runs - none of the five read endpoints declares it any more. The 403 comes
   * from the real {@link AuditQueryService}'s own role check instead, simulated here by stubbing
   * the mock to throw exactly what that check throws for a non-AUDITOR caller.
   */
  @Test
  void listByObjectAsRegularUserReturns403() throws Exception {
    when(queryService.byObject(any(), any(), any(), any(), any(), any(), any(), anyInt(), anyInt()))
        .thenThrow(new AccessDeniedException("Zugriff verweigert"));

    mockMvc
        .perform(
            get("/api/v1/audit/events/by-object")
                .with(asRegularUser())
                .param("objectType", "KNOWLEDGE_LIBRARY")
                .param("objectId", "lib-1")
                .param("from", "2026-02-01T00:00:00Z")
                .param("to", "2026-02-28T00:00:00Z")
                .param("reason", REASON))
        .andExpect(status().isForbidden());
  }

  @Test
  void listByTimeRangeWithoutMandatoryFromIsRejected() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/audit/events/by-time-range")
                .with(asAuditor())
                .param("to", "2026-02-28T00:00:00Z")
                .param("reason", REASON))
        .andExpect(status().is4xxClientError());
  }

  /**
   * #393 code review, nit 6: an unparsable value (as opposed to a missing one) for a required
   * parameter must also be a 400, not the 500 {@code MethodArgumentTypeMismatchException} fell
   * through to before {@code GlobalExceptionHandler} gained a dedicated handler for it.
   */
  @Test
  void listByObjectWithAnUnparsableObjectTypeReturns400() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/audit/events/by-object")
                .with(asAuditor())
                .param("objectType", "NOT_A_REAL_OBJECT_TYPE")
                .param("objectId", "lib-1")
                .param("from", "2026-02-01T00:00:00Z")
                .param("to", "2026-02-28T00:00:00Z")
                .param("reason", REASON))
        .andExpect(status().isBadRequest());
  }

  @Test
  void listByObjectWithAnUnparsableFromReturns400() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/audit/events/by-object")
                .with(asAuditor())
                .param("objectType", "KNOWLEDGE_LIBRARY")
                .param("objectId", "lib-1")
                .param("from", "gestern")
                .param("to", "2026-02-28T00:00:00Z")
                .param("reason", REASON))
        .andExpect(status().isBadRequest());
  }

  /**
   * #394 acceptance criterion: "eine Abfrage ohne Anlass wird abgewiesen" - proven here at the HTTP
   * layer by simulating exactly what the real {@link AuditQueryService} does for a missing reason
   * (see {@code AuditQueryServiceIntegrationTest#aMissingReasonIsRejectedAndTheRejectionIsLogged}
   * for the real-service proof, including that the rejection is logged).
   */
  @Test
  void listByObjectWithoutReasonIsRejected() throws Exception {
    when(queryService.byObject(any(), any(), any(), any(), any(), any(), any(), anyInt(), anyInt()))
        .thenThrow(new IllegalArgumentException("reason ist ein Pflichtfeld"));

    mockMvc
        .perform(
            get("/api/v1/audit/events/by-object")
                .with(asAuditor())
                .param("objectType", "KNOWLEDGE_LIBRARY")
                .param("objectId", "lib-1")
                .param("from", "2026-02-01T00:00:00Z")
                .param("to", "2026-02-28T00:00:00Z"))
        .andExpect(status().isBadRequest());
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
   * {@code @RequestParam} on every public HTTP-handler method {@link AuditController} declares,
   * across every one of its revision access paths, is inspected by name - none may be usable to
   * name, filter, group or sort by the acting person.
   *
   * <p>#393 code review, nit 4: this alone would miss a future unannotated {@code Pageable
   * pageable} parameter, which Spring's {@code PageableHandlerMethodArgumentResolver} binds
   * straight from {@code ?sort=actorRef,desc} without ever going through {@code @RequestParam} -
   * see {@link #noParameterIsUnannotatedOrClientControlledSort()} for the structural check that
   * closes exactly that gap, and {@link #forbiddenSubstringsCoverAllDeclaredParameterNames()} for
   * why iterating {@code getDeclaredMethods()} rather than a hardcoded method-name list matters
   * here too.
   */
  @Test
  void noEndpointAcceptsAnActorOrSortRequestParameter() {
    // "subjectUserId" (the one named exception, set in advance in the incident-scope request
    // body, not a per-call filter) is on a request body DTO, never a @RequestParam, so it is
    // structurally outside what this check even scans - no exception list needed here.
    List<String> forbiddenSubstrings = List.of("actor", "sort", "person", "user", "subject");

    for (Method method : httpHandlerMethods()) {
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

  /**
   * #393 code review, nit 4: closes the blind spot the substring check above cannot see on its own
   * - an unannotated {@code Pageable}/{@code Sort}/{@code @ModelAttribute} parameter binds from
   * arbitrary query parameters (including {@code sort=actorRef,desc}) without ever carrying an
   * {@code @RequestParam} this class could inspect by name. Every parameter on every HTTP handler
   * method must therefore either be one of the explicit, named binding annotations this controller
   * already uses, or {@code @AuthenticationPrincipal} - never left unannotated, and never typed
   * {@link Pageable}/{@link Sort} even if it were annotated.
   */
  @Test
  void noParameterIsUnannotatedOrClientControlledSort() {
    for (Method method : httpHandlerMethods()) {
      for (Parameter parameter : method.getParameters()) {
        Class<?> type = parameter.getType();
        failIf(
            type == Pageable.class || type == Sort.class,
            "Method "
                + method.getName()
                + " has a "
                + type.getSimpleName()
                + " parameter - it would accept a client-controlled ?sort=actorRef,desc without"
                + " ever going through @RequestParam (#393)");

        boolean explicitlyBound =
            parameter.isAnnotationPresent(RequestParam.class)
                || parameter.isAnnotationPresent(PathVariable.class)
                || parameter.isAnnotationPresent(RequestBody.class)
                || parameter.isAnnotationPresent(AuthenticationPrincipal.class)
                // CurrentUserArgumentResolver only claims @Caller-annotated parameters (fail-closed
                // by design, see its Javadoc) - an unannotated CurrentUser parameter would be
                // eligible for Spring MVC's catch-all ModelAttributeMethodProcessor instead,
                // exactly
                // like an unannotated Pageable/Sort would be.
                || parameter.isAnnotationPresent(io.opaa.auth.Caller.class);
        failIf(
            !explicitlyBound,
            "Method "
                + method.getName()
                + " has an unannotated parameter of type "
                + type.getSimpleName()
                + " - Spring can bind such a parameter (e.g. Pageable) from arbitrary request"
                + " parameters without it ever appearing as a named @RequestParam (#393)");
      }
    }
  }

  /**
   * #393 code review, nit 4: the other half of the same finding - a hardcoded method-name list (as
   * the two tests above no longer use) would silently stop covering a newly added access path. This
   * asserts the controller's public HTTP-handler surface still consists of exactly the seven #393
   * endpoints; growing that list is a deliberate reminder to add the new method to the checks above
   * as well, not an assertion this test is expected to keep failing forever.
   */
  @Test
  void forbiddenSubstringsCoverAllDeclaredParameterNames() {
    List<String> methodNames = httpHandlerMethods().stream().map(Method::getName).sorted().toList();

    failIf(
        methodNames.size() != 7,
        "AuditController's public HTTP-handler method count changed to "
            + methodNames.size()
            + " ("
            + methodNames
            + ") - review whether the new method needs covering here too before adjusting this"
            + " count");
  }

  /**
   * Every {@code public}, non-synthetic method declared directly on {@link AuditController} - its
   * full HTTP-handler surface, private helpers like {@code toPage}/{@code toEventResponse} excluded
   * by construction since those are not {@code public}.
   */
  private List<Method> httpHandlerMethods() {
    return Arrays.stream(AuditController.class.getDeclaredMethods())
        .filter(m -> Modifier.isPublic(m.getModifiers()))
        .filter(m -> !m.isSynthetic())
        .toList();
  }

  private void failIf(boolean condition, String message) {
    if (condition) {
      throw new AssertionError(message);
    }
  }
}
