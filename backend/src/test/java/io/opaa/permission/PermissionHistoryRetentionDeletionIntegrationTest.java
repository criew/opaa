package io.opaa.permission;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.api.types.AssetRole;
import io.opaa.api.types.ExternalAccessState;
import io.opaa.api.types.LibraryVisibility;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.library.LibraryVisibilityHistory;
import io.opaa.library.LibraryVisibilityHistoryCause;
import io.opaa.library.LibraryVisibilityHistoryRepository;
import io.opaa.organization.Organization;
import io.opaa.test.OpaaIntegrationTest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The deletion pass against the real schema (#1833, ADR-0036 Entscheidung 8): what ages out, what
 * never does, and that a shortening of the period only takes effect going forward.
 *
 * <p>The pass itself is harmless to neighbouring classes: no other class writes a history row whose
 * interval ended more than a year ago, so nothing outside this class's own rows can fall inside any
 * configurable cutoff. {@code SeededRowRestorer} puts the settings row - including the deletion
 * progress advanced here - back after every method.
 */
@OpaaIntegrationTest
class PermissionHistoryRetentionDeletionIntegrationTest {

  /** An asset type only this class knows, so its grant rows are recognisable without a library. */
  private static final AssetType TEST_ASSET = AssetType.of("TEST_RETENTION");

  @Autowired private PermissionHistoryRetentionDeletionService deletionService;
  @Autowired private PermissionHistoryRetentionSettingsRepository settingsRepository;
  @Autowired private AssetGrantHistoryRepository grantHistoryRepository;
  @Autowired private GroupMembershipHistoryRepository membershipHistoryRepository;
  @Autowired private LibraryVisibilityHistoryRepository visibilityHistoryRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private TransactionTemplate transactionTemplate;
  @Autowired private PermissionHistoryService permissionHistoryService;
  @Autowired private PermissionHistoryRetentionService retentionService;

  private final List<UUID> grantRowIds = new ArrayList<>();
  private final List<UUID> membershipRowIds = new ArrayList<>();
  private final List<UUID> visibilityRowIds = new ArrayList<>();
  private final List<UUID> userIds = new ArrayList<>();

  private UUID memberUserId;

  @BeforeEach
  void setUp() {
    cleanUp();
    memberUserId = persistUser();
  }

  /** Only this class's own rows - the deletion pass has already removed some of them. */
  @AfterEach
  void cleanUp() {
    grantHistoryRepository.deleteAll(grantHistoryRepository.findAllById(grantRowIds));
    membershipHistoryRepository.deleteAll(
        membershipHistoryRepository.findAllById(membershipRowIds));
    visibilityHistoryRepository.deleteAll(
        visibilityHistoryRepository.findAllById(visibilityRowIds));
    userRepository.deleteAll(userRepository.findAllById(userIds));
    grantRowIds.clear();
    membershipRowIds.clear();
    visibilityRowIds.clear();
    userIds.clear();
  }

  @Test
  void expiredClosedIntervalsGoAndOpenOnesStay() {
    UUID agedGrant = closedGrantInterval(monthsAgo(48), monthsAgo(40));
    UUID freshGrant = closedGrantInterval(monthsAgo(6), monthsAgo(1));
    UUID openGrant = openGrantInterval(monthsAgo(60));
    UUID agedMembership = closedMembershipInterval(monthsAgo(48), monthsAgo(40));
    UUID openMembership = openMembershipInterval(monthsAgo(60));
    UUID agedVisibility = closedVisibilityInterval(monthsAgo(48), monthsAgo(40));
    UUID openVisibility = openVisibilityInterval(monthsAgo(60));

    PermissionHistoryRetentionRun run = deletionService.runOnce();

    assertThat(grantHistoryRepository.existsById(agedGrant)).isFalse();
    assertThat(grantHistoryRepository.existsById(freshGrant)).isTrue();
    assertThat(grantHistoryRepository.existsById(openGrant))
        .as("an open interval is a right in force - its validFrom may lie arbitrarily far back")
        .isTrue();
    assertThat(membershipHistoryRepository.existsById(agedMembership)).isFalse();
    assertThat(membershipHistoryRepository.existsById(openMembership)).isTrue();
    assertThat(visibilityHistoryRepository.existsById(agedVisibility)).isFalse();
    assertThat(visibilityHistoryRepository.existsById(openVisibility)).isTrue();
  }

  /**
   * The two boundary cases the half-open interval brings with it: a state that began before the
   * cutoff and is still recorded as ending after it belongs to the answerable window and stays, and
   * an interval ending exactly on the cutoff stays too - the sweep is strictly {@code <}.
   */
  @Test
  void anIntervalSpanningTheCutoffStaysAndSoDoesOneEndingExactlyOnIt() {
    UUID spanning = closedGrantInterval(monthsAgo(48), monthsAgo(1));
    Instant cutoff = deletionService.runOnce().cutoff();
    UUID endingOnTheCutoff = closedGrantInterval(monthsAgo(48), cutoff);

    deletionService.runOnce();

    assertThat(grantHistoryRepository.existsById(spanning))
        .as("it was in force at every instant inside the period")
        .isTrue();
    assertThat(grantHistoryRepository.existsById(endingOnTheCutoff))
        .as("valid_to = cutoff is the first instant the period covers")
        .isTrue();
  }

  /** A second pass in the same month reaches the same cutoff and finds nothing left. */
  @Test
  void aSecondPassInTheSameMonthRemovesNothingMore() {
    closedGrantInterval(monthsAgo(48), monthsAgo(40));

    PermissionHistoryRetentionRun first = deletionService.runOnce();
    PermissionHistoryRetentionRun second = deletionService.runOnce();

    assertThat(first.deletedRows())
        .as("at least this class's own aged row - the count spans every history table")
        .isGreaterThanOrEqualTo(1);
    assertThat(second.deletedRows()).isZero();
    assertThat(second.cutoff()).isEqualTo(first.cutoff());
  }

  /**
   * The guarantee "eine Verkürzung wirkt nur nach vorn": setting the period to its floor does not
   * make the next pass remove everything older than a year - the cutoff advances at most one
   * calendar month per elapsed calendar month, from where the last pass left it.
   */
  @Test
  void aShorteningOfThePeriodDoesNotTakeEffectAtOnce() {
    UUID row = closedGrantInterval(monthsAgo(24), monthsAgo(20));
    updateRetentionMonths(PermissionHistoryRetentionSettings.MIN_RETENTION_MONTHS);

    PermissionHistoryRetentionRun run = deletionService.runOnce();

    assertThat(grantHistoryRepository.existsById(row))
        .as("20 months old, the configured period now 12 - and the row still stands")
        .isTrue();
    assertThat(run.cutoff())
        .as("the cutoff stays where the installation left it, not at twelve months")
        .isBefore(monthsAgo(30));
  }

  /**
   * A lengthened period makes a pass delete less - but it must not move the recorded progress back.
   * Would it, a later shortening had to creep over months the deletion had long passed, one month
   * per month, while rows inside them stood untouched the whole time.
   */
  @Test
  void aLongerPeriodMakesThePassDeleteLessWithoutMovingTheProgressBack() {
    UUID aged = closedGrantInterval(monthsAgo(48), monthsAgo(40));
    Instant progressBefore = settingsRepository.findSingleton().orElseThrow().getLastCutoff();
    updateRetentionMonths(PermissionHistoryRetentionSettings.MAX_RETENTION_MONTHS);

    PermissionHistoryRetentionRun run = deletionService.runOnce();

    assertThat(grantHistoryRepository.existsById(aged))
        .as("40 months old, the configured period is now 120 - it is inside the period")
        .isTrue();
    assertThat(run.cutoff())
        .as("the pass deletes by the lengthened period's own target")
        .isBefore(progressBefore);
    assertThat(settingsRepository.findSingleton().orElseThrow().getLastCutoff())
        .as("the recorded progress never moves back")
        .isEqualTo(progressBefore);
  }

  /**
   * What a Stichtag before the cutoff yields, held as a contract rather than left to be
   * rediscovered by the reading path #1822 builds: the reconstruction answers <em>empty</em>, and
   * that is indistinguishable from "had no access". A caller that states an Auskunft has to compare
   * its Stichtag against {@code retentionCutoff()} first and name which of the two it is.
   */
  @Test
  void aStichtagBeforeTheCutoffAnswersEmptyRatherThanNegative() {
    UUID assetId = UUID.randomUUID();
    closedUserGrantInterval(assetId, monthsAgo(48), monthsAgo(40));
    Instant stichtag = monthsAgo(44);

    assertThat(
            permissionHistoryService.readableAssetIdsAsOf(
                TEST_ASSET, memberUserId, Organization.DEFAULT_ID, stichtag))
        .as("inside the period the reconstruction answers, and the absence of an id proves")
        .contains(assetId);

    Instant cutoff = deletionService.runOnce().cutoff();

    assertThat(stichtag).isBefore(cutoff);
    assertThat(
            permissionHistoryService.readableAssetIdsAsOf(
                TEST_ASSET, memberUserId, Organization.DEFAULT_ID, stichtag))
        .as(
            "outside the period the same call answers empty - no longer on record, not \"no access\"")
        .doesNotContain(assetId);
    assertThat(retentionService.retentionCutoff())
        .as("the boundary a caller needs to tell the two apart")
        .contains(cutoff);
  }

  /**
   * The progress is recorded even when nothing was removed - otherwise the forward-only cap would
   * never advance on an installation whose history is younger than its period.
   */
  @Test
  void aPassWithoutAnyExpiredRowStillRecordsItsProgress() {
    PermissionHistoryRetentionRun run = deletionService.runOnce();

    PermissionHistoryRetentionSettings settings = settingsRepository.findSingleton().orElseThrow();
    assertThat(settings.getLastCutoff()).isEqualTo(run.cutoff());
    assertThat(settings.getLastRunMonth()).isNotNull();
  }

  /**
   * The narrow update is a {@code @Modifying} query and needs a transaction of its own here - in
   * production its only caller, {@link PermissionHistoryRetentionService}, brings one.
   */
  private void updateRetentionMonths(int months) {
    transactionTemplate.executeWithoutResult(
        status -> settingsRepository.updateRetentionMonths(months));
  }

  private UUID closedUserGrantInterval(UUID assetId, Instant from, Instant to) {
    AssetGrant grant =
        AssetGrant.forUser(
            TEST_ASSET,
            assetId,
            Organization.DEFAULT_ID,
            memberUserId,
            AssetRole.VIEWER,
            null,
            null);
    AssetGrantHistory interval =
        AssetGrantHistory.open(grant, AssetGrantHistoryCause.GRANTED, null, from);
    interval.close(to);
    return saveGrantInterval(interval);
  }

  private static Instant monthsAgo(int months) {
    return ZonedDateTime.now(ZoneOffset.UTC).minusMonths(months).toInstant();
  }

  private UUID closedGrantInterval(Instant from, Instant to) {
    AssetGrantHistory interval = grantInterval(from);
    interval.close(to);
    return saveGrantInterval(interval);
  }

  private UUID openGrantInterval(Instant from) {
    return saveGrantInterval(grantInterval(from));
  }

  private UUID saveGrantInterval(AssetGrantHistory interval) {
    UUID id = grantHistoryRepository.save(interval).getId();
    grantRowIds.add(id);
    return id;
  }

  private AssetGrantHistory grantInterval(Instant from) {
    AssetGrant grant =
        AssetGrant.forGroup(
            TEST_ASSET,
            UUID.randomUUID(),
            Organization.DEFAULT_ID,
            UUID.randomUUID(),
            AssetRole.VIEWER,
            null,
            null);
    return AssetGrantHistory.open(grant, AssetGrantHistoryCause.GRANTED, null, from);
  }

  private UUID closedMembershipInterval(Instant from, Instant to) {
    GroupMembershipHistory interval = membershipInterval(from);
    interval.close(to);
    return saveMembershipInterval(interval);
  }

  private UUID openMembershipInterval(Instant from) {
    return saveMembershipInterval(membershipInterval(from));
  }

  private UUID saveMembershipInterval(GroupMembershipHistory interval) {
    UUID id = membershipHistoryRepository.save(interval).getId();
    membershipRowIds.add(id);
    return id;
  }

  private GroupMembershipHistory membershipInterval(Instant from) {
    return new GroupMembershipHistory(
        UUID.randomUUID(),
        Organization.DEFAULT_ID,
        memberUserId,
        GroupMembershipHistoryCause.ADDED,
        null,
        from);
  }

  private UUID closedVisibilityInterval(Instant from, Instant to) {
    LibraryVisibilityHistory interval = visibilityInterval(from);
    interval.close(to);
    return saveVisibilityInterval(interval);
  }

  private UUID openVisibilityInterval(Instant from) {
    return saveVisibilityInterval(visibilityInterval(from));
  }

  private UUID saveVisibilityInterval(LibraryVisibilityHistory interval) {
    UUID id = visibilityHistoryRepository.save(interval).getId();
    visibilityRowIds.add(id);
    return id;
  }

  private LibraryVisibilityHistory visibilityInterval(Instant from) {
    return new LibraryVisibilityHistory(
        UUID.randomUUID(),
        Organization.DEFAULT_ID,
        LibraryVisibility.PRIVATE,
        false,
        ExternalAccessState.NEVER_SET,
        null,
        LibraryVisibilityHistoryCause.CREATED,
        null,
        from);
  }

  private UUID persistUser() {
    User user =
        new User(
            "permission-history-retention-" + UUID.randomUUID(),
            "https://issuer.example",
            UUID.randomUUID() + "@example.com",
            "Mitglied");
    user.setOrganizationId(Organization.DEFAULT_ID);
    UUID id = userRepository.save(user).getId();
    userIds.add(id);
    return id;
  }
}
