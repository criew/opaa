package io.opaa.succession;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.api.types.SuccessionKind;
import io.opaa.api.types.SuccessionObjectType;
import io.opaa.api.types.SystemRole;
import io.opaa.asset.AssetTypes;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.KnowledgeLibraryRepository;
import io.opaa.organization.Organization;
import io.opaa.organization.OrganizationRepository;
import io.opaa.permission.GroupMembershipResolver;
import io.opaa.permission.SuccessionFindingSource;
import io.opaa.test.OpaaIntegrationTest;
import io.opaa.test.OwnOrganizationFixtures;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A succession case is written by two parties that do not wait for each other: the detection run
 * and an operation that ends the state and names its actor. Whichever closes a case first decides
 * how it ended; nothing a party loaded before that close may reopen it or rewrite who ended it.
 *
 * <p>Each race is replayed deterministically on real transactions against the real schema: the
 * repository handed to the racing party runs the other party in its own committed transaction right
 * after the racing party has loaded the case.
 */
@OpaaIntegrationTest
class SuccessionCaseRaceIntegrationTest {

  @Autowired private SuccessionService successionService;
  @Autowired private SuccessionDetectionService detectionService;
  @Autowired private SuccessionCaseRepository cases;
  @Autowired private SuccessionAgingThreshold agingThreshold;
  @Autowired private List<SuccessionFindingSource> sources;
  @Autowired private OrganizationRepository organizations;
  @Autowired private AssetTypes assetTypes;
  @Autowired private Clock clock;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private KnowledgeLibraryRepository libraries;
  @Autowired private UserRepository users;
  @Autowired private GroupMembershipResolver membershipResolver;
  @Autowired private OwnOrganizationFixtures ownOrganizationFixtures;
  @Autowired private JdbcTemplate jdbcTemplate;

  private UUID organizationId;
  private UUID owner;
  private UUID library;
  private UUID closer;

  @BeforeEach
  void openASuccession() {
    organizationId =
        organizations
            .save(new Organization(UUID.randomUUID(), "Nachfolge-Wettlauf " + UUID.randomUUID()))
            .getId();
    owner = user();
    closer = user();
    library =
        libraries
            .save(
                KnowledgeLibrary.ownedByUser(
                    organizationId, "Bibliothek " + UUID.randomUUID(), null, owner))
            .getId();
    lock(owner);
    detectionService.runFor(organizationId);
    assertThat(openCase()).as("the run has recorded the open succession").isNotNull();
  }

  @AfterEach
  void tearDown() {
    jdbcTemplate.update("DELETE FROM succession_cases WHERE organization_id = ?", organizationId);
    ownOrganizationFixtures.removeOrganizations(organizationId);
  }

  /**
   * regression guard for #2910: the run has loaded the open case and still sees the state; a
   * transfer closes the case and names its actor; the run's later sighting must neither reopen the
   * case nor erase the actor.
   */
  @Test
  void aSightingAfterAConcurrentCloseKeepsTheCaseClosedAndItsActor() {
    UUID caseId = openCase().getId();
    SuccessionDetectionService racingRun =
        detectionWith(
            raceAfter(
                "findByKindAndObjectTypeAndObjectIdAndClosedAtIsNull",
                args -> library.equals(args[2]),
                this::transferEndsTheSuccession));

    racingRun.runFor(organizationId);

    SuccessionCase after = cases.findById(caseId).orElseThrow();
    assertThat(after.getClosedAt()).as("the concurrently closed case stays closed").isNotNull();
    assertThat(after.getClosedByUserId())
        .as("the person who ended the state stays named")
        .isEqualTo(closer);
  }

