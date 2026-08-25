package io.opaa.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.api.types.AssetRole;
import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.LibraryVisibility;
import io.opaa.api.types.PermissionSubjectType;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.library.AssetGrant;
import io.opaa.library.AssetGrantHistoryRepository;
import io.opaa.library.AssetGrantService;
import io.opaa.library.GrantChanged;
import io.opaa.library.KnowledgeLibrary;
import io.opaa.library.KnowledgeLibraryService;
import io.opaa.library.LibraryChanged;
import io.opaa.library.LibraryVisibilityHistoryRepository;
import io.opaa.library.PermissionHistoryService;
import io.opaa.organization.Organization;
import io.opaa.organization.OrganizationRepository;
import io.opaa.test.OpaaIntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallbackWithoutResult;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Proves {@link GrantChanged}/{@link LibraryChanged}'s core structural guarantee (#892): publishing
 * ONE event produces BOTH the {@link PermissionHistoryService} interval and the audit entry - the
 * double bookkeeping the two hand-paired calls these events replace used to require callers to
 * remember - and a rollback of the publishing transaction removes both together, exactly like a
 * single write would. Runs against a real Postgres database with the real, versioned Liquibase
 * schema applied ({@code spring.liquibase.enabled=true}, {@code ddl-auto=none}) and the real Spring
 * {@link ApplicationEventPublisher}/{@code @EventListener} wiring - {@code AuditListener} and
 * {@code PermissionHistoryListener} (both package-private in {@code io.opaa.library}) are real
 * beans here, not mocked away, since the whole point is to exercise both listeners actually
 * running.
 *
 * <p>Publishes the events directly rather than through {@link AssetGrantService}/{@link
 * KnowledgeLibraryService} - those services' own field-identical behaviour is already pinned by
 * {@code AuditEventRecordingIntegrationTest} and their own unit tests; this class isolates the
 * event/listener wiring itself.
 */
@OpaaIntegrationTest
class GrantLibraryChangedEventIntegrationTest {

  @Autowired private ApplicationEventPublisher eventPublisher;
  @Autowired private AssetGrantHistoryRepository grantHistoryRepository;
  @Autowired private LibraryVisibilityHistoryRepository visibilityHistoryRepository;
  @Autowired private AuditLogRepository auditLogRepository;
  @Autowired private OrganizationRepository organizationRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private JdbcTemplate jdbcTemplate;

  private UUID organizationId;
  private UUID actorUserId;
  private TransactionTemplate transactionTemplate;
  private final List<UUID> createdLibraryIds = new ArrayList<>();

  @BeforeEach
  void setUp() {
    createdLibraryIds.clear();
    organizationId =
        organizationRepository.save(new Organization(UUID.randomUUID(), "Event Test Org")).getId();
    User actor =
        new User(UUID.randomUUID().toString(), "test-issuer", "actor@example.com", "Actor");
    actor.setOrganizationId(organizationId);
    actorUserId = userRepository.save(actor).getId();
    transactionTemplate = new TransactionTemplate(transactionManager);
  }

  @AfterEach
  void tearDown() {
    // None of this test's libraries is ever persisted to knowledge_libraries (the events are
    // published directly - see the class Javadoc), so the history/audit rows below reference a
    // library_id/object_id with no row backing it, and no FK ties them to createdLibraryIds
    // either (library_id carries no foreign key at all, per PermissionHistoryService's Javadoc) -
    // they must be found and removed by that id explicitly, the same client-side-filter pattern
    // AuditEventRecordingIntegrationTest's own teardown uses for ownLibraryIds. Every grant this
    // class writes targets a GROUP subject (see newGrant), never a USER one, so
    // deleteBySubjectUserIdIn would filter out none of these rows - subject-based cleanup does not
    // apply here.
    grantHistoryRepository.deleteAll(
        grantHistoryRepository.findAll().stream()
            .filter(h -> createdLibraryIds.contains(h.getLibraryId()))
            .toList());
    visibilityHistoryRepository.deleteAll(
        visibilityHistoryRepository.findAll().stream()
            .filter(v -> createdLibraryIds.contains(v.getLibraryId()))
            .toList());
    // audit_log is insert-only at the application layer (see AuditLogServiceIntegrationTest's
    // teardown), so it needs the same JdbcTemplate cleanup before fk_audit_log_organization (ON
    // DELETE RESTRICT) allows the organization itself to go.
    jdbcTemplate.update("DELETE FROM audit_log WHERE organization_id = ?", organizationId);
    userRepository.deleteById(actorUserId);
    organizationRepository.deleteById(organizationId);
  }

