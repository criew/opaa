package io.opaa.library;

import static io.opaa.library.LibraryCreationBuilder.libraryCreation;
import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.api.types.AssetOwnerType;
import io.opaa.api.types.SystemRole;
import io.opaa.asset.AssetVisibilityHistoryService;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.auth.CurrentUser;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.knowledge.KnowledgeLibraryRepository;
import io.opaa.knowledge.SourceType;
import io.opaa.organization.Organization;
import io.opaa.organization.OrganizationRepository;
import io.opaa.permission.AssetGrantHistoryRepository;
import io.opaa.test.OpaaIntegrationTest;
import io.opaa.test.OwnLibraryFixtures;
import io.opaa.test.TwoInstanceRace;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Two instances run the daily run of the Fremdzugangsfreigabe at the same moment (ADR-0053,
 * Entscheidung 7): the scheduled-run lock keeps that from happening, but a run outliving its lock
 * must not double what it writes. An expired release stands in the audit and in the visibility
 * history exactly once.
 */
@OpaaIntegrationTest
class LibraryExternalAccessTwoInstanceIntegrationTest {

  @Autowired private DataSource dataSource;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private LibraryExternalAccessService externalAccessService;
  @Autowired private KnowledgeLibraryService libraryService;
  @Autowired private KnowledgeLibraryRepository libraryRepository;
  @Autowired private AssetVisibilityHistoryService visibilityHistoryService;
  @Autowired private AuditEventRecorder auditEventRecorder;
  @Autowired private UserRepository userRepository;
  @Autowired private OrganizationRepository organizationRepository;
  @Autowired private AssetGrantHistoryRepository grantHistoryRepository;
  @Autowired private OwnLibraryFixtures ownLibraryFixtures;

  private UUID organizationId;
  private UUID ownerId;
  private UUID libraryId;

  @BeforeEach
  void setUp() {
    organizationId =
        organizationRepository.save(new Organization(UUID.randomUUID(), "Zwei Instanzen")).getId();
    User user = new User(UUID.randomUUID().toString(), "test-issuer", "zwei@example.com", "Zwei");
    user.setOrganizationId(organizationId);
    ownerId = userRepository.save(user).getId();
    libraryId =
        libraryService
            .createLibrary(
                libraryCreation("Bibliothek", SourceType.UPLOAD)
                    .ownerType(AssetOwnerType.USER)
                    .ownerId(ownerId)
                    .build(),
                owner())
            .library()
            .getId();
  }

  @AfterEach
  void tearDown() {
    ownLibraryFixtures.removeLibraries(libraryId);
    grantHistoryRepository.deleteBySubjectUserIdIn(List.of(ownerId));
    jdbcTemplate.update(
        "DELETE FROM asset_ownership_history WHERE organization_id = ?", organizationId);
    userRepository.deleteById(ownerId);
    jdbcTemplate.update("DELETE FROM audit_log WHERE organization_id = ?", organizationId);
    organizationRepository.deleteById(organizationId);
  }

  @Test
  void twoInstancesExpireAReleaseOnce() throws Exception {
    externalAccessService.setExternalAccess(
        owner(), libraryId, true, Instant.now().plus(30, ChronoUnit.DAYS));
    Instant afterwards = Instant.now().plus(31, ChronoUnit.DAYS);

    new TwoInstanceRace(dataSource)
        .run(
            () -> expiryServiceAt(afterwards).runOnce(),
            () -> expiryServiceAt(afterwards).runOnce(),
            "SELECT 1 FROM knowledge_libraries WHERE id = ? FOR UPDATE",
            libraryId);

    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE object_id = ?"
                    + " AND event_type = 'ASSET_EXTERNAL_ACCESS_EXPIRED'",
                Long.class,
                libraryId.toString()))
        .isEqualTo(1);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM asset_visibility_history WHERE asset_id = ?"
                    + " AND cause = 'EXTERNAL_ACCESS_EXPIRED'",
                Long.class,
                libraryId))
        .isEqualTo(1);
  }

  /** The expiry run with its clock past the Befristung, as a second instance would run it. */
  private LibraryExternalAccessExpiryService expiryServiceAt(Instant now) {
    TransactionTemplate transactions = new TransactionTemplate(transactionManager);
    // In a transaction like the bean's @Transactional: the run locks the rows it selects.
    return new LibraryExternalAccessExpiryService(
        libraryRepository, visibilityHistoryService, auditEventRecorder, () -> now) {
      @Override
      public int runOnce() {
        return transactions.execute(status -> super.runOnce());
      }
    };
  }

  private CurrentUser owner() {
    return CurrentUser.of(ownerId, organizationId, SystemRole.USER, "Zwei");
  }
}