  /**
   * The run's own close: it has loaded the open cases after the state ended, a transfer closes the
   * case first and names its actor - the run's anonymous close must not overwrite that.
   */
  @Test
  void theRunsCloseAfterAConcurrentCloseKeepsTheActor() {
    UUID caseId = openCase().getId();
    unlock(owner);
    SuccessionDetectionService racingRun =
        detectionWith(
            raceAfter(
                "findByOrganizationIdAndClosedAtIsNull",
                args -> organizationId.equals(args[0]),
                () ->
                    successionService.closeForAsset(KnowledgeLibrary.ASSET_TYPE, library, closer)));

    SuccessionDetectionRun run = racingRun.runFor(organizationId);

    SuccessionCase after = cases.findById(caseId).orElseThrow();
    assertThat(after.getClosedAt()).isNotNull();
    assertThat(after.getClosedByUserId())
        .as("the person who ended the state stays named")
        .isEqualTo(closer);
    assertThat(run.closed()).as("the run closed nothing - the case was already closed").isZero();
  }

  /**
   * The transfer's close: it has loaded the open case, the run closes it first - the state had
   * already ended before the transfer, so the earlier close stands and is not rewritten.
   */
  @Test
  void aTransfersCloseAfterAConcurrentRunCloseLeavesTheEarlierClose() {
    UUID caseId = openCase().getId();
    unlock(owner);
    SuccessionService racingTransfer =
        new SuccessionService(
            sources,
            raceAfter(
                "findByKindAndObjectIdAndClosedAtIsNull",
                args -> library.equals(args[1]),
                () -> detectionService.runFor(organizationId)),
            agingThreshold,
            clock);

    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            status -> racingTransfer.closeForAsset(KnowledgeLibrary.ASSET_TYPE, library, closer));

    SuccessionCase after = cases.findById(caseId).orElseThrow();
    assertThat(after.getClosedAt()).isNotNull();
    assertThat(after.getClosedByUserId())
        .as("the run's earlier close names nobody, and a later close does not rewrite it")
        .isNull();
  }

  private void transferEndsTheSuccession() {
    unlock(owner);
    successionService.closeForAsset(KnowledgeLibrary.ASSET_TYPE, library, closer);
  }

  private SuccessionDetectionService detectionWith(SuccessionCaseRepository repository) {
    return new SuccessionDetectionService(
        sources, repository, organizations, clock, transactionManager, assetTypes);
  }

  /**
   * The real repository, except that the first matching call of {@code methodName} runs {@code
   * concurrently} in its own, committed transaction after the call has returned its result.
   */
  private SuccessionCaseRepository raceAfter(
      String methodName, Predicate<Object[]> matches, Runnable concurrently) {
    TransactionTemplate ownTransaction = new TransactionTemplate(transactionManager);
    ownTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    AtomicBoolean raced = new AtomicBoolean();
    return (SuccessionCaseRepository)
        Proxy.newProxyInstance(
            SuccessionCaseRepository.class.getClassLoader(),
            new Class<?>[] {SuccessionCaseRepository.class},
            (proxy, method, args) -> {
              Object result = invoke(method, args);
              if (method.getName().equals(methodName)
                  && matches.test(args)
                  && raced.compareAndSet(false, true)) {
                ownTransaction.executeWithoutResult(status -> concurrently.run());
              }
              return result;
            });
  }

  private Object invoke(Method method, Object[] args) throws Throwable {
    try {
      return method.invoke(cases, args);
    } catch (InvocationTargetException e) {
      throw e.getCause();
    }
  }

  private SuccessionCase openCase() {
    return cases
        .findByKindAndObjectTypeAndObjectIdAndClosedAtIsNull(
            SuccessionKind.OPEN_SUCCESSION, SuccessionObjectType.ASSET, library)
        .orElse(null);
  }

  private void lock(UUID userId) {
    jdbcTemplate.update("UPDATE users SET directory_locked_at = now() WHERE id = ?", userId);
  }

  private void unlock(UUID userId) {
    jdbcTemplate.update("UPDATE users SET directory_locked_at = NULL WHERE id = ?", userId);
  }

  private UUID user() {
    User user =
        new User(
            UUID.randomUUID().toString(),
            "https://issuer.example",
            UUID.randomUUID() + "@example.org",
            "Nachfolge");
    user.setOrganizationId(organizationId);
    user.setSystemRole(SystemRole.USER);
    UUID id = users.save(user).getId();
    membershipResolver.invalidateUser(id);
    return id;
  }
}
