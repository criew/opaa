package io.opaa.audit.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditObjectType;
import io.opaa.api.types.AuditOutcome;
import io.opaa.api.types.AuditSubjectKind;
import io.opaa.api.types.SystemRole;
import io.opaa.audit.AuditActorPseudonymService;
import io.opaa.audit.AuditEvent;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.auth.DevAuthFilter;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.organization.Organization;
import io.opaa.test.OpaaIntegrationTest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * The linking paths of #3352 through the real endpoint, the real recorder and a real database:
 * overlapping answers share event ids, so a reference to a person in them - even one valid for a
 * single answer - could be equated from answer to answer. No answer of a general access path
 * carries one: neither the stored pseudonym nor the account id nor any other per-person value; the
 * rights change that names her (what the Stichtagsauskunft would line up with) shows only that a
 * person was affected.
 */
@OpaaIntegrationTest
class AuditRevisionPersonReferenceIntegrationTest {

  private static final String REASON = "Revision der Bibliotheksrechte Q4";

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository userRepository;
  @Autowired private AuditEventRecorder recorder;
  @Autowired private AuditActorPseudonymService pseudonyms;
  @Autowired private JdbcTemplate jdbcTemplate;

  private final List<UUID> createdUsers = new ArrayList<>();
  private final UUID library = UUID.randomUUID();
  private final UUID otherLibrary = UUID.randomUUID();
  private final Instant from = Instant.now().minus(1, ChronoUnit.HOURS);
  private final Instant to = Instant.now().plus(1, ChronoUnit.HOURS);
  private UUID person;
  private UUID admin;

  @BeforeEach
  void setUp() throws Exception {
    person = createUser();
    admin = createUser();
    mockMvc.perform(get("/api/v1/spaces").with(devUser())).andExpect(status().isOk());
    jdbcTemplate.update(
        "UPDATE users SET system_role = 'AUDITOR' WHERE email = 'dev-user@opaa.local'");
  }

  @AfterEach
  void tearDown() {
    for (UUID objectId : List.of(library, otherLibrary)) {
      jdbcTemplate.update("DELETE FROM audit_log WHERE object_id = ?", objectId.toString());
      jdbcTemplate.update(
          "DELETE FROM audit_log WHERE event_type = 'AUDIT_LOG_ACCESSED' AND after LIKE ?",
          "%" + objectId + "%");
    }
    jdbcTemplate.update(
        "UPDATE users SET system_role = 'USER' WHERE email = 'dev-user@opaa.local'");
    createdUsers.forEach(userRepository::deleteById);
  }

  @Test
  void overlappingAnswersNameNoPersonToJoinOn() throws Exception {
    recorder.recordUserActionOnSubject(
        AuditEvent.builder()
            .organizationId(Organization.DEFAULT_ID)
            .actor(admin)
            .type(AuditEventType.ASSET_GRANT_GRANTED)
            .object(AuditObjectType.KNOWLEDGE_LIBRARY, library, "Bibliothek")
            .subject(AuditSubjectKind.USER, person)
            .outcome(AuditOutcome.SUCCESS)
            .build());
    recorder.recordUserAction(changeBy(person, library));
    recorder.recordUserAction(changeBy(person, otherLibrary));
    List<String> personal =
        List.of(
            pseudonyms.findExistingPseudonym(person).orElseThrow().toString(),
            pseudonyms.findExistingPseudonym(admin).orElseThrow().toString(),
            person.toString(),
            admin.toString());

    String first = byObject(library);
    String overlapping = byObject(library);
    String other = byObject(otherLibrary);

    assertThat(JsonPath.<List<String>>read(first, "$.events[*].eventId"))
        .as("the two answers overlap")
        .isEqualTo(JsonPath.read(overlapping, "$.events[*].eventId"));
    for (String answer : List.of(first, overlapping, other)) {
      for (String value : personal) {
        assertThat(answer).doesNotContain(value);
      }
      List<Map<String, Object>> events = JsonPath.read(answer, "$.events");
      assertThat(events).isNotEmpty();
      for (Map<String, Object> event : events) {
        assertThat(event.get("actorKind")).isEqualTo("USER");
        assertThat(event.get("actorRef")).isNull();
        assertThat(event.get("subjectRef")).isNull();
      }
    }
    assertThat(JsonPath.<List<String>>read(first, "$.events[*].subjectKind")).contains("USER");
  }

  private String byObject(UUID objectId) throws Exception {
    return mockMvc
        .perform(
            get("/api/v1/audit/events/by-object")
                .with(devUser())
                .param("objectType", "KNOWLEDGE_LIBRARY")
                .param("objectId", objectId.toString())
                .param("from", from.toString())
                .param("to", to.toString())
                .param("reason", REASON))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  private AuditEvent changeBy(UUID actor, UUID objectId) {
    return AuditEvent.builder()
        .organizationId(Organization.DEFAULT_ID)
        .actor(actor)
        .type(AuditEventType.LIBRARY_CHANGED)
        .object(AuditObjectType.KNOWLEDGE_LIBRARY, objectId, "Bibliothek")
        .outcome(AuditOutcome.SUCCESS)
        .build();
  }

  private UUID createUser() {
    User user =
        new User(UUID.randomUUID().toString(), "test-issuer", "person@example.com", "Person");
    user.setOrganizationId(Organization.DEFAULT_ID);
    user.setSystemRole(SystemRole.USER);
    UUID id = userRepository.save(user).getId();
    createdUsers.add(id);
    return id;
  }

  private static RequestPostProcessor devUser() {
    return request -> {
      request.addHeader(DevAuthFilter.DEV_USER_HEADER, "dev-user");
      return request;
    };
  }
}
