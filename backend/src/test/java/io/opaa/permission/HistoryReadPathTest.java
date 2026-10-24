package io.opaa.permission;

import static com.tngtech.archunit.base.DescribedPredicate.describe;
import static com.tngtech.archunit.core.domain.JavaCall.Predicates.target;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import io.opaa.architecture.MainClasses;
import java.time.Instant;
import java.util.Collection;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The ownership and account-state histories have exactly one reader: the Stichtagsauskunft about
 * one named object (ADR-0036, Entscheidung 8 - "kein Verlauf am Konto neben der
 * Stichtagsauskunft"). The read methods are public only because the auskunft lives in another
 * package; any further caller would be the second reading path the decision rules out.
 */
class HistoryReadPathTest {

  private static final String READER = "io.opaa.revision.PointInTimeAccessService";

  private static JavaClasses mainClasses;

  @BeforeAll
  static void importMainClasses() {
    mainClasses = MainClasses.get();
  }

  @Test
  void onlyTheStichtagsauskunftReadsTheLockedIntervalsOfAnAccount() {
    assertThat(
            noClasses()
                .that()
                .doNotHaveFullyQualifiedName(READER)
                .should()
                .callMethod(
                    AccountStateHistoryService.class,
                    "lockedIntervalsBetween",
                    Collection.class,
                    UUID.class,
                    Instant.class,
                    Instant.class,
                    int.class)
                .evaluate(mainClasses)
                .getFailureReport()
                .getDetails())
        .isEmpty();
  }

  @Test
  void onlyTheStichtagsauskunftReadsTheOwnershipIntervalsOfAnAsset() {
    assertThat(
            noClasses()
                .that()
                .doNotHaveFullyQualifiedName(READER)
                .should()
                .callMethod(
                    AssetOwnershipHistoryService.class,
                    "ownershipIntervalsBetween",
                    AssetType.class,
                    UUID.class,
                    UUID.class,
                    Instant.class,
                    Instant.class,
                    int.class)
                .evaluate(mainClasses)
                .getFailureReport()
                .getDetails())
        .isEmpty();
  }

  @Test
  void theLockedIntervalQueryIsReachedOnlyThroughItsService() {
    assertThat(
            noClasses()
                .that()
                .doNotHaveFullyQualifiedName(AccountStateHistoryService.class.getName())
                .should()
                .callMethodWhere(
                    target(
                        describe(
                            "AccountStateHistoryRepository.findLockedIntervalsOverlapping",
                            target ->
                                target
                                        .getOwner()
                                        .isAssignableTo(AccountStateHistoryRepository.class)
                                    && target.getName().equals("findLockedIntervalsOverlapping"))))
                .evaluate(mainClasses)
                .getFailureReport()
                .getDetails())
        .isEmpty();
  }

  @Test
  void theOwnershipIntervalQueryIsReachedOnlyThroughItsService() {
    assertThat(
            noClasses()
                .that()
                .doNotHaveFullyQualifiedName(AssetOwnershipHistoryService.class.getName())
                .should()
                .callMethodWhere(
                    target(
                        describe(
                            "AssetOwnershipHistoryRepository.findAssetIntervalsOverlapping",
                            target ->
                                target
                                        .getOwner()
                                        .isAssignableTo(AssetOwnershipHistoryRepository.class)
                                    && target.getName().equals("findAssetIntervalsOverlapping"))))
                .evaluate(mainClasses)
                .getFailureReport()
                .getDetails())
        .isEmpty();
  }
}
