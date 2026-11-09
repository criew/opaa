package io.opaa.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.opaa.compliance.SelfDisclosureSubject;
import io.opaa.test.CapturingSelfDisclosureSink;
import io.opaa.test.OpaaIntegrationTest;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * The marker "hint of the quick tour done" against Postgres: every account starts at {@code false},
 * the person sets it for her own account only and idempotently, setting it changes no other column
 * and leaves no audit entry, no metric and no log line about the person, a later sign-in through an
 * identity provider never resets it, and the person's own self-disclosure carries it. Works on the
 * two development accounts and its own provider accounts and puts back what it changed.
 */
@OpaaIntegrationTest
class QuickTourHintIntegrationTest {

  private static final String HINT = "/api/v1/me/quick-tour-hint";
  private static final String ME = "/api/v1/auth/me";
  private static final String ISSUER = "https://idp.example/realms/rundgang";

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private UserRepository users;
  @Autowired private UserService userService;
  @Autowired private QuickTourHint quickTourHint;
  @Autowired private AccountSelfDisclosureExporter exporter;
  @Autowired private MeterRegistry meterRegistry;

  private final String providerSubject = "rundgang-" + UUID.randomUUID();
  private User person;
  private User colleague;

  @BeforeEach
  void setUp() throws Exception {
    mockMvc.perform(get(ME).with(dev("dev-user"))).andExpect(status().isOk());
    mockMvc.perform(get(ME).with(dev("dev-admin"))).andExpect(status().isOk());
    person = users.findBySubjectAndIssuer("dev-user", "opaa-dev").orElseThrow();
    colleague = users.findBySubjectAndIssuer("dev-admin", "opaa-dev").orElseThrow();
    resetMarkers();
  }

  @AfterEach
  void tearDown() {
    resetMarkers();
    jdbc.update(
        "DELETE FROM spaces WHERE owner_id IN (SELECT id FROM users WHERE subject = ?)",
        providerSubject);
    jdbc.update(
        "DELETE FROM space_membership_history WHERE subject_user_id IN"
            + " (SELECT id FROM users WHERE subject = ?)",
        providerSubject);
    jdbc.update(
        "DELETE FROM asset_ownership_history WHERE owner_user_id IN"
            + " (SELECT id FROM users WHERE subject = ?)",
        providerSubject);
    jdbc.update("DELETE FROM users WHERE subject = ?", providerSubject);
  }

  private void resetMarkers() {
    for (User user : List.of(person, colleague)) {
      jdbc.update("UPDATE users SET quick_tour_hint_done = false WHERE id = ?", user.getId());
    }
  }

