package io.opaa.group.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;

import io.opaa.api.dto.DirectorySyncReportResponse;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.group.Group;
import io.opaa.group.GroupKind;
import io.opaa.group.GroupRepository;
import io.opaa.organization.Organization;
import io.opaa.test.OpaaIntegrationTest;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * A separate Spring context from {@link DirectorySyncServiceIntegrationTest} - not one more test
 * method added to it - because {@code @MockitoBean} replaces {@link DirectorySyncStatusRecorder}
 * for the whole class. Sharing it with the tests that rely on real status persistence would
 * silently turn every one of their {@code statusRecorder.record(...)} calls into a no-op instead of
 * the real write they assert on, breaking those tests for a reason that has nothing to do with what
 * they cover.
 *
 * <p>Covers review of PR #297's nit: a failure while recording the outcome (here simulated; in
 * production e.g. the status table's own insert/update failing) must not turn an already
 * successful, already-committed apply into an error response with no report at all - that would
 * additionally invite an operator to retry a run that already took effect.
 */
// Own @MockitoBean (below) and @Import registering a FakeDirectoryClient mean Spring's context
// cache still keys this to its own context regardless of the shared @OpaaIntegrationTest base -
// documented exception per AGENTS.md. Previously also declared its own duplicate Postgres
// container bean instead of reusing the shared TestcontainersConfiguration (issue #843) - removed,
// ServiceConnection now comes from @OpaaIntegrationTest's import.
@OpaaIntegrationTest
@Import(DirectorySyncServiceStatusFailureTest.TestConfig.class)
class DirectorySyncServiceStatusFailureTest {

  @TestConfiguration(proxyBeanMethods = false)
  static class TestConfig {
    @Bean
    @Primary
    DirectorySyncServiceIntegrationTest.FakeDirectoryClient fakeDirectoryClient() {
      return new DirectorySyncServiceIntegrationTest.FakeDirectoryClient();
    }
  }

  @Autowired private DirectorySyncService directorySyncService;
  @Autowired private GroupRepository groupRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private DirectorySyncServiceIntegrationTest.FakeDirectoryClient directoryClient;
  @MockitoBean private DirectorySyncStatusRecorder statusRecorder;

  private UUID organizationId;

  @BeforeEach
  void setUp() {
    groupRepository.deleteAll();
    userRepository.deleteAll();
    organizationId = Organization.DEFAULT_ID;
    directoryClient.respondWith();
    doThrow(new RuntimeException("simulated status write failure"))
        .when(statusRecorder)
        .record(any(), any(), any(), anyString(), anyDouble());
  }

  private UUID createUser(String subject) {
    User user = new User(subject, "test-issuer", subject + "@example.com", "Test User");
    user.setOrganizationId(organizationId);
    return userRepository.save(user).getId();
  }

  @Test
  void aStatusWriteFailureDoesNotSwallowAnAlreadyAppliedReport() {
    UUID member = createUser("member-1");
    directoryClient.respondWith(
        new DirectoryGroup("dir-guid-9", "Referat 99", null, Set.of("member-1")));

    DirectorySyncReportResponse report = directorySyncService.run(organizationId);

    assertThat(report.getOutcome()).isEqualTo(DirectorySyncOutcome.APPLIED);
    // The group/membership change is real and committed, regardless of the status write failure.
    List<Group> groups = groupRepository.findByOrganizationId(organizationId);
    assertThat(groups).hasSize(1);
    assertThat(groups.get(0).getKind()).isEqualTo(GroupKind.ORG_UNIT);
    assertThat(member).isNotNull();
  }
}
