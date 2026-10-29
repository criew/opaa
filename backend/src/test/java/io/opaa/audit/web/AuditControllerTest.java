package io.opaa.audit.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.tngtech.archunit.core.domain.JavaClass;
import io.opaa.api.types.ActorKind;
import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditIncidentScopePurpose;
import io.opaa.api.types.AuditObjectType;
import io.opaa.api.types.AuditOutcome;
import io.opaa.api.types.AuditSubjectKind;
import io.opaa.api.types.SystemRole;
import io.opaa.architecture.MainClasses;
import io.opaa.audit.AuditChainReport;
import io.opaa.audit.AuditEventCatalog;
import io.opaa.audit.AuditIncidentScopeGrant;
import io.opaa.audit.AuditIncidentScopeService;
import io.opaa.audit.AuditLogEntry;
import io.opaa.audit.AuditQueryService;
import io.opaa.auth.AdminTestSecurityConfig;
import io.opaa.auth.User;
import io.opaa.auth.UserService;
import io.opaa.common.ValidationException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
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
import org.springframework.stereotype.Controller;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.context.support.StaticWebApplicationContext;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * #393 acceptance criterion: "Ein Test gegen sämtliche Auswertungsendpunkte belegt, dass keiner
 * nach Person filtert, gruppiert oder sortiert." {@link
 * #noEndpointAcceptsAnActorOrSortRequestParameter()} is that cross-cutting proof, at the HTTP layer
 * - it reflects over every {@code @RequestParam} of every handler under {@code /api/v1/audit}
 * (except the diagnostic protocol, see {@code httpHandlerMethods}) and fails if any of them could
 * be used to name, filter, group or sort by a person. {@code
 * io.opaa.audit.AuditQueryServiceIntegrationTest#noAccessPathAcceptsOrSortsByActor()} makes the
 * same proof one layer down, against the service the controller delegates to.
 *
 * <p>{@link AuditQueryService} is mocked here (this is a {@code @WebMvcTest} slice), so the real
 * role/reason enforcement this class now performs is exercised by {@code
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

  private static final String AUDIT_PATH = "/api/v1/audit";
  private static final String DIAGNOSTIC_CONTEXT_PATH = "/api/v1/audit/diagnostic-context-events";

  /** The revision access paths of #393 plus the Stichtagsauskunft of #1822. */
  private static final List<String> KNOWN_REVISION_HANDLERS =
      List.of(
          "listAuditEventsByObject",
          "listAuditEventsByTimeRange",
          "listAuditEventsByEventType",
          "listAuditEventsByCorrelation",
          "listAccessAsOf",
          "listConnectionLog",
          "requestAuditIncidentScope",
          "approveAuditIncidentScope",
          "listAuditEventsByIncidentScope",
          "verifyAuditChain");

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
    when(userService.provisionFromToken(
            org.mockito.ArgumentMatchers.argThat(
                token -> token != null && TEST_SUBJECT.equals(token.getSubject()))))
        .thenReturn(auditor);

    // UserProvisioningFilter (wired below via AdminTestSecurityConfig) re-derives authorities
    // from the provisioned User's real SystemRole - a distinct subject/user is required here so
    // that enrichment does not silently grant ROLE_AUDITOR to a request meant to simulate a
    // regular, non-AUDITOR caller.
    User regularUser = new User(REGULAR_USER_SUBJECT, TEST_ISSUER, "user@example.com", "User");
    regularUser.setSystemRole(SystemRole.USER);
    regularUser.setOrganizationId(organizationId);
    setId(regularUser, UUID.randomUUID());
    when(userService.provisionFromToken(
            org.mockito.ArgumentMatchers.argThat(
                token -> REGULAR_USER_SUBJECT.equals(token.getSubject()))))
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
        .andExpect(jsonPath("$.events[0].actorRef").doesNotExist())
        .andExpect(jsonPath("$.events[0].objectId").value("lib-1"))
        .andExpect(
            jsonPath("$.events[0].schemaVersion").value((int) AuditEventCatalog.SCHEMA_VERSION));
  }

  /**
   * Regression guard for #3352: no revision response names a person, not even with a reference
   * valid for one answer. Two overlapping answers share event ids, so any such reference could be
   * equated from answer to answer; with none given, the overlap has nothing to join.
   */
  @Test
  void overlappingAnswersCarryNoPersonReferenceToJoin() throws Exception {
    AuditLogEntry shared = actedBy("pseud-x", "lib-1");
    when(queryService.byTimeRange(any(), any(), any(), any(), any(), anyInt(), anyInt()))
        .thenReturn(new PageImpl<>(List.of(shared, actedBy("pseud-x", "lib-2"))))
        .thenReturn(new PageImpl<>(List.of(actedBy("pseud-x", "lib-0"), shared)));

    String first = body(byTimeRange());
    String second = body(byTimeRange());

    for (String answer : List.of(first, second)) {
      assertThat(answer).doesNotContain("pseud-x");
      assertThat(JsonPath.<List<Object>>read(answer, "$.events[*].actorRef")).containsOnlyNulls();
      assertThat(JsonPath.<List<String>>read(answer, "$.events[*].actorKind")).containsOnly("USER");
    }
    assertThat(JsonPath.<String>read(first, "$.events[0].eventId"))
        .isEqualTo(JsonPath.read(second, "$.events[1].eventId"));
  }

  /**
   * Regression guard for #3352: a rights change at the moment the Stichtagsauskunft names a person
   * must not hand over her pseudonym - subject, account object and the owner in the payload are
   * left out; the kinds stay. A group keeps its id.
   */
  @Test
  void subjectAccountObjectAndOwnerAreLeftOut() throws Exception {
    String group = UUID.randomUUID().toString();
    AuditLogEntry grant =
        AuditLogEntry.withSubject(
            organizationId,
            ActorKind.USER,
            "pseud-admin",
            AuditEventType.ASSET_GRANT_GRANTED,
            AuditObjectType.KNOWLEDGE_LIBRARY,
            "lib-1",
            "Bibliothek",
            AuditSubjectKind.USER,
            "pseud-x",
            null,
            "{\"role\":\"VIEWER\"}",
            AuditOutcome.SUCCESS,
            null,
            null);
    AuditLogEntry groupGrant =
        AuditLogEntry.withSubject(
            organizationId,
            ActorKind.USER,
            "pseud-admin",
            AuditEventType.ASSET_GRANT_GRANTED,
            AuditObjectType.KNOWLEDGE_LIBRARY,
            "lib-1",
            "Bibliothek",
            AuditSubjectKind.GROUP,
            group,
            null,
            null,
            AuditOutcome.SUCCESS,
            null,
            null);
    AuditLogEntry account =
        AuditLogEntry.withSubject(
            organizationId,
            ActorKind.USER,
            "pseud-admin",
            AuditEventType.AUDITOR_ROLE_GRANTED,
            AuditObjectType.USER_ACCOUNT,
            "pseud-x",
            null,
            AuditSubjectKind.USER,
            "pseud-x",
            null,
            null,
            AuditOutcome.SUCCESS,
            null,
            null);
    AuditLogEntry owner =
        AuditLogEntry.withoutSubject(
            organizationId,
            ActorKind.USER,
            "pseud-admin",
            AuditEventType.SPACE_CREATED,
            AuditObjectType.SPACE,
            UUID.randomUUID().toString(),
            "Team",
            null,
            "{\"name\":\"Team\",\"ownerId\":\"pseud-x\"}",
            AuditOutcome.SUCCESS,
            null,
            null);
    when(queryService.byTimeRange(any(), any(), any(), any(), any(), anyInt(), anyInt()))
        .thenReturn(new PageImpl<>(List.of(grant, groupGrant, account, owner)));

    String body = body(byTimeRange());

    assertThat(body).doesNotContain("pseud-x").doesNotContain("pseud-admin");
    assertThat(JsonPath.<String>read(body, "$.events[0].subjectKind")).isEqualTo("USER");
    assertThat(JsonPath.<Object>read(body, "$.events[0].subjectRef")).isNull();
    assertThat(JsonPath.<String>read(body, "$.events[1].subjectRef")).isEqualTo(group);
    assertThat(JsonPath.<String>read(body, "$.events[2].objectType")).isEqualTo("USER_ACCOUNT");
    assertThat(JsonPath.<Object>read(body, "$.events[2].objectId")).isNull();
    assertThat(JsonPath.<String>read(body, "$.events[3].after")).isEqualTo("{\"name\":\"Team\"}");
    assertThat(JsonPath.<String>read(body, "$.events[0].after")).isEqualTo("{\"role\":\"VIEWER\"}");
  }

  /** A system process names no person; its fixed label goes out as stored. */
  @Test
  void aSystemProcessKeepsItsLabel() throws Exception {
    AuditLogEntry run =
        AuditLogEntry.withoutSubject(
            organizationId,
            ActorKind.SYSTEM_PROCESS,
            "directory-sync",
            AuditEventType.DIRECTORY_SYNC_RUN_COMPLETED,
            AuditObjectType.DIRECTORY_SYNC_RUN,
            UUID.randomUUID().toString(),
            null,
            null,
            null,
            AuditOutcome.SUCCESS,
            null,
            null);
    when(queryService.byTimeRange(any(), any(), any(), any(), any(), anyInt(), anyInt()))
        .thenReturn(new PageImpl<>(List.of(run)));

    byTimeRange().andExpect(jsonPath("$.events[0].actorRef").value("directory-sync"));
  }

  /**
   * Regression guard for #3352: the Klärung names the person with her account id, so a reference
   * next to it would hand over the key to every other answer. Its entries carry none.
   */
  @Test
  void theKlaerungCarriesNoActorRef() throws Exception {
    when(queryService.byIncidentScope(any(), any(), any(), any(), any(), any(), anyInt(), anyInt()))
        .thenReturn(
            new PageImpl<>(List.of(actedBy("pseud-x", "lib-1"), actedBy("pseud-x", "lib-2"))));

    String body =
        body(
            mockMvc
                .perform(
                    get("/api/v1/audit/incident-scopes/" + UUID.randomUUID() + "/events")
                        .with(asAuditor())
                        .param("from", "2026-02-01T00:00:00Z")
                        .param("to", "2026-02-28T00:00:00Z")
                        .param("reason", REASON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events.length()").value(2))
                .andExpect(jsonPath("$.events[0].actorRef").doesNotExist())
                .andExpect(jsonPath("$.events[1].actorRef").doesNotExist()));

    assertThat(body).doesNotContain("pseud-x");
  }

  /** The grant names the person by account id - that is its purpose; it carries no pseudonym. */
  @Test
  void requestingAKlaerungAnswersWithTheGrant() throws Exception {
    UUID subject = UUID.randomUUID();
    AuditIncidentScopeGrant grant =
        new AuditIncidentScopeGrant(
            organizationId,
            subject,
            Instant.parse("2026-02-01T00:00:00Z"),
            Instant.parse("2026-02-28T00:00:00Z"),
            AuditIncidentScopePurpose.SECURITY_INCIDENT,
            REASON,
            UUID.randomUUID());
    when(incidentScopeService.request(any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(grant);

    mockMvc
        .perform(
            post("/api/v1/audit/incident-scopes")
                .with(asAuditor())
                .contentType("application/json")
                .content(
                    "{\"subjectUserId\":\""
                        + subject
                        + "\",\"scopeStart\":\"2026-02-01T00:00:00Z\","
                        + "\"scopeEnd\":\"2026-02-28T00:00:00Z\","
                        + "\"purpose\":\"SECURITY_INCIDENT\",\"reason\":\""
                        + REASON
                        + "\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.subjectUserId").value(subject.toString()))
        .andExpect(jsonPath("$.status").value("PENDING"));
  }

  private AuditLogEntry actedBy(String actorRef, String libraryId) {
    return AuditLogEntry.withoutSubject(
        organizationId,
        ActorKind.USER,
        actorRef,
        AuditEventType.LIBRARY_CHANGED,
        AuditObjectType.KNOWLEDGE_LIBRARY,
        libraryId,
        "Bibliothek",
        null,
        null,
        AuditOutcome.SUCCESS,
        null,
        null);
  }

  private ResultActions byTimeRange() throws Exception {
    return mockMvc
        .perform(
            get("/api/v1/audit/events/by-time-range")
                .with(asAuditor())
                .param("from", "2026-02-01T00:00:00Z")
                .param("to", "2026-02-28T00:00:00Z")
                .param("reason", REASON))
        .andExpect(status().isOk());
  }

  private static String body(ResultActions result) throws Exception {
    return result.andReturn().getResponse().getContentAsString();
  }

  @Test
  void verifyChainAsAuditorReturnsTheReportWithoutAnyEntry() throws Exception {
    UUID sealEventId = UUID.randomUUID();
    when(queryService.verifyChain(any(), any(), any(), any()))
        .thenReturn(
            new AuditChainReport(
                YearMonth.of(2026, 9),
                AuditChainReport.Status.BROKEN,
                true,
                4711,
                0,
                3,
                "ab".repeat(32),
                sealEventId,
                "OPAA-AUDIT-CHAIN-1",
                new AuditChainReport.Deviation(
                    4000,
                    AuditChainReport.DeviationKind.CHANGED,
                    Instant.parse("2026-09-20T00:00:00Z"),
                    Instant.parse("2026-09-25T00:00:00Z"))));

    mockMvc
        .perform(get("/api/v1/audit/chain/2026-09").with(asAuditor()).param("reason", REASON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.month").value("2026-09"))
        .andExpect(jsonPath("$.status").value("BROKEN"))
        .andExpect(jsonPath("$.sealed").value(true))
        .andExpect(jsonPath("$.chainedEntries").value(4711))
        .andExpect(jsonPath("$.checkpoints").value(3))
        .andExpect(jsonPath("$.headHash").value("ab".repeat(32)))
        .andExpect(jsonPath("$.sealEventId").value(sealEventId.toString()))
        .andExpect(jsonPath("$.deviation.position").value(4000))
        .andExpect(jsonPath("$.deviation.kind").value("CHANGED"))
        .andExpect(jsonPath("$.deviation.segmentFrom").value("2026-09-20T00:00:00Z"));
  }

  /**
   * Unlike before, this no longer relies on {@code @PreAuthorize} short-circuiting before the
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
   * An unparsable value (as opposed to a missing one) for a required parameter must also be a 400,
   * not the 500 {@code MethodArgumentTypeMismatchException} fell through to before {@code
   * GlobalExceptionHandler} gained a dedicated handler for it.
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
        .thenThrow(new ValidationException("reason ist ein Pflichtfeld"));

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
   * {@code @RequestParam} on every HTTP-handler method under {@code /api/v1/audit} outside the
   * diagnostic protocol, across every revision access path, is inspected by name - none may be
   * usable to name, filter, group or sort by the acting person.
   *
   * <p>This alone would miss a future unannotated {@code Pageable pageable} parameter, which
   * Spring's {@code PageableHandlerMethodArgumentResolver} binds straight from {@code
   * ?sort=actorRef,desc} without ever going through {@code @RequestParam} - see {@link
   * #noParameterIsUnannotatedOrClientControlledSort()} for the structural check that closes exactly
   * that gap, and {@link #forbiddenSubstringsCoverAllDeclaredParameterNames()} for why the handlers
   * are collected by path rather than from a hardcoded list.
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
   * Closes the blind spot the substring check above cannot see on its own - an unannotated {@code
   * Pageable}/{@code Sort}/{@code @ModelAttribute} parameter binds from arbitrary query parameters
   * (including {@code sort=actorRef,desc}) without ever carrying an {@code @RequestParam} this
   * class could inspect by name. Every parameter on every HTTP handler method must therefore either
   * be one of the explicit, named binding annotations this controller already uses, or
   * {@code @AuthenticationPrincipal} - never left unannotated, and never typed {@link
   * Pageable}/{@link Sort} even if it were annotated.
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
   * The other half of the same finding - the handlers are collected by path, not from a list of
   * methods or controllers, so a new access path under {@code /api/v1/audit} is covered by the
   * checks above wherever its controller lives. This asserts that the collection still finds at
   * least the known revision access paths, so the checks cannot pass vacuously, and that the named
   * exclusion still matches something.
   */
  @Test
  void forbiddenSubstringsCoverAllDeclaredParameterNames() {
    List<String> methodNames = httpHandlerMethods().stream().map(Method::getName).toList();

    assertThat(methodNames)
        .as("the revision access paths under %s", AUDIT_PATH)
        .containsAll(KNOWN_REVISION_HANDLERS);
    assertThat(handlerMethodsUnder(DIAGNOSTIC_CONTEXT_PATH))
        .as("the excluded diagnostic protocol paths still exist")
        .isNotEmpty();
  }

  /**
   * Every HTTP-handler method of every controller in {@code io.opaa} mapped under {@link
   * #AUDIT_PATH}, except the diagnostic protocol under {@link #DIAGNOSTIC_CONTEXT_PATH}: its
   * purpose limitation is guarded by {@code DiagnosticContextPurposeLimitationTest}.
   */
  private static List<Method> httpHandlerMethods() {
    List<Method> diagnostic = handlerMethodsUnder(DIAGNOSTIC_CONTEXT_PATH);
    return handlerMethodsUnder(AUDIT_PATH).stream()
        .filter(method -> !diagnostic.contains(method))
        .toList();
  }

  private static List<Method> handlerMethodsUnder(String prefix) {
    HandlerPaths mapping = new HandlerPaths();
    List<Method> methods = new ArrayList<>();
    for (JavaClass javaClass : MainClasses.get()) {
      if (!javaClass.isAnnotatedWith(Controller.class)
          && !javaClass.isMetaAnnotatedWith(Controller.class)) {
        continue;
      }
      Class<?> controller = javaClass.reflect();
      for (Method method : controller.getMethods()) {
        RequestMappingInfo info = mapping.of(method, controller);
        if (info != null
            && info.getPatternValues().stream()
                .anyMatch(path -> path.equals(prefix) || path.startsWith(prefix + "/"))) {
          methods.add(method);
        }
      }
    }
    return methods;
  }

  /**
   * Resolves the mapping of a handler method as Spring MVC does, without an application context.
   */
  private static final class HandlerPaths extends RequestMappingHandlerMapping {
    HandlerPaths() {
      StaticWebApplicationContext context = new StaticWebApplicationContext();
      context.refresh();
      setApplicationContext(context);
      afterPropertiesSet();
    }

    RequestMappingInfo of(Method method, Class<?> controller) {
      return getMappingForMethod(method, controller);
    }
  }

  private void failIf(boolean condition, String message) {
    if (condition) {
      throw new AssertionError(message);
    }
  }
}
