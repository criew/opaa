package io.opaa.space;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opaa.api.types.SpaceRole;
import io.opaa.api.types.SpaceVisibility;
import io.opaa.api.types.SystemRole;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.auth.CurrentUser;
import io.opaa.auth.UserRepository;
import io.opaa.common.ConflictException;
import io.opaa.permission.AssetOwnershipHistoryService;
import io.opaa.permission.CapabilityService;
import io.opaa.permission.GroupMembershipResolver;
import io.opaa.permission.GroupSubjectDirectory;
import io.opaa.permission.SuccessionReachGuard;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

/**
 * {@link SpaceService#ensureDefaultSpace}'s race handling (#265) is now entirely delegated to
 * {@link SpaceRepository#insertDefaultSpaceIfAbsent}'s {@code ON CONFLICT ... DO NOTHING} (#201/
 * #305 code review) - there is no more application-level catch-and-reread branch to simulate here,
 * unlike the version of this test that predates that change. What remains testable at the unit
 * level is the one decision {@link SpaceService#ensureDefaultSpace} itself still makes: skip the
 * insert attempt entirely when {@code existsByOwnerIdAndKind} already reports a personal space,
 * otherwise delegate to the repository. A {@link PlatformTransactionManager} is still mocked here
 * (not a real one) because {@link SpaceService} constructs its own {@link
 * org.springframework.transaction.support.TransactionTemplate} from it in the constructor; {@code
 * TransactionTemplate#executeWithoutResult} invokes the callback synchronously regardless of
 * whether the underlying transaction manager is real, so the mocked repository call inside it is
 * still observable via {@code verify(...)} below.
 */
class SpaceServiceTest {

  private SpaceRepository spaceRepository;
  private PlatformTransactionManager transactionManager;
  private SpaceAccessPolicy accessPolicy;
  private SpaceMembershipHistoryService membershipHistory;
  private SpaceService spaceService;

  private final SuccessionReachGuard successionGuard = mock(SuccessionReachGuard.class);

  @BeforeEach
  void setUp() {
    spaceRepository = mock(SpaceRepository.class);
    UserRepository userRepository = mock(UserRepository.class);
    transactionManager = mock(PlatformTransactionManager.class);
    when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
    AuditEventRecorder auditEventRecorder = mock(AuditEventRecorder.class);
    SpaceAssetAssociationService associationService = mock(SpaceAssetAssociationService.class);
    accessPolicy = mock(SpaceAccessPolicy.class);
    membershipHistory = mock(SpaceMembershipHistoryService.class);
    CapabilityService capabilityService = mock(CapabilityService.class);
    spaceService =
        new SpaceService(
            spaceRepository,
            userRepository,
            auditEventRecorder,
            mock(SpaceChatDirectory.class),
            associationService,
            accessPolicy,
            membershipHistory,
            mock(AssetOwnershipHistoryService.class),
            mock(GroupMembershipResolver.class),
            mock(GroupSubjectDirectory.class),
            mock(io.opaa.permission.GroupMemberDisclosureDirectory.class),
            capabilityService,
            new io.opaa.permission.GroupSizeProperties(null),
            successionGuard,
            mock(SpaceSuccessionSource.class),
            transactionManager);
  }

  /**
   * ADR-0036, Entscheidung 6, Schutzregel 1 - the half the integration test cannot reach: a space
   * whose owner is not the membership being touched, so the owner rule does not apply and {@code
   * SpaceService#requireCapableAdminRemains} is the only thing that can refuse. The decision itself
   * lives in {@link SpaceAccessPolicy#hasCapableAdminAfter} (tested there against a real group);
   * here it is driven through the mocked policy, which is what makes the guard's own call site
   * observable at all - through the API it is subsumed by the owner protection until #1818 gives
   * accounts a state.
   */
  @Test
  void theLastCapableAdminIsProtectedEvenWhenTheOwnerRuleDoesNotApply() {
    UUID organizationId = UUID.randomUUID();
    UUID owner = UUID.randomUUID();
    UUID admin = UUID.randomUUID();
    Space space = new Space("Team", null, false, SpaceVisibility.PRIVATE, owner, organizationId);
    SpaceMembership adminRow = SpaceMembership.ofUser(admin, SpaceRole.ADMIN, organizationId);
    space.addMembership(adminRow);
    when(spaceRepository.findByIdWithMemberships(any(UUID.class))).thenReturn(Optional.of(space));
    when(accessPolicy.hasCapableAdminAfter(space, adminRow, null)).thenReturn(false);
    when(accessPolicy.hasCapableAdmin(space)).thenReturn(true);
    CurrentUser caller = CurrentUser.of(owner, organizationId, SystemRole.USER, "Owner", null);

    assertThatThrownBy(() -> spaceService.removeMember(space.getId(), adminRow.getId(), caller))
        .isInstanceOf(ConflictException.class)
        .hasMessageContaining("letztes handlungsfähiges ADMIN-Mitglied");
    verify(membershipHistory, never()).recordRemoved(any(), any());
  }

