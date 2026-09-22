package io.opaa.group;

import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditObjectType;
import io.opaa.api.types.AuditOutcome;
import io.opaa.api.types.AuditSubjectKind;
import io.opaa.audit.AuditEvent;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.auth.CurrentUser;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.common.ConflictException;
import io.opaa.common.NotFoundException;
import io.opaa.common.OrganizationScopedLoader;
import io.opaa.common.ValidationException;
import io.opaa.permission.GroupMembershipChangeListener;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The contact points of provider groups (#1875, ADR-0036 Entscheidung 9). The system administration
 * names them - a Verwaltungsakt that changes nothing about the group - and they, and only they, set
 * and release that group's protection mark.
 *
 * <p><b>A contact point is no maintenance right.</b> A provider group stays read-only: it comes
 * from the directory or from a token, and this appointment adds no way to rename it, to change its
 * members or to release it for use. Whoever is named is a member of the group already; the
 * appointment gives them one act, not a role.
 *
 * <p><b>The appointment ends by itself when its ground does.</b> A person who leaves the group is
 * no longer its contact point, and the row goes with the membership ({@link #onMembershipChanged});
 * a person whose account is locked stays appointed but counts for nothing, so a protected group
 * without a usable contact point shows up in the operational list of #1819.
 */
@Service
@Transactional(readOnly = true)
public class GroupContactService implements GroupMembershipChangeListener {

  private static final Logger log = LoggerFactory.getLogger(GroupContactService.class);

  /** The stable {@code code} of the {@code 403} anybody but a contact point gets at the mark. */
  public static final String CONTACT_REQUIRED = "CONTACT_REQUIRED";

  /** The process behind an appointment that ended with its membership, not with a decision. */
  public static final String SYSTEM_ACTOR = "group-membership";

  private final GroupContactRepository contacts;
  private final GroupRepository groups;
  private final UserRepository users;
  private final AuditEventRecorder auditEventRecorder;

  public GroupContactService(
      GroupContactRepository contacts,
      GroupRepository groups,
      UserRepository users,
      AuditEventRecorder auditEventRecorder) {
    this.contacts = contacts;
    this.groups = groups;
    this.users = users;
    this.auditEventRecorder = auditEventRecorder;
  }

  /**
   * Names a further contact point. Only a natural person of the same organization who is already a
   * <b>member</b> of the group: the appointment speaks for the body concerned, and somebody outside
   * it would speak for a body they do not belong to.
   */
  @Transactional
  public GroupContactView appointContact(UUID groupId, UUID userId, CurrentUser caller) {
    Group group = loadGroup(groupId, caller);
    requireProviderGroup(group);
    requireUserInOrganization(userId, group.getOrganizationId());
    if (group.getMemberships().stream().noneMatch(m -> m.getUserId().equals(userId))) {
      throw new ValidationException("Nur ein Mitglied dieser Gruppe kann ihre Ansprechstelle sein");
    }
    if (contacts.existsByGroupIdAndUserId(groupId, userId)) {
      throw new ConflictException("Die Person ist bereits Ansprechstelle dieser Gruppe");
    }
    GroupContact contact =
        contacts.save(
            new GroupContact(group.getId(), userId, group.getOrganizationId(), caller.id()));
    recordEvent(AuditEventType.GROUP_CONTACT_APPOINTED, group, userId, caller.id());
    return new GroupContactView(contact, displayNameOf(userId));
  }

  /**
   * Dismisses a contact point. The last one may go - an account leaving the house has to be
   * releasable - and a protected group without one then appears in the operational list (#1819):
   * its mark can no longer be released by anybody, which is exactly what that list is for.
   */
  @Transactional
  public void dismissContact(UUID groupId, UUID userId, CurrentUser caller) {
    Group group = loadGroup(groupId, caller);
    requireProviderGroup(group);
    GroupContact contact =
        contacts
            .findByGroupIdAndUserId(groupId, userId)
            .orElseThrow(() -> new NotFoundException("Ansprechstelle nicht gefunden"));
    contacts.delete(contact);
    recordEvent(AuditEventType.GROUP_CONTACT_DISMISSED, group, userId, caller.id());
  }

  /** Whether this person may set and release the group's protection mark. */
  public boolean isContact(UUID groupId, UUID userId) {
    return contacts.existsByGroupIdAndUserId(groupId, userId);
  }

  /**
   * The contact points of a whole list of groups in one read - the one resolution of a contact
   * point's display name, which {@code GroupService} reads for both group responses.
   */
  public Map<UUID, List<GroupContactView>> contactsOf(Collection<Group> candidates) {
    if (candidates.isEmpty()) {
      return Map.of();
    }
    List<GroupContact> rows =
        contacts.findByGroupIdIn(candidates.stream().map(Group::getId).toList());
    Map<UUID, List<GroupContactView>> byGroup = new HashMap<>();
    for (GroupContactView view : toViews(rows)) {
      byGroup.computeIfAbsent(view.contact().getGroupId(), key -> new ArrayList<>()).add(view);
    }
    return byGroup;
  }

  /** The contact points of one group, in the order they were named. */
  public List<GroupContactView> contactsOf(UUID groupId) {
    return toViews(contacts.findByGroupIdOrderByCreatedAtAsc(groupId));
  }

  /** The groups the caller is the contact point of - the counterpart of "meine Gruppen". */
  public Set<UUID> contactedGroupIds(UUID userId) {
    return contacts.findGroupIdsByUserId(userId);
  }

  /**
   * The appointment follows the membership: whoever is no longer a member of the group is no longer
   * its contact point. Hangs on the one place every membership change passes through, so no sync
   * path - directory run, token sign-in or a hand-made change - can forget it. The end is an audit
   * event without an actor: nobody decided it, the ground simply fell away.
   */
  @Override
  @Transactional
  public void onMembershipChanged(Collection<UUID> userIds) {
    for (UUID userId : userIds) {
      List<GroupContact> appointments = contacts.findByUserId(userId);
      if (appointments.isEmpty()) {
        continue;
      }
      Map<UUID, Group> byId =
          groups
              .findAllByIdWithMemberships(
                  appointments.stream().map(GroupContact::getGroupId).collect(Collectors.toSet()))
              .stream()
              .collect(Collectors.toMap(Group::getId, group -> group));
      for (GroupContact appointment : appointments) {
        Group group = byId.get(appointment.getGroupId());
        boolean stillMember =
            group != null
                && group.getMemberships().stream().anyMatch(m -> m.getUserId().equals(userId));
        if (stillMember) {
          continue;
        }
        contacts.delete(appointment);
        if (group != null) {
          recordEvent(AuditEventType.GROUP_CONTACT_DISMISSED, group, userId, null);
        }
        log.info(
            "Group contact appointment ended with the membership: group {}, user {}",
            appointment.getGroupId(),
            userId);
      }
    }
  }

  private void requireProviderGroup(Group group) {
    if (group.isInternal()) {
      throw new ValidationException(
          "Eine interne Gruppe hat Verantwortliche, keine Ansprechstelle");
    }
  }

  private Group loadGroup(UUID groupId, CurrentUser caller) {
    return OrganizationScopedLoader.load(
        () -> groups.findByIdWithMemberships(groupId),
        Group::getOrganizationId,
        caller.organizationId(),
        "Gruppe nicht gefunden");
  }

  private User requireUserInOrganization(UUID userId, UUID organizationId) {
    return OrganizationScopedLoader.load(
        () -> users.findById(userId),
        User::getOrganizationId,
        organizationId,
        "Benutzer nicht gefunden");
  }

  /**
   * Appointment and dismissal are audit events and deliberately no history rows (ADR-0036,
   * Entscheidungen 4 and 8): the appointment carries no read access. {@code actorId} is null where
   * the appointment ended with the membership rather than by somebody's decision.
   */
  private void recordEvent(AuditEventType type, Group group, UUID userId, UUID actorId) {
    AuditEvent.Builder builder =
        AuditEvent.builder()
            .organizationId(group.getOrganizationId())
            .type(type)
            .object(AuditObjectType.GROUP, group.getId(), group.getName())
            .subject(AuditSubjectKind.USER, userId)
            .outcome(AuditOutcome.SUCCESS);
    if (actorId == null) {
      auditEventRecorder.recordSystemProcessAction(builder.actorRef(SYSTEM_ACTOR).build());
    } else {
      auditEventRecorder.recordUserActionOnSubject(builder.actor(actorId).build());
    }
  }

  private List<GroupContactView> toViews(List<GroupContact> rows) {
    Map<UUID, String> displayNames = displayNamesOf(rows.stream().map(GroupContact::getUserId));
    return rows.stream()
        .map(row -> new GroupContactView(row, displayNames.get(row.getUserId())))
        .toList();
  }

  private String displayNameOf(UUID userId) {
    return displayNamesOf(java.util.stream.Stream.of(userId)).get(userId);
  }

  private Map<UUID, String> displayNamesOf(java.util.stream.Stream<UUID> userIds) {
    return users.displayNamesById(userIds.toList());
  }
}
