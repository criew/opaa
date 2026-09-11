package io.opaa.group.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;

import io.opaa.api.types.DirectorySyncOutcome;
import io.opaa.api.types.GroupKind;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.group.Group;
import io.opaa.group.GroupMembershipHistoryRepository;
import io.opaa.group.GroupRepository;
import io.opaa.organization.Organization;
import io.opaa.test.FakeDirectoryClient;
import io.opaa.test.OpaaIntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Stubs the shared {@code DirectorySyncStatusRecorder} spy of {@link
 * io.opaa.test.OpaaIntegrationTest} to fail. The spy delegates to the real recorder for every other
 * class of the context, and Spring resets it after each test method, so the simulated failure never
 * outlives this class.
 *
 * <p>Covers review of PR #297's nit: a failure while recording the outcome (here simulated; in
 * production e.g. the status table's own insert/update failing) must not turn an already
 * successful, already-committed apply into an error response with no report at all - that would
 * additionally invite an operator to retry a run that already took effect.
 */
@OpaaIntegrationTest
class DirectorySyncServiceStatusFailureTest {

  @Autowired private DirectorySyncService directorySyncService;
  @Autowired private GroupRepository groupRepository;
  @Autowired private GroupMembershipHistoryRepository membershipHistoryRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private FakeDirectoryClient directoryClient;
  @Autowired private io.opaa.auth.AuthProperties authProperties;
  @Autowired private DirectorySyncStatusRecorder statusRecorder;

  private UUID organizationId;

  private final List<UUID> createdUserIds = new ArrayList<>();

  @BeforeEach
  void setUp() {
    organizationId = Organization.DEFAULT_ID;
    directoryClient.respondWith();
    doThrow(new RuntimeException("simulated status write failure"))
        .when(statusRecorder)
        .record(any(), any(), any(), anyString(), anyDouble());
  }

  // Only what this class created: the suite shares one database, and the dev auth filter
  // provisions users with personal spaces into the same organization, which an unscoped
  // deleteAll() cannot remove (fk_spaces_owner_organization).
  @AfterEach
  void tearDown() {
    groupRepository.deleteAll(groupRepository.findByOrganizationId(organizationId));
    // group_membership_history.user_id is ON DELETE RESTRICT - history before users.
    membershipHistoryRepository.deleteByUserIdIn(createdUserIds);
    userRepository.deleteAllById(createdUserIds);
    createdUserIds.clear();
  }

  private UUID createUser(String subject) {
    User user =
        new User(subject, authProperties.dev().issuer(), subject + "@example.com", "Test User");
    user.setOrganizationId(organizationId);
    UUID id = userRepository.save(user).getId();
    createdUserIds.add(id);
    return id;
  }

  @Test
  void aStatusWriteFailureDoesNotSwallowAnAlreadyAppliedReport() {
    UUID member = createUser("member-1");
    directoryClient.respondWith(
        new DirectoryGroup("dir-guid-9", "Referat 99", null, Set.of("member-1")));

    SyncReport report = directorySyncService.run(organizationId);

    assertThat(report.outcome()).isEqualTo(DirectorySyncOutcome.APPLIED);
    // The group/membership change is real and committed, regardless of the status write failure.
    List<Group> groups = groupRepository.findByOrganizationId(organizationId);
    assertThat(groups).hasSize(1);
    assertThat(groups.get(0).getKind()).isEqualTo(GroupKind.ORG_UNIT);
    assertThat(member).isNotNull();
  }
}
