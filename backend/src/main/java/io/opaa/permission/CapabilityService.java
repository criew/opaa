package io.opaa.permission;

import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditObjectType;
import io.opaa.api.types.AuditOutcome;
import io.opaa.api.types.AuditSubjectKind;
import io.opaa.api.types.Capability;
import io.opaa.api.types.CapabilitySubjectType;
import io.opaa.audit.AuditEvent;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.auth.CurrentUser;
import io.opaa.auth.UserRepository;
import io.opaa.common.AccessDeniedException;
import io.opaa.common.ConflictException;
import io.opaa.common.NotFoundException;
import io.opaa.common.ValidationException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The one evaluation of an installation-wide {@link Capability} (ADR-0036, Entscheidung 5): {@code
 * hasCapability(user, capability) = SYSTEM_ADMIN OR grant to ALL_ACCOUNTS OR grant to the account
 * OR grant to one of its groups}. A capability opens a creation path and never an existing content
 * - nothing in this class widens what {@code LibraryAccessService#readableLibraryIds} returns, and
 * the role {@code AUDITOR} confers no capability of its own.
 *
 * <p><b>Deliberately uncached</b>, unlike {@link AssetAccessService#effectiveRole}: a withdrawal
 * has to take effect without a new sign-in, and a check runs once per creation attempt - a handful
 * of requests per session against one indexed query. A cache would buy nothing measurable and would
 * add a second invalidation path that has to be right for the guarantee to hold. The group half
 * still goes through {@link GroupMembershipResolver}, whose cache is invalidated after commit.
 */
@Service
public class CapabilityService {

  /** The stable {@code code} of the {@code 403} a missing capability produces. */
  public static final String CAPABILITY_REQUIRED = "CAPABILITY_REQUIRED";

  /**
   * The German name of each capability, used in the refusal, in the audit entry and in the
   * administration overview's plain-text line. "Anlegerecht" is the term the interface and the
   * manual use for a capability (ADR-0036, Begriffe).
   */
  private static final Map<Capability, String> LABELS =
      Map.of(
          Capability.CREATE_SPACE, "Spaces anlegen",
          Capability.CREATE_LIBRARY, "Bibliotheken für Uploads anlegen",
          Capability.CREATE_CONNECTOR_LIBRARY, "Konnektorbibliotheken anlegen",
          Capability.CREATE_INTERNAL_GROUP, "Interne Gruppen anlegen");

  /**
   * What each capability creates, as the object of a sentence - the piece the overview's plain-text
   * line puts between "Alle Konten dürfen" and "anlegen". Separate from {@link #LABELS} because a
   * German noun that opens a label is capitalised and the same noun inside a sentence is not.
   */
  private static final Map<Capability, String> CREATABLES =
      Map.of(
          Capability.CREATE_SPACE, "Spaces",
          Capability.CREATE_LIBRARY, "Bibliotheken für Uploads",
          Capability.CREATE_CONNECTOR_LIBRARY, "Konnektorbibliotheken",
          Capability.CREATE_INTERNAL_GROUP, "interne Gruppen");

  private final CapabilityGrantRepository grantRepository;
  private final GroupMembershipResolver membershipResolver;
  private final GroupSubjectDirectory groupDirectory;
  private final UserRepository userRepository;
  private final PermissionHistoryService permissionHistoryService;
  private final AuditEventRecorder auditEventRecorder;

  CapabilityService(
      CapabilityGrantRepository grantRepository,
      GroupMembershipResolver membershipResolver,
      GroupSubjectDirectory groupDirectory,
      UserRepository userRepository,
      PermissionHistoryService permissionHistoryService,
      AuditEventRecorder auditEventRecorder) {
    this.grantRepository = grantRepository;
    this.membershipResolver = membershipResolver;
    this.groupDirectory = groupDirectory;
    this.userRepository = userRepository;
    this.permissionHistoryService = permissionHistoryService;
    this.auditEventRecorder = auditEventRecorder;
  }

  /** The German name of a capability - the one wording the interface and the manual use. */
  public static String label(Capability capability) {
    return LABELS.get(capability);
  }

  /** What the capability creates, as the object of a sentence - see {@code CREATABLES}. */
  public static String creatable(Capability capability) {
    return CREATABLES.get(capability);
  }

  /**
   * Every capability the caller holds. A system administrator holds all of them implicitly: the
   * capabilities sit below that role, they never sit beside it.
   */
  @Transactional(readOnly = true)
  public Set<Capability> capabilitiesOf(CurrentUser caller) {
    if (caller.isSystemAdmin()) {
      return EnumSet.allOf(Capability.class);
    }
    Set<Capability> held = EnumSet.noneOf(Capability.class);
    held.addAll(
        grantRepository.findCapabilitiesGrantedDirectly(caller.organizationId(), caller.id()));
    Set<UUID> groupIds = membershipResolver.groupIdsForUser(caller.id());
    if (!groupIds.isEmpty()) {
      held.addAll(
          grantRepository.findCapabilitiesGrantedToGroups(caller.organizationId(), groupIds));
    }
    return held;
  }

  @Transactional(readOnly = true)
  public boolean hasCapability(CurrentUser caller, Capability capability) {
    return capabilitiesOf(caller).contains(capability);
  }

  /**
   * Refuses the call with {@code 403} unless the caller holds {@code capability}. The message names
   * the missing right and who to turn to, because the interface is meant to explain rather than
   * hide (ADR-0036, Entscheidung 5); the {@code code} lets it say so before the attempt.
   */
  public void requireCapability(CurrentUser caller, Capability capability) {
    if (!hasCapability(caller, capability)) {
      throw new AccessDeniedException(
          "Ihnen fehlt das Anlegerecht „"
              + label(capability)
              + "“. Wenden Sie sich an die Systemverwaltung, wenn Sie es benötigen.",
          CAPABILITY_REQUIRED);
    }
  }

  // -------------------------------------------------------------------------------------------
  // Administration
  // -------------------------------------------------------------------------------------------

  /**
   * Every capability of the organization with the subjects holding it, names resolved in one query
   * per subject kind rather than one per row.
   */
  @Transactional(readOnly = true)
  public List<CapabilityOverview> overview(UUID organizationId) {
    List<CapabilityGrant> grants = grantRepository.findByOrganizationId(organizationId);
    Map<UUID, String> names = subjectNames(grants);

    Map<Capability, List<CapabilityGrantView>> byCapability = new LinkedHashMap<>();
    for (Capability capability : Capability.values()) {
      byCapability.put(capability, new ArrayList<>());
    }
    for (CapabilityGrant grant : grants) {
      byCapability
          .get(grant.getCapability())
          .add(new CapabilityGrantView(grant, names.get(grant.getSubjectId())));
    }
    return byCapability.entrySet().stream()
        .map(entry -> new CapabilityOverview(entry.getKey(), List.copyOf(entry.getValue())))
        .toList();
  }

  /** The display name of one grant's subject - {@code null} for {@code ALL_ACCOUNTS}. */
  @Transactional(readOnly = true)
  public String subjectName(CapabilityGrant grant) {
    return subjectNames(List.of(grant)).get(grant.getSubjectId());
  }

  private Map<UUID, String> subjectNames(List<CapabilityGrant> grants) {
    Map<UUID, String> names = new LinkedHashMap<>();
    List<UUID> userIds =
        grants.stream()
            .filter(grant -> grant.getSubjectType() == CapabilitySubjectType.USER)
            .map(CapabilityGrant::getSubjectUserId)
            .toList();
    if (!userIds.isEmpty()) {
      userRepository
          .findAllById(userIds)
          .forEach(user -> names.put(user.getId(), user.getDisplayName()));
    }
    List<UUID> groupIds =
        grants.stream()
            .filter(grant -> grant.getSubjectType() == CapabilitySubjectType.GROUP)
            .map(CapabilityGrant::getSubjectGroupId)
            .toList();
    if (!groupIds.isEmpty()) {
      names.putAll(groupDirectory.namesById(groupIds));
    }
    return names;
  }

  /**
   * Grants {@code capability} to the named subject. Idempotent by conflict rather than by silence:
   * an existing grant is a {@code 409}, so the administration never reports a change it did not
   * make.
   */
  @Transactional
  public CapabilityGrant grant(
      Capability capability, CapabilitySubjectType subjectType, UUID subjectId, CurrentUser actor) {
    UUID organizationId = actor.organizationId();
    // Ahead of the conflict check on purpose: a request that names a subject where none may stand
    // is malformed, and answering it with the 409 of the grant it did not ask for would tell the
    // caller their request went through in a different shape.
    if (subjectType == CapabilitySubjectType.ALL_ACCOUNTS && subjectId != null) {
      throw new ValidationException(
          "subjectType ALL_ACCOUNTS benennt kein Subjekt - subjectId ist hier unzulässig");
    }
    requireNoExistingGrant(capability, subjectType, subjectId, organizationId);

    CapabilityGrant grant =
        switch (subjectType) {
          case USER -> {
            requireUserInOrganization(subjectId, organizationId);
            yield CapabilityGrant.forUser(organizationId, capability, subjectId, actor.id());
          }
          case GROUP -> {
            requireEffectiveGroup(subjectId, organizationId);
            yield CapabilityGrant.forGroup(organizationId, capability, subjectId, actor.id());
          }
          case ALL_ACCOUNTS ->
              CapabilityGrant.forAllAccounts(organizationId, capability, actor.id());
        };

    CapabilityGrant saved = grantRepository.save(grant);
    permissionHistoryService.recordCapabilityGranted(saved, actor.id());
    recordGovernanceEvent(AuditEventType.CAPABILITY_GRANTED, saved, actor);
    return saved;
  }

  /**
   * Withdraws one grant. The capability takes effect again for nobody it reached only through it.
   */
  @Transactional
  public void revoke(Capability capability, UUID grantId, CurrentUser actor) {
    CapabilityGrant grant =
        grantRepository
            .findByIdAndOrganizationId(grantId, actor.organizationId())
            .filter(existing -> existing.getCapability() == capability)
            .orElseThrow(() -> new NotFoundException("Anlegerecht nicht gefunden"));

    permissionHistoryService.recordCapabilityRevoked(grant, actor.id());
    recordGovernanceEvent(AuditEventType.CAPABILITY_REVOKED, grant, actor);
    grantRepository.delete(grant);
  }

  private void requireNoExistingGrant(
      Capability capability,
      CapabilitySubjectType subjectType,
      UUID subjectId,
      UUID organizationId) {
    boolean exists =
        switch (subjectType) {
          case USER ->
              grantRepository
                  .findByOrganizationIdAndCapabilityAndSubjectTypeAndSubjectUserId(
                      organizationId, capability, subjectType, subjectId)
                  .isPresent();
          case GROUP ->
              grantRepository
                  .findByOrganizationIdAndCapabilityAndSubjectTypeAndSubjectGroupId(
                      organizationId, capability, subjectType, subjectId)
                  .isPresent();
          case ALL_ACCOUNTS ->
              grantRepository
                  .findByOrganizationIdAndCapabilityAndSubjectType(
                      organizationId, capability, subjectType)
                  .isPresent();
        };
    if (exists) {
      throw new ConflictException("Dieses Anlegerecht besteht für dieses Subjekt bereits");
    }
  }

  private void requireUserInOrganization(UUID userId, UUID organizationId) {
    if (userId == null) {
      throw new ValidationException("subjectId ist erforderlich, wenn subjectType USER ist");
    }
    boolean belongs =
        userRepository
            .findById(userId)
            .map(user -> user.getOrganizationId().equals(organizationId))
            .orElse(false);
    if (!belongs) {
      throw new NotFoundException("Konto nicht gefunden");
    }
  }

  /**
   * The same "wirksame Gruppe" rule {@code AssetGrantService#requireGrantableGroup} applies to an
   * asset grant (ADR-0036, Begriffe): a dissolved group, or one of a switched-off identity
   * provider, keeps the rights it has but receives no new one.
   */
  private void requireEffectiveGroup(UUID groupId, UUID organizationId) {
    if (groupId == null) {
      throw new ValidationException("subjectId ist erforderlich, wenn subjectType GROUP ist");
    }
    GroupSubject group =
        groupDirectory
            .find(groupId)
            .filter(found -> found.organizationId().equals(organizationId))
            .orElseThrow(() -> new NotFoundException("Gruppe nicht gefunden"));
    if (group.dissolved()) {
      throw new ValidationException(
          "Die Gruppe ist aufgelöst und kann kein neues Anlegerecht mehr erhalten");
    }
    if (group.providerDisabled()) {
      throw new ValidationException(
          "Der Identitätsanbieter dieser Gruppe ist deaktiviert. Sie kann kein neues Anlegerecht"
              + " erhalten, solange er es bleibt; bestehende Rechte bleiben unverändert.");
    }
  }

  /**
   * A capability change is a governance event, not an ordinary permission change: withdrawing one
   * from all accounts changes the working conditions of every employee. The object is the
   * capability itself, under a fixed id derived from its name, so the personnel council's extract
   * groups every change of one capability together; the subject is the account or group it was
   * granted to, and absent for {@code ALL_ACCOUNTS}, which names nobody.
   */
  private void recordGovernanceEvent(
      AuditEventType type, CapabilityGrant grant, CurrentUser actor) {
    AuditEvent.Builder event =
        AuditEvent.builder()
            .organizationId(grant.getOrganizationId())
            .actor(actor.id())
            .type(type)
            .object(
                AuditObjectType.SYSTEM_SETTING,
                capabilityObjectId(grant.getCapability()),
                "Anlegerecht: " + label(grant.getCapability()))
            .outcome(AuditOutcome.SUCCESS);
    Map<String, Object> payload =
        Map.of(
            "capability",
            grant.getCapability().name(),
            "subjectType",
            grant.getSubjectType().name());
    if (type == AuditEventType.CAPABILITY_GRANTED) {
      event.after(payload);
    } else {
      event.before(payload);
    }
    if (grant.getSubjectType() == CapabilitySubjectType.ALL_ACCOUNTS) {
      auditEventRecorder.recordUserAction(event.build());
      return;
    }
    AuditSubjectKind subjectKind =
        grant.getSubjectType() == CapabilitySubjectType.USER
            ? AuditSubjectKind.USER
            : AuditSubjectKind.GROUP;
    auditEventRecorder.recordUserActionOnSubject(
        event.subject(subjectKind, grant.getSubjectId()).build());
  }

  /**
   * The fixed, well-known audit object id of one capability - it is an installation-wide setting,
   * not an entity with an id of its own, exactly like {@code
   * PermissionHistoryRetentionService#SETTINGS_OBJECT_ID}.
   */
  static UUID capabilityObjectId(Capability capability) {
    return UUID.nameUUIDFromBytes(
        ("io.opaa.permission.capability:" + capability.name()).getBytes(StandardCharsets.UTF_8));
  }
}
