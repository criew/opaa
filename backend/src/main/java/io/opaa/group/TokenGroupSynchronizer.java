package io.opaa.group;

import io.opaa.api.RateLimitService;
import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditObjectType;
import io.opaa.api.types.AuditOutcome;
import io.opaa.api.types.AuditSubjectKind;
import io.opaa.api.types.GroupKind;
import io.opaa.audit.AuditEvent;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.auth.TokenGroups;
import io.opaa.auth.User;
import io.opaa.auth.oidc.OidcProvider;
import io.opaa.permission.GroupMembershipHistoryCause;
import io.opaa.permission.GroupMembershipResolver;
import io.opaa.permission.PermissionHistoryService;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
 *
 * <p>Only a claim the token actually carried is authoritative (#1807): an empty claim is the
 * provider ending every membership of its namespace, but a token whose claim is absent, of another
 * shape or replaced by an overage reference leaves the last known memberships as they are and is
 * logged per provider and cause at most once per {@link #INCIDENT_WINDOW_SECONDS} seconds. Without
 * that distinction a removed group mapper at the provider would silently revoke every account's
 * access on its next sign-in. One call writes at most one such entry.
 */
@Component
public class TokenGroupSynchronizer {

  public static final String EXTERNAL_ID_PREFIX = "oidc:";
  static final String IDENTITY_PROVIDER_ACTOR = "identity-provider";

  /** {@code groups.external_id} is 255 characters; the namespace takes 42 of them. */
  static final int MAX_NAME_LENGTH = 255 - (EXTERNAL_ID_PREFIX.length() + 36 + 1);

  /** One incident per provider and cause per window: a broken provider must not flood the log. */
  static final int INCIDENT_WINDOW_SECONDS = 300;

  private static final int INCIDENTS_PER_WINDOW = 1;

  /** Causes of {@link #incidentDue} that are no {@link TokenGroups.Reason}. */
  private static final String NO_USABLE_NAME = "NO_USABLE_NAME";

  private static final String OVERLONG_NAME = "OVERLONG_NAME";

  private static final Logger log = LoggerFactory.getLogger(TokenGroupSynchronizer.class);

  private final RateLimitService incidentLog =
      new RateLimitService(INCIDENTS_PER_WINDOW, INCIDENT_WINDOW_SECONDS);

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

  /**
   * Cheap when the token's groups equal the stored memberships; otherwise a resync. A token that
   * named no usable claim changes nothing at all and is only reported (#1807).
   */
  @Transactional
  public void apply(User user, OidcProvider provider, TokenGroups groups) {
    switch (groups) {
      case TokenGroups.Unavailable unavailable ->
          reportUnchanged(
              provider, unavailable.reason().name(), unavailable.reason().description());
      case TokenGroups.Named named -> applyNames(user, provider, named.names());
    }
  }

  private void applyNames(User user, OidcProvider provider, List<String> groupNames) {
    String prefix = namespaceOf(provider);
    DesiredGroups desired = desiredByExternalId(prefix, groupNames);
    Map<String, String> byExternalId = desired.byExternalId();
    // a claim that named groups but none this installation can hold is no revocation either
    if (byExternalId.isEmpty() && !groupNames.isEmpty()) {
      reportUnchanged(provider, NO_USABLE_NAME, "names only groups that cannot be held here");
      return;
    }
    reportIgnoredNames(provider, desired.tooLongNames());
    Set<String> current =
        groupRepository.findIdentityProviderExternalIdsOfUser(user.getId(), prefix);
    if (current.equals(byExternalId.keySet())) {
      return;
    }
    resync(user, provider, prefix, byExternalId, current);
  }

  /** Names the provider and the cause; the memberships are left as they are. */
  private void reportUnchanged(OidcProvider provider, String cause, String description) {
    if (!incidentDue(provider, cause)) {
      return;
    }
    log.warn(
        "The token of provider '{}' {}; the group memberships of its accounts are left unchanged"
            + " (further incidents of this cause are suppressed for {} seconds)",
        provider.getDisplayName(),
        description,
        INCIDENT_WINDOW_SECONDS);
  }

  /** The other names of the same token are applied, so this is no "left unchanged". */
  private void reportIgnoredNames(OidcProvider provider, int tooLongNames) {
    if (tooLongNames == 0 || !incidentDue(provider, OVERLONG_NAME)) {
      return;
    }
    log.warn(
        "The token of provider '{}' names {} group(s) longer than {} characters, which are ignored"
            + " (further incidents of this cause are suppressed for {} seconds)",
        provider.getDisplayName(),
        tooLongNames,
        MAX_NAME_LENGTH,
        INCIDENT_WINDOW_SECONDS);
  }

  /**
   * At most one log entry per provider and cause per {@link #INCIDENT_WINDOW_SECONDS} seconds: a
   * provider whose tokens are broken sends every account of its own through here, and a changed
   * cause is news of its own rather than a repetition.
   */
  private boolean incidentDue(OidcProvider provider, String cause) {
    return incidentLog.isAllowed(provider.getId() + ":" + cause);
  }

  /**
   * @param byExternalId the namespaced names the token's claim can be held under
   * @param tooLongNames how many of its names exceed {@link #MAX_NAME_LENGTH} and were dropped -
   *     counted rather than logged here, so that one call writes at most one log entry
   */
  private record DesiredGroups(Map<String, String> byExternalId, int tooLongNames) {}

  private static DesiredGroups desiredByExternalId(String prefix, List<String> groupNames) {
    Map<String, String> desired = new LinkedHashMap<>();
    int tooLongNames = 0;
    for (String raw : groupNames) {
      String name = raw == null ? "" : raw.trim();
      if (name.isEmpty()) {
        continue;
      }
      if (name.length() > MAX_NAME_LENGTH) {
        tooLongNames++;
        continue;
      }
      desired.putIfAbsent(prefix + name, name);
    }
    return new DesiredGroups(desired, tooLongNames);
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
      // one row, not the group's whole membership list - a large group must not be loaded on
      // every first sign-in of a further member
      if (membershipRepository.findByGroupIdAndUserId(group.getId(), user.getId()).isPresent()) {
        continue;
      }
      GroupMembership membership = new GroupMembership(user.getId(), organizationId);
      membership.assignGroup(group);
      membershipRepository.save(membership);
      permissionHistoryService.recordMembershipAdded(
          group.getId(),
          organizationId,
          user.getId(),
          GroupMembershipHistoryCause.IDENTITY_PROVIDER_ADDED,
          null);
      recordMembershipChange(user, provider, group, AuditEventType.GROUP_MEMBER_ADDED);
      changed = true;
    }
    for (String externalId : new LinkedHashSet<>(current)) {
      if (desired.containsKey(externalId)) {
        continue;
      }
      Optional<Group> group =
          groupRepository.findByOrganizationIdAndKindAndExternalId(
              organizationId, GroupKind.IDENTITY_PROVIDER, externalId);
      Optional<GroupMembership> membership =
          group.flatMap(g -> membershipRepository.findByGroupIdAndUserId(g.getId(), user.getId()));
      if (group.isEmpty() || membership.isEmpty()) {
        continue;
      }
      membershipRepository.delete(membership.get());
      permissionHistoryService.recordMembershipRemoved(
          group.get().getId(),
          organizationId,
          user.getId(),
          GroupMembershipHistoryCause.IDENTITY_PROVIDER_REMOVED,
          null);
      recordMembershipChange(user, provider, group.get(), AuditEventType.GROUP_MEMBER_REMOVED);
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