  /**
   * #1819: the rule refuses the <em>loss</em>, not the state. A space that already has no capable
   * ADMIN loses none by removing somebody - and refusing there would freeze a change that takes
   * reach away.
   */
  @Test
  void aSpaceThatAlreadyHasNoCapableAdminStillLosesAMember() {
    UUID organizationId = UUID.randomUUID();
    UUID owner = UUID.randomUUID();
    Space space = new Space("Team", null, false, SpaceVisibility.PRIVATE, owner, organizationId);
    SpaceMembership memberRow =
        SpaceMembership.ofUser(UUID.randomUUID(), SpaceRole.MEMBER, organizationId);
    space.addMembership(memberRow);
    when(spaceRepository.findByIdWithMemberships(any(UUID.class))).thenReturn(Optional.of(space));
    when(accessPolicy.hasCapableAdminAfter(space, memberRow, null)).thenReturn(false);
    when(accessPolicy.hasCapableAdmin(space)).thenReturn(false);
    CurrentUser caller = CurrentUser.of(owner, organizationId, SystemRole.USER, "Owner", null);

    spaceService.removeMember(space.getId(), memberRow.getId(), caller);

    verify(membershipHistory).recordRemoved(any(), any());
  }

  /** The same call site must let the change through when a capable ADMIN does remain. */
  @Test
  void aMemberIsRemovedWhenACapableAdminRemains() {
    UUID organizationId = UUID.randomUUID();
    UUID owner = UUID.randomUUID();
    Space space = new Space("Team", null, false, SpaceVisibility.PRIVATE, owner, organizationId);
    SpaceMembership memberRow =
        SpaceMembership.ofUser(UUID.randomUUID(), SpaceRole.MEMBER, organizationId);
    space.addMembership(memberRow);
    when(spaceRepository.findByIdWithMemberships(any(UUID.class))).thenReturn(Optional.of(space));
    when(accessPolicy.hasCapableAdminAfter(space, memberRow, null)).thenReturn(true);
    CurrentUser caller = CurrentUser.of(owner, organizationId, SystemRole.USER, "Owner", null);

    spaceService.removeMember(space.getId(), memberRow.getId(), caller);

    verify(membershipHistory).recordRemoved(memberRow, owner);
    assertThat(space.getMemberships()).doesNotContain(memberRow);
  }

  @Test
  void ensureDefaultSpaceInsertsWhenNoPersonalSpaceExistsYet() {
    UUID userId = UUID.randomUUID();
    UUID organizationId = UUID.randomUUID();
    when(spaceRepository.existsByOwnerIdAndIsDefaultTrue(userId)).thenReturn(false);

    spaceService.ensureDefaultSpace(userId, organizationId);

    verify(spaceRepository)
        .insertDefaultSpaceIfAbsent(
            any(UUID.class),
            any(UUID.class),
            any(String.class),
            any(String.class),
            eq(userId),
            eq(organizationId));
  }