  private KnowledgeLibrary newLibrary() {
    KnowledgeLibrary library =
        KnowledgeLibrary.ownedByUser(
            organizationId, "Bibliothek", null, actorUserId, LibraryVisibility.PRIVATE, false);
    createdLibraryIds.add(library.getId());
    return library;
  }

  private AssetGrant newGrant(KnowledgeLibrary library, UUID subjectGroupId) {
    return AssetGrant.forGroup(
        library.getId(), organizationId, subjectGroupId, AssetRole.MANAGER, null, actorUserId);
  }

  @Test
  void publishingAGrantChangedEventWritesBothTheHistoryIntervalAndTheAuditEntry() {
    KnowledgeLibrary library = newLibrary();
    UUID subjectGroupId = UUID.randomUUID();
    AssetGrant grant = newGrant(library, subjectGroupId);

    eventPublisher.publishEvent(
        new GrantChanged(
            library,
            grant,
            GrantChanged.Cause.GRANTED,
            actorUserId,
            null,
            Map.of("role", "MANAGER")));

    assertThat(
            grantHistoryRepository.findByLibraryIdAndSubjectTypeAndSubjectGroupIdAndValidToIsNull(
                library.getId(), PermissionSubjectType.GROUP, subjectGroupId))
        .isPresent();
    assertThat(
            auditLogRepository.findAll().stream()
                .filter(e -> e.getObjectId().equals(library.getId().toString()))
                .filter(e -> e.getEventType() == AuditEventType.ASSET_GRANT_GRANTED)
                .toList())
        .hasSize(1);
  }

  @Test
  void rollingBackTheTransactionRemovesBothTheHistoryIntervalAndTheAuditEntry() {
    KnowledgeLibrary library = newLibrary();
    UUID subjectGroupId = UUID.randomUUID();
    AssetGrant grant = newGrant(library, subjectGroupId);

    assertThatThrownBy(
            () ->
                transactionTemplate.execute(
                    new TransactionCallbackWithoutResult() {
                      @Override
                      protected void doInTransactionWithoutResult(TransactionStatus status) {
                        eventPublisher.publishEvent(
                            new GrantChanged(
                                library,
                                grant,
                                GrantChanged.Cause.GRANTED,
                                actorUserId,
                                null,
                                Map.of("role", "MANAGER")));
                        throw new RuntimeException(
                            "simulated failure after the event was published");
                      }
                    }))
        .isInstanceOf(RuntimeException.class);

    assertThat(
            grantHistoryRepository.findByLibraryIdAndSubjectTypeAndSubjectGroupIdAndValidToIsNull(
                library.getId(), PermissionSubjectType.GROUP, subjectGroupId))
        .isEmpty();
    assertThat(
            auditLogRepository.findAll().stream()
                .filter(e -> e.getObjectId().equals(library.getId().toString()))
                .toList())
        .isEmpty();
  }

  @Test
  void publishingALibraryChangedEventWritesBothTheHistoryIntervalAndTheAuditEntry() {
    KnowledgeLibrary library = newLibrary();

    eventPublisher.publishEvent(
        new LibraryChanged(
            library,
            LibraryChanged.Cause.CREATED,
            actorUserId,
            null,
            Map.of("name", library.getName())));

    assertThat(visibilityHistoryRepository.findByLibraryIdAndValidToIsNull(library.getId()))
        .isPresent();
    assertThat(
            auditLogRepository.findAll().stream()
                .filter(e -> e.getObjectId().equals(library.getId().toString()))
                .filter(e -> e.getEventType() == AuditEventType.LIBRARY_CREATED)
                .toList())
        .hasSize(1);
  }

  @Test
  void rollingBackALibraryChangedEventRemovesBothTheHistoryIntervalAndTheAuditEntry() {
    KnowledgeLibrary library = newLibrary();

    assertThatThrownBy(
            () ->
                transactionTemplate.execute(
                    new TransactionCallbackWithoutResult() {
                      @Override
                      protected void doInTransactionWithoutResult(TransactionStatus status) {
                        eventPublisher.publishEvent(
                            new LibraryChanged(
                                library,
                                LibraryChanged.Cause.CREATED,
                                actorUserId,
                                null,
                                Map.of("name", library.getName())));
                        throw new RuntimeException(
                            "simulated failure after the event was published");
                      }
                    }))
        .isInstanceOf(RuntimeException.class);

    assertThat(visibilityHistoryRepository.findByLibraryIdAndValidToIsNull(library.getId()))
        .isEmpty();
    assertThat(
            auditLogRepository.findAll().stream()
                .filter(e -> e.getObjectId().equals(library.getId().toString()))
                .toList())
        .isEmpty();
  }
}
