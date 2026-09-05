package io.opaa.group;

import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditObjectType;
import io.opaa.api.types.AuditOutcome;
import io.opaa.api.types.AuditSubjectKind;
import io.opaa.api.types.GroupKind;
import io.opaa.audit.AuditEvent;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.auth.User;
import io.opaa.auth.oidc.OidcProvider;
import io.opaa.library.PermissionHistoryService;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Mirrors a provider's groups claim into {@link GroupKind#IDENTITY_PROVIDER} groups (ADR-0025,
 * Entscheidung 4): on every sign-in of a provider with a {@code groups_claim}, the user is a member
 * of exactly the groups the token names - created on first sight, membership added or removed as
 * the token changes - inside the provider's namespace {@value #EXTERNAL_ID_PREFIX}{@code
 * <provider-id>:<name>}, so same-named groups of two providers are two groups and no provider can
 * reach into another's. A name longer than {@link #MAX_NAME_LENGTH} is skipped and logged.
 *
 * <p>One read per request decides whether anything changed; only then are writes made, serialized
 * per provider through an advisory lock so two first sign-ins cannot create the same group twice
 * ({@code uk_groups_organization_external_id} backs that). Every change is historised ({@link
 * GroupMembershipHistoryCause#IDENTITY_PROVIDER_ADDED}/{@code _REMOVED}) and audited under the
 * {@value #IDENTITY_PROVIDER_ACTOR} system actor; the user's cached group set is evicted after the
 * commit. ORG_UNIT and AD_HOC groups are never touched.
 */
@Component
public class TokenGroupSynchronizer {

  public static final String EXTERNAL_ID_PREFIX = "oidc:";
  static final String IDENTITY_PROVIDER_ACTOR = "identity-provider";

  /** {@code groups.external_id} is 255 characters; the namespace takes 42 of them. */
  static final int MAX_NAME_LENGTH = 255 - (EXTERNAL_ID_PREFIX.length() + 36 + 1);

  private static final Logger log = LoggerFactory.getLogger(TokenGroupSynchronizer.class);

  private final GroupRepository groupRepository;
  private final GroupMembershipRepository membershipRepository;
  private final GroupMembershipResolver membershipResolver;
  private final PermissionHistoryService permissionHistoryService;
  private final AuditEventRecorder auditEventRecorder;

  public TokenGroupSynchronizer(
      GroupRepository groupRepository,
      GroupMembershipRepository membershipRepository,
      GroupMembershipResolver membershipResolver,
      PermissionHistoryService permissionHistoryService,
      AuditEventRecorder auditEventRecorder) {
    this.groupRepository = groupRepository;
    this.membershipRepository = membershipRepository;
    this.membershipResolver = membershipResolver;
    this.permissionHistoryService = permissionHistoryService;
    this.auditEventRecorder = auditEventRecorder;
  }

  /** The namespace of {@code provider}'s groups. */
  public static String namespaceOf(OidcProvider provider) {
    return EXTERNAL_ID_PREFIX + provider.getId() + ":";
  }

  /** Cheap when the token's groups equal the stored memberships; otherwise a resync. */
  @Transactional
  public void apply(User user, OidcProvider provider, List<String> groupNames) {
    String prefix = namespaceOf(provider);
    Map<String, String> desired = desiredByExternalId(provider, prefix, groupNames);
    Set<String> current =
        groupRepository.findIdentityProviderExternalIdsOfUser(user.getId(), prefix);
    if (current.equals(desired.keySet())) {
      return;
    }
    resync(user, provider, prefix, desired, current);
  }

  private static Map<String, String> desiredByExternalId(
      OidcProvider provider, String prefix, List<String> groupNames) {
    Map<String, String> desired = new LinkedHashMap<>();
    for (String raw : groupNames) {
      String name = raw == null ? "" : raw.trim();
      if (name.isEmpty()) {
        continue;
      }
      if (name.length() > MAX_NAME_LENGTH) {
        log.warn(
            "Provider '{}' names a group of {} characters in its token; groups longer than {}"
                + " characters are ignored",
            provider.getDisplayName(),
            name.length(),
            MAX_NAME_LENGTH);
        continue;
      }
      desired.putIfAbsent(prefix + name, name);
    }
    return desired;
  }

  private void resync(
      User user,
      OidcProvider provider,
      String prefix,
      Map<String, String> desired,
      Set<String> current) {
    groupRepository.lockIdentityProviderGroups(provider.getId());
    UUID organizationId = user.getOrganizationId();
    boolean changed = false;
    for (Map.Entry<String, String> entry : desired.entrySet()) {
      if (current.contains(entry.getKey())) {
        continue;
      }
      Group group = findOrCreate(organizationId, provider, entry.getKey(), entry.getValue());
      if (group.getMemberships().stream().anyMatch(m -> m.getUserId().equals(user.getId()))) {
        continue;
      }
      GroupMembership membership = new GroupMembership(user.getId(), organizationId);
      group.addMembership(membership);
      groupRepository.save(group);
      permissionHistoryService.recordMembershipAdded(
          membership, GroupMembershipHistoryCause.IDENTITY_PROVIDER_ADDED, null);
      recordMembershipChange(user, provider, group, AuditEventType.GROUP_MEMBER_ADDED);
      changed = true;
    }
    for (String externalId : new LinkedHashSet<>(current)) {
      if (desired.containsKey(externalId)) {
        continue;
      }
      groupRepository
          .findByOrganizationIdAndKindAndExternalId(
              organizationId, GroupKind.IDENTITY_PROVIDER, externalId)
          .ifPresent(
              group -> {
                membershipRepository
                    .findByGroupIdAndUserId(group.getId(), user.getId())
                    .ifPresent(group::removeMembership);
                groupRepository.save(group);
                permissionHistoryService.recordMembershipRemoved(
                    group.getId(),
                    organizationId,
                    user.getId(),
                    GroupMembershipHistoryCause.IDENTITY_PROVIDER_REMOVED,
                    null);
                recordMembershipChange(user, provider, group, AuditEventType.GROUP_MEMBER_REMOVED);
              });
      changed = true;
    }
    if (changed) {
      invalidateAfterCommit(user.getId());
    }
  }

  private Group findOrCreate(
      UUID organizationId, OidcProvider provider, String externalId, String name) {
    return groupRepository
        .findByOrganizationIdAndKindAndExternalId(
            organizationId, GroupKind.IDENTITY_PROVIDER, externalId)
        .orElseGet(
            () -> {
              Group group =
                  new Group(
                      organizationId, GroupKind.IDENTITY_PROVIDER, name, null, externalId, null);
              Group saved = groupRepository.save(group);
              auditEventRecorder.recordSystemProcessAction(
                  AuditEvent.builder()
                      .organizationId(organizationId)
                      .actorRef(IDENTITY_PROVIDER_ACTOR)
                      .type(AuditEventType.GROUP_CREATED)
                      .object(AuditObjectType.GROUP, saved.getId(), saved.getName())
                      .after(
                          Map.of("provider", provider.getDisplayName(), "externalId", externalId))
                      .outcome(AuditOutcome.SUCCESS)
                      .build());
              log.info(
                  "Created identity-provider group '{}' ({}) for provider '{}'",
                  name,
                  externalId,
                  provider.getDisplayName());
              return saved;
            });
  }

  private void recordMembershipChange(
      User user, OidcProvider provider, Group group, AuditEventType type) {
    boolean added = type == AuditEventType.GROUP_MEMBER_ADDED;
    auditEventRecorder.recordSystemProcessAction(
        AuditEvent.builder()
            .organizationId(user.getOrganizationId())
            .actorRef(IDENTITY_PROVIDER_ACTOR)
            .type(type)
            .object(AuditObjectType.GROUP, group.getId(), group.getName())
            .subject(AuditSubjectKind.USER, user.getId())
            .before(added ? null : Map.of("member", true))
            .after(added ? Map.of("member", true, "provider", provider.getDisplayName()) : null)
            .outcome(AuditOutcome.SUCCESS)
            .build());
  }

  private void invalidateAfterCommit(UUID userId) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      membershipResolver.invalidateUser(userId);
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            membershipResolver.invalidateUser(userId);
          }
        });
  }
}