  @Test
  void ensureDefaultSpaceIsANoOpWhenAPersonalSpaceAlreadyExists() {
    UUID userId = UUID.randomUUID();
    UUID organizationId = UUID.randomUUID();
    when(spaceRepository.existsByOwnerIdAndIsDefaultTrue(userId)).thenReturn(true);

    spaceService.ensureDefaultSpace(userId, organizationId);

    verify(spaceRepository, never())
        .insertDefaultSpaceIfAbsent(
            any(UUID.class),
            any(UUID.class),
            any(String.class),
            any(String.class),
            any(UUID.class),
            any(UUID.class));
  }

  @Test
  void ensureDefaultSpacePropagatesAnyFailureFromTheInsert() {
    // A genuine failure other than the partial-unique-index conflict (which the repository method
    // itself absorbs via ON CONFLICT ... DO NOTHING and therefore never throws for) - e.g. a
    // dangling ownerId violating fk_spaces_owner - must still surface to the caller, not be
    // swallowed. There is no "was it the race or a real violation" distinction to make anymore;
    // whatever the repository throws propagates as-is.
    UUID userId = UUID.randomUUID();
    UUID organizationId = UUID.randomUUID();
    when(spaceRepository.existsByOwnerIdAndIsDefaultTrue(userId)).thenReturn(false);
    DataIntegrityViolationException violation =
        new DataIntegrityViolationException("fk_spaces_owner violation");
    doThrow(violation)
        .when(spaceRepository)
        .insertDefaultSpaceIfAbsent(
            any(UUID.class),
            any(UUID.class),
            any(String.class),
            any(String.class),
            eq(userId),
            eq(organizationId));

    assertThatThrownBy(() -> spaceService.ensureDefaultSpace(userId, organizationId))
        .isSameAs(violation);
  }

  @Test
  void ensureDefaultSpaceDoesNotThrowWhenTheInsertSucceedsOrIsANoOp() {
    UUID userId = UUID.randomUUID();
    UUID organizationId = UUID.randomUUID();
    when(spaceRepository.existsByOwnerIdAndIsDefaultTrue(userId)).thenReturn(false);

    assertThatCode(() -> spaceService.ensureDefaultSpace(userId, organizationId))
        .doesNotThrowAnyException();
  }

  @Test
  void ensureDefaultSpaceForNewUserSkipsTheExistenceCheck() {
    // #307: a caller that already knows userId is brand new (PersonalSpaceProvisioner, for a
    // subject/issuer pair the sign-in's own insert just created) must not spend an extra pooled
    // connection confirming a fact it already knows - see ensureDefaultSpaceForNewUser's Javadoc.
    UUID userId = UUID.randomUUID();
    UUID organizationId = UUID.randomUUID();

    spaceService.ensureDefaultSpaceForNewUser(userId, organizationId);

    verify(spaceRepository, never()).existsByOwnerIdAndIsDefaultTrue(any(UUID.class));
    verify(spaceRepository)
        .insertDefaultSpaceIfAbsent(
            any(UUID.class),
            any(UUID.class),
            any(String.class),
            any(String.class),
            eq(userId),
            eq(organizationId));
  }

  @Test
  void ensureDefaultSpaceCachesAProvisionedPersonalSpaceAcrossCalls() {
    // #307/#137: once a personal space is known to exist for userId, a later ensureDefaultSpace
    // call for the same user - the returning-user steady state, by far the common case - must not
    // spend a pooled connection re-confirming it.
    UUID userId = UUID.randomUUID();
    UUID organizationId = UUID.randomUUID();
    when(spaceRepository.existsByOwnerIdAndIsDefaultTrue(userId)).thenReturn(true);

    spaceService.ensureDefaultSpace(userId, organizationId);
    spaceService.ensureDefaultSpace(userId, organizationId);

    verify(spaceRepository, org.mockito.Mockito.times(1)).existsByOwnerIdAndIsDefaultTrue(userId);
  }

  @Test
  void ensureDefaultSpaceForNewUserAlsoPopulatesTheCacheForLaterCalls() {
    UUID userId = UUID.randomUUID();
    UUID organizationId = UUID.randomUUID();

    spaceService.ensureDefaultSpaceForNewUser(userId, organizationId);
    spaceService.ensureDefaultSpace(userId, organizationId);

    verify(spaceRepository, never()).existsByOwnerIdAndIsDefaultTrue(any(UUID.class));
  }
}
