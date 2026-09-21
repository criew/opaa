package io.opaa.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.opaa.api.types.ChatRole;
import io.opaa.api.types.SpaceRole;
import io.opaa.api.types.SpaceVisibility;
import io.opaa.auth.DevAuthFilter;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.space.Space;
import io.opaa.space.SpaceMembership;
import io.opaa.space.SpaceRepository;
import io.opaa.test.OpaaIntegrationTest;
import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * The chat search term leaves no trace (docs/features/chat-list.md, "Datenschutz und
 * Personalvertretung"): a unique term goes through the whole HTTP path - a hit, a refusal for a
 * non-member and a validation error - with every logger that is not pinned in application.yml at
 * TRACE, and appears afterwards in no log line, no audit row and no metric.
 */
@OpaaIntegrationTest
class ChatSearchPrivacyIntegrationTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository users;
  @Autowired private SpaceRepository spaceRepository;
  @Autowired private ChatService chatService;
  @Autowired private ChatMessageRepository chatMessageRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private MeterRegistry meterRegistry;

  private UUID spaceId;
  private String term;

  @BeforeEach
  void setUp() throws Exception {
    mockMvc.perform(get("/api/v1/auth/me").with(devUser("dev-user"))).andExpect(status().isOk());
    mockMvc.perform(get("/api/v1/auth/me").with(devUser("dev-admin"))).andExpect(status().isOk());
    User person = users.findBySubjectAndIssuer("dev-user", "opaa-dev").orElseThrow();
    Space space =
        new Space(
            "Chatsuche Protokoll",
            null,
            false,
            SpaceVisibility.PRIVATE,
            person.getId(),
            person.getOrganizationId());
    space.addMembership(
        SpaceMembership.ofUser(person.getId(), SpaceRole.ADMIN, person.getOrganizationId()));
    spaceId = spaceRepository.save(space).getId();
    // Letters only, so the German analysis keeps it as one lexeme that no other text contains.
    term =
        "Protokollprobe"
            + UUID.randomUUID().toString().replaceAll("[^a-f]", "").toLowerCase(Locale.ROOT);
    UUID chatId =
        chatService.createChat(spaceId, person.getId(), new ChatCreation().title("Probe")).getId();
    chatMessageRepository.save(
        new ChatMessage(chatId, 0, ChatRole.USER, "Frage zu " + term + " bitte", null));
  }

  @AfterEach
  void tearDown() {
    if (spaceId != null) {
      jdbcTemplate.update("DELETE FROM chats WHERE space_id = ?", spaceId);
      jdbcTemplate.update("DELETE FROM spaces WHERE id = ?", spaceId);
    }
  }

  @Test
  void theTermAppearsInNoLogLineAuditRowOrMetric() throws Exception {
    Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    Level previousRootLevel = root.getLevel();
    root.setLevel(Level.TRACE);
    root.addAppender(appender);
    try {
      mockMvc
          .perform(search("dev-user", "{\"query\":\"" + term + "\"}"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.hits.length()").value(1));
      mockMvc
          .perform(search("dev-admin", "{\"query\":\"" + term + "\"}"))
          .andExpect(status().isForbidden());
      mockMvc
          .perform(search("dev-user", "{\"query\":\"" + term + "\",\"page\":-1}"))
          .andExpect(status().isBadRequest());
    } finally {
      root.detachAppender(appender);
      root.setLevel(previousRootLevel);
    }

    assertThat(appender.list).as("the capture must have seen the requests").isNotEmpty();
    for (ILoggingEvent event : appender.list) {
      assertThat(event.getFormattedMessage()).doesNotContainIgnoringCase(term);
      if (event.getArgumentArray() != null) {
        assertThat(Arrays.toString(event.getArgumentArray())).doesNotContainIgnoringCase(term);
      }
      for (IThrowableProxy cause = event.getThrowableProxy();
          cause != null;
          cause = cause.getCause()) {
        assertThat(String.valueOf(cause.getMessage())).doesNotContainIgnoringCase(term);
      }
    }
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM audit_log a WHERE lower(CAST(row_to_json(a) AS text)) LIKE ?",
                Integer.class,
                "%" + term.toLowerCase(Locale.ROOT) + "%"))
        .isZero();
    for (Meter meter : meterRegistry.getMeters()) {
      assertThat(meter.getId().getName()).doesNotContainIgnoringCase(term);
      for (Tag tag : meter.getId().getTags()) {
        assertThat(tag.getValue()).doesNotContainIgnoringCase(term);
      }
    }
    assertThat(meterRegistry.find("opaa.chat.search.duration").timer()).isNotNull();
    assertThat(meterRegistry.find("opaa.chat.search.duration").timer().getId().getTags()).isEmpty();
  }

  private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder search(
      String subject, String body) {
    return post("/api/v1/spaces/{spaceId}/chats/search", spaceId)
        .with(devUser(subject))
        .contentType(MediaType.APPLICATION_JSON)
        .content(body);
  }

  private static RequestPostProcessor devUser(String subject) {
    return request -> {
      request.addHeader(DevAuthFilter.DEV_USER_HEADER, subject);
      return request;
    };
  }
}