  @Test
  void thePersonMarksTheHintDoneForHerOwnAccountOnlyAndIdempotently() throws Exception {
    mockMvc
        .perform(get(ME).with(dev("dev-user")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.quickTourHintDone").value(false));

    mockMvc.perform(put(HINT).with(dev("dev-user"))).andExpect(status().isNoContent());
    mockMvc.perform(put(HINT).with(dev("dev-user"))).andExpect(status().isNoContent());

    mockMvc
        .perform(get(ME).with(dev("dev-user")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.quickTourHintDone").value(true));
    mockMvc
        .perform(get(ME).with(dev("dev-admin")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.quickTourHintDone").value(false));
    assertThat(quickTourHint.isDone(colleague.getId())).isFalse();
  }

  @Test
  void settingTheMarkerChangesNoOtherColumnOfTheAccount() {
    String before = rowWithoutMarker(person.getId());

    quickTourHint.markDone(person.getId());
    quickTourHint.markDone(person.getId());

    assertThat(quickTourHint.isDone(person.getId())).isTrue();
    assertThat(rowWithoutMarker(person.getId())).isEqualTo(before);
  }

  @Test
  void settingTheMarkerLeavesNoAuditEntryNoMetricAndNoLogLineAboutThePerson() throws Exception {
    long auditEntriesBefore = auditEntries();
    Set<Meter.Id> metersBefore = meterIds();
    ListAppender<ILoggingEvent> logs = new ListAppender<>();
    Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
    Level previousLevel = root.getLevel();
    root.setLevel(Level.DEBUG);
    logs.start();
    root.addAppender(logs);
    try {
      mockMvc.perform(put(HINT).with(dev("dev-user"))).andExpect(status().isNoContent());
      mockMvc.perform(put(HINT).with(dev("dev-user"))).andExpect(status().isNoContent());
    } finally {
      root.detachAppender(logs);
      root.setLevel(previousLevel);
    }

    assertThat(quickTourHint.isDone(person.getId())).isTrue();
    assertThat(auditEntries()).isEqualTo(auditEntriesBefore);
    List<String> identifiers =
        Stream.of(
                person.getId().toString(),
                person.getSubject(),
                person.getIssuer(),
                person.getEmail(),
                person.getDisplayName())
            .filter(Objects::nonNull)
            .filter(value -> !value.isBlank())
            .toList();
    for (ILoggingEvent event : logs.list) {
      String line = event.getFormattedMessage();
      for (String identifier : identifiers) {
        assertThat(line).as("log line of %s", event.getLoggerName()).doesNotContain(identifier);
      }
      if (event.getLoggerName().startsWith("io.opaa")) {
        assertThat(line.toLowerCase(Locale.ROOT))
            .as("log line of %s", event.getLoggerName())
            .doesNotContain("quick")
            .doesNotContain("rundgang");
      }
    }
    for (Meter meter : meterRegistry.getMeters()) {
      Meter.Id id = meter.getId();
      assertThat(id.getTags().stream().map(Tag::getValue))
          .as("tags of meter %s", id.getName())
          .noneMatch(value -> identifiers.stream().anyMatch(value::contains));
      assertThat(id.getTag("uri")).as("uri tag of meter %s", id.getName()).isNotEqualTo(HINT);
      if (!metersBefore.contains(id)) {
        assertThat(id.getName().toLowerCase(Locale.ROOT))
            .doesNotContain("quick")
            .doesNotContain("tour");
      }
    }
  }

  /** The scrape a Prometheus reads, histogram buckets of the HTTP timer included. */
  @Test
  void theMarkerAppearsInNoSeriesOfTheScrapeHistogramBucketsIncluded() throws Exception {
    mockMvc.perform(put(HINT).with(dev("dev-user"))).andExpect(status().isNoContent());
    mockMvc.perform(get("/actuator/prometheus")).andExpect(status().isOk());

    String scrape =
        mockMvc
            .perform(get("/actuator/prometheus"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(scrape)
        .contains("http_server_requests_seconds_bucket{")
        .doesNotContain("quick-tour")
        .doesNotContain("quick_tour");
  }

  @Test
  void aSignInThroughAnIdentityProviderNeverResetsTheMarker() {
    User created =
        userService.findOrCreateUser(providerSubject, ISSUER, "rundgang@behoerde.example", "Ria");
    assertThat(quickTourHint.isDone(created.getId())).as("a new account").isFalse();

    quickTourHint.markDone(created.getId());
    // changed claims make the sign-in write the whole row again
    User signedInAgain =
        userService.findOrCreateUser(
            providerSubject, ISSUER, "ria.rundgang@behoerde.example", "Ria Rundgang");

    assertThat(signedInAgain.getId()).isEqualTo(created.getId());
    assertThat(signedInAgain.getEmail()).isEqualTo("ria.rundgang@behoerde.example");
    assertThat(quickTourHint.isDone(created.getId())).isTrue();
  }

  @Test
  void theOwnSelfDisclosureCarriesTheMarker() throws Exception {
    assertThat(exportOf(person).records("account"))
        .singleElement()
        .satisfies(record -> assertThat(record).containsEntry("quickTourHintDone", false));

    quickTourHint.markDone(person.getId());

    assertThat(exportOf(person).records("account"))
        .singleElement()
        .satisfies(record -> assertThat(record).containsEntry("quickTourHintDone", true));
  }

  private CapturingSelfDisclosureSink exportOf(User user) throws Exception {
    CapturingSelfDisclosureSink sink =
        new CapturingSelfDisclosureSink(AccountSelfDisclosureExporter.CATEGORY);
    exporter.export(new SelfDisclosureSubject(user.getId(), user.getOrganizationId()), sink);
    return sink;
  }

  private String rowWithoutMarker(UUID account) {
    return jdbc.queryForObject(
        "SELECT (to_jsonb(u) - 'quick_tour_hint_done')::text FROM users u WHERE id = ?",
        String.class,
        account);
  }

  private long auditEntries() {
    Long count = jdbc.queryForObject("SELECT count(*) FROM audit_log", Long.class);
    return count == null ? 0 : count;
  }

  private Set<Meter.Id> meterIds() {
    return meterRegistry.getMeters().stream().map(Meter::getId).collect(Collectors.toSet());
  }

  private static RequestPostProcessor dev(String devUser) {
    return request -> {
      request.addHeader(DevAuthFilter.DEV_USER_HEADER, devUser);
      return request;
    };
  }
}
