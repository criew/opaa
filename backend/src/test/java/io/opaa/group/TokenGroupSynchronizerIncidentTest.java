package io.opaa.group;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.auth.TokenGroups;
import io.opaa.auth.TokenGroups.Reason;
import io.opaa.auth.User;
import io.opaa.auth.oidc.OidcClaimMapping;
import io.opaa.auth.oidc.OidcProvider;
import io.opaa.library.PermissionHistoryService;
import io.opaa.organization.Organization;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.slf4j.LoggerFactory;

/**
 * The incident report of {@link TokenGroupSynchronizer} (#1807): a token that carried no usable
 * groups claim reaches no repository at all, and the reason is named once per provider per window -
 * a provider whose group mapper is gone sends every one of its accounts through here.
 */
class TokenGroupSynchronizerIncidentTest {

  private final GroupRepository groupRepository = mock(GroupRepository.class);
  private final GroupMembershipRepository membershipRepository =
      mock(GroupMembershipRepository.class);
  private final GroupMembershipResolver membershipResolver = mock(GroupMembershipResolver.class);
  private final PermissionHistoryService permissionHistoryService =
      mock(PermissionHistoryService.class);
  private final AuditEventRecorder auditEventRecorder = mock(AuditEventRecorder.class);
  private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

  private Logger logger;
  private TokenGroupSynchronizer synchronizer;

  @BeforeEach
  void setUp() {
    synchronizer =
        new TokenGroupSynchronizer(
            groupRepository,
            membershipRepository,
            membershipResolver,
            permissionHistoryService,
            auditEventRecorder);
    logger = (Logger) LoggerFactory.getLogger(TokenGroupSynchronizer.class);
    logs.start();
    logger.addAppender(logs);
  }

  @AfterEach
  void tearDown() {
    logger.detachAppender(logs);
  }

  private static User user() {
    User user = new User("sub1", "https://idp.example/realms/a", "a@x.example", "A");
    user.setOrganizationId(Organization.DEFAULT_ID);
    return user;
  }

  private static OidcProvider provider(String displayName) {
    return new OidcProvider(
        displayName,
        "https://idp.example/realms/" + displayName,
        "opaa-frontend",
        null,
        new OidcClaimMapping(null, null, null, null, null, "groups"));
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
    synchronizer.apply(user(), provider("Beschäftigte"), TokenGroups.unavailable(reason));

    assertThat(warnings())
        .singleElement()
        .asString()
        .contains("Beschäftigte")
        .contains(reason.description())
        .contains("left unchanged");
    verifyNoInteractions(
        groupRepository, membershipRepository, permissionHistoryService, auditEventRecorder);
  }

  @Test
  void aSecondIncidentOfTheSameProviderIsSuppressed() {
    OidcProvider provider = provider("Beschäftigte");

    synchronizer.apply(user(), provider, TokenGroups.unavailable(Reason.CLAIM_MISSING));
    synchronizer.apply(user(), provider, TokenGroups.unavailable(Reason.CLAIM_MISSING));
    synchronizer.apply(user(), provider, TokenGroups.unavailable(Reason.CLAIM_OVERAGE));

    assertThat(warnings()).hasSize(1);
  }

  @Test
  void anotherProviderIsReportedOnItsOwn() {
    synchronizer.apply(
        user(), provider("Beschäftigte"), TokenGroups.unavailable(Reason.CLAIM_MISSING));
    synchronizer.apply(user(), provider("Partner"), TokenGroups.unavailable(Reason.CLAIM_MISSING));

    assertThat(warnings()).hasSize(2);
  }

  /** The claim named groups, but none this installation can hold: no revocation, one report. */
  @Test
  void aClaimOfOnlyUnusableNamesIsReportedAndWritesNothing() {
    String overlong = "x".repeat(TokenGroupSynchronizer.MAX_NAME_LENGTH + 1);

    synchronizer.apply(user(), provider("Beschäftigte"), TokenGroups.named(List.of(overlong, " ")));

    assertThat(warnings()).last().asString().contains("left unchanged");
    verifyNoInteractions(
        groupRepository, membershipRepository, permissionHistoryService, auditEventRecorder);
  }
}
