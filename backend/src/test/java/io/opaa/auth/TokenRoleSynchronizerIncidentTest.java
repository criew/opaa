package io.opaa.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.opaa.api.types.SystemRole;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.auth.TokenRoles.Reason;
import io.opaa.auth.local.LocalAdminAvailabilityGuard;
import io.opaa.auth.oidc.OidcClaimMapping;
import io.opaa.auth.oidc.OidcProvider;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.slf4j.LoggerFactory;

/**
 * The incident report of {@link TokenRoleSynchronizer} (#1830): a token that carried no usable
 * roles claim reaches neither the repository nor the last-administrator guard, and the cause is
 * named once per provider and cause per window - a provider whose role mapper is gone sends every
 * one of its accounts through here.
 */
class TokenRoleSynchronizerIncidentTest {

  private final UserRepository userRepository = mock(UserRepository.class);
  private final LocalAdminAvailabilityGuard guard = mock(LocalAdminAvailabilityGuard.class);
  private final AuditEventRecorder auditEventRecorder = mock(AuditEventRecorder.class);
  private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

  private Logger logger;
  private TokenRoleSynchronizer synchronizer;

  @BeforeEach
  void setUp() {
    synchronizer = new TokenRoleSynchronizer(userRepository, guard, auditEventRecorder);
    logger = (Logger) LoggerFactory.getLogger(TokenRoleSynchronizer.class);
    logs.start();
    logger.addAppender(logs);
  }

  @AfterEach
  void tearDown() {
    logger.detachAppender(logs);
  }

  private static User admin() {
    User user = new User("sub1", "https://idp.example/realms/a", "a@x.example", "A");
    user.setOrganizationId(UUID.randomUUID());
    user.setSystemRole(SystemRole.SYSTEM_ADMIN);
    return user;
  }

  private static OidcProvider provider(String displayName) {
    return new OidcProvider(
        displayName,
        "https://idp.example/realms/" + displayName,
        "opaa-frontend",
        null,
        new OidcClaimMapping(null, null, "roles", "opaa-admin", "opaa-auditor", null));
  }

  private List<String> warnings() {
    return logs.list.stream()
        .filter(event -> event.getLevel() == Level.WARN)
        .map(ILoggingEvent::getFormattedMessage)
        .toList();
  }

  @ParameterizedTest
  @EnumSource(Reason.class)
  void everyReasonIsNamedWithItsProviderAndNothingIsWritten(Reason reason) {
    User user = admin();

    User result =
        synchronizer.apply(user, provider("Beschäftigte"), TokenRoles.unavailable(reason));

    assertThat(result).isSameAs(user);
    assertThat(result.getSystemRole()).isEqualTo(SystemRole.SYSTEM_ADMIN);
    assertThat(warnings())
        .singleElement()
        .asString()
        .contains("Beschäftigte")
        .contains(reason.description())
        .contains("left unchanged");
    verifyNoInteractions(userRepository, guard, auditEventRecorder);
  }

  /** Throttled per cause: a repetition is suppressed, a changed cause is news of its own. */
  @Test
  void aRepeatedCauseIsSuppressedAndAChangedOneIsNot() {
    OidcProvider provider = provider("Beschäftigte");

    synchronizer.apply(admin(), provider, TokenRoles.unavailable(Reason.CLAIM_MISSING));
    synchronizer.apply(admin(), provider, TokenRoles.unavailable(Reason.CLAIM_MISSING));
    synchronizer.apply(admin(), provider, TokenRoles.unavailable(Reason.CLAIM_OVERAGE));

    assertThat(warnings())
        .satisfiesExactly(
            first -> assertThat(first).contains(Reason.CLAIM_MISSING.description()),
            second -> assertThat(second).contains(Reason.CLAIM_OVERAGE.description()));
  }

  @Test
  void anotherProviderIsReportedOnItsOwn() {
    synchronizer.apply(
        admin(), provider("Beschäftigte"), TokenRoles.unavailable(Reason.CLAIM_MISSING));
    synchronizer.apply(admin(), provider("Partner"), TokenRoles.unavailable(Reason.CLAIM_MISSING));

    assertThat(warnings()).hasSize(2);
  }
}
