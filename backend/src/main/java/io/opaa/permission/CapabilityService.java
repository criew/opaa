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
import java.util.HashSet;
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
 * <p>{@code CREATE_CONNECTOR_LIBRARY} is granted per scope (ADR-0036, Nachtrag of 03.10.2026):
 * every check of it names the scope, and a check without one is a programming error. Which scopes
 * exist and what they are called answers {@link CapabilityScopeCatalog}.
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
          Capability.CREATE_INTERNAL_GROUP, "Interne Gruppen anlegen",
          Capability.CREATE_PROMPT_LIBRARY, "Prompt-Bibliotheken anlegen");

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
          Capability.CREATE_INTERNAL_GROUP, "interne Gruppen",
          Capability.CREATE_PROMPT_LIBRARY, "Prompt-Bibliotheken");

  /** The capabilities granted per scope. */
  private static final Set<Capability> SCOPED = EnumSet.of(Capability.CREATE_CONNECTOR_LIBRARY);

  private final CapabilityGrantRepository grantRepository;
  private final GroupMembershipResolver membershipResolver;
  private final GroupSubjectDirectory groupDirectory;
  private final UserRepository userRepository;
  private final PermissionHistoryService permissionHistoryService;
  private final AuditEventRecorder auditEventRecorder;
  private final CapabilityScopeCatalog scopeCatalog;

  CapabilityService(
      CapabilityGrantRepository grantRepository,
      GroupMembershipResolver membershipResolver,
      GroupSubjectDirectory groupDirectory,
      UserRepository userRepository,
      PermissionHistoryService permissionHistoryService,
      AuditEventRecorder auditEventRecorder,
      CapabilityScopeCatalog scopeCatalog) {
    this.grantRepository = grantRepository;
    this.membershipResolver = membershipResolver;
    this.groupDirectory = groupDirectory;
    this.userRepository = userRepository;
    this.permissionHistoryService = permissionHistoryService;
    this.auditEventRecorder = auditEventRecorder;
    this.scopeCatalog = scopeCatalog;
  }

  /** Whether {@code capability} is granted per scope. */
  public static boolean isScoped(Capability capability) {
    return SCOPED.contains(capability);
  }

  /**
   * The refusal of a scoped capability, also the notice before the attempt: names the right, the
   * scope and who to turn to.
   */
  public static String missingInScope(Capability capability, String scopeLabel) {
    return "Ihnen fehlt das Anlegerecht „"
        + label(capability)
        + "“ für "
        + scopeLabel
        + ". Wenden Sie sich an die Systemverwaltung, wenn Sie es benötigen.";
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
   * Every capability the caller holds, a scoped one once it is held in at least one scope. A system
   * administrator holds all of them implicitly: the capabilities sit below that role, they never
   * sit beside it.
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
    requireUnscoped(capability);
    return capabilitiesOf(caller).contains(capability);
  }

  /** The scopes of {@code capability} the caller holds. */
  @Transactional(readOnly = true)
  public HeldScopes scopesOf(CurrentUser caller, Capability capability) {
    requireScoped(capability);
    if (caller.isSystemAdmin()) {
      return HeldScopes.ALL;
    }
    Set<String> held =
        new HashSet<>(
            grantRepository.findScopesGrantedDirectly(
                caller.organizationId(), capability, caller.id()));
    Set<UUID> groupIds = membershipResolver.groupIdsForUser(caller.id());
    if (!groupIds.isEmpty()) {
      held.addAll(
          grantRepository.findScopesGrantedToGroups(caller.organizationId(), capability, groupIds));
    }
    return new HeldScopes(false, held);
  }

  @Transactional(readOnly = true)
  public boolean hasCapability(CurrentUser caller, Capability capability, String scope) {
    return scopesOf(caller, capability).covers(scope);
  }

  /** The scoped counterpart of {@link #requireCapability(CurrentUser, Capability)}. */
  public void requireCapability(
      CurrentUser caller, Capability capability, String scope, String scopeLabel) {
    if (!hasCapability(caller, capability, scope)) {
      throw new AccessDeniedException(missingInScope(capability, scopeLabel), CAPABILITY_REQUIRED);
    }
  }

  /**
   * Refuses the call with {@code 403} unless the caller holds {@code capability}. The message names
   * the missing right and who to turn to, because the interface is meant to explain rather than
   * hide (ADR-0036, Entscheidung 5); the {@code code} lets it say so before the attempt.
   */
  public void requireCapability(CurrentUser caller, Capability capability) {
    if (!hasCapability(caller, capability)) {
      throw new AccessDeniedException(missing(capability), CAPABILITY_REQUIRED);
    }
  }

  /** The refusal of a missing capability, also the notice before the attempt. */
  public static String missing(Capability capability) {
    return "Ihnen fehlt das Anlegerecht „"
        + label(capability)
        + "“. Wenden Sie sich an die Systemverwaltung, wenn Sie es benötigen.";
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

    List<CapabilityOverview> overviews = new ArrayList<>();
    for (Capability capability : Capability.values()) {
      Map<String, List<CapabilityGrantView>> byScope = new LinkedHashMap<>();
      Map<String, String> labels = new LinkedHashMap<>();
      if (isScoped(capability)) {
        for (CapabilityScopeCatalog.CapabilityScope scope : scopeCatalog.scopes(capability)) {
          byScope.put(scope.value(), new ArrayList<>());
          labels.put(scope.value(), scope.label());
        }
      } else {
        byScope.put(null, new ArrayList<>());
      }
      for (CapabilityGrant grant : grants) {
        if (grant.getCapability() == capability) {
          byScope
              .computeIfAbsent(grant.getScope(), unknown -> new ArrayList<>())
              .add(new CapabilityGrantView(grant, names.get(grant.getSubjectId())));
        }
      }
      byScope.forEach(
          (scope, views) ->
              overviews.add(
                  new CapabilityOverview(
                      capability,
                      scope,
                      scope == null ? null : labels.getOrDefault(scope, scope),
                      List.copyOf(views))));
    }
    return overviews;
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
      // #1820, ADR-0036 Entscheidung 9: Die Liste der Anlegerechte ist eine fremde Liste - eine
      // geschuetzte Gruppe steht dort ohne ihren Namen.
      names.putAll(groupDirectory.displayNamesById(groupIds));
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
    return grant(capability, null, subjectType, subjectId, actor);
  }

  /**
   * {@link #grant(Capability, CapabilitySubjectType, UUID, CurrentUser)} in {@code scope}, which a
   * scoped capability requires and every other one refuses; the scope must be one the {@link
   * CapabilityScopeCatalog} knows.
   */
  @Transactional
  public CapabilityGrant grant(
      Capability capability,
      String scope,
      CapabilitySubjectType subjectType,
      UUID subjectId,
      CurrentUser actor) {
    UUID organizationId = actor.organizationId();
    requireKnownScope(capability, scope);
    // Ahead of the conflict check on purpose: a request that names a subject where none may stand
    // is malformed, and answering it with the 409 of the grant it did not ask for would tell the
    // caller their request went through in a different shape.
    if (subjectType == CapabilitySubjectType.ALL_ACCOUNTS && subjectId != null) {
      throw new ValidationException(
          "subjectType ALL_ACCOUNTS benennt kein Subjekt - subjectId ist hier unzulässig");
    }
    if (grantRepository
        .findGrant(organizationId, capability, scope, subjectType, subjectId)
        .isPresent()) {
      throw new ConflictException("Dieses Anlegerecht besteht für dieses Subjekt bereits");
    }

    CapabilityGrant grant =
        switch (subjectType) {
          case USER -> {
            requireUserInOrganization(subjectId, organizationId);
            yield CapabilityGrant.forUser(organizationId, capability, scope, subjectId, actor.id());
          }
          case GROUP -> {
            requireEffectiveGroup(subjectId, organizationId);
            yield CapabilityGrant.forGroup(
                organizationId, capability, scope, subjectId, actor.id());
          }
          case ALL_ACCOUNTS ->
              CapabilityGrant.forAllAccounts(organizationId, capability, scope, actor.id());
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

  /**
   * Withdraws every grant of {@code capability} in {@code scope} in every organization - what
   * removing an installation-wide target takes with it. Each withdrawal closes its interval and is
   * a governance event, exactly like {@link #revoke}; in another organization than the actor's, the
   * interval carries no actor.
   */
  @Transactional
  public int revokeScope(Capability capability, String scope, CurrentUser actor) {
    requireScoped(capability);
    List<CapabilityGrant> grants = grantRepository.findByCapabilityAndScope(capability, scope);
    for (CapabilityGrant grant : grants) {
      boolean ownOrganization = grant.getOrganizationId().equals(actor.organizationId());
      permissionHistoryService.recordCapabilityRevoked(grant, ownOrganization ? actor.id() : null);
      recordGovernanceEvent(AuditEventType.CAPABILITY_REVOKED, grant, actor);
      grantRepository.delete(grant);
    }
    return grants.size();
  }

  private void requireKnownScope(Capability capability, String scope) {
    if (!isScoped(capability)) {
      if (scope != null) {
        throw new ValidationException(
            "scope gehört nur zu CREATE_CONNECTOR_LIBRARY, nicht zu " + capability.name());
      }
      return;
    }
    if (scope == null || scope.isBlank()) {
      throw new ValidationException(
          "scope ist für " + capability.name() + " erforderlich: eine Quellart oder ein Zugang");
    }
    boolean known =
        scopeCatalog.scopes(capability).stream().anyMatch(entry -> entry.value().equals(scope));
    if (!known) {
      throw new ValidationException("Unbekannter Geltungsbereich: " + scope);
    }
  }

  private static void requireScoped(Capability capability) {
    if (!isScoped(capability)) {
      throw new IllegalArgumentException(capability + " has no scope");
    }
  }

  private static void requireUnscoped(Capability capability) {
    if (isScoped(capability)) {
      throw new IllegalArgumentException(capability + " is checked in a scope");
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
   *
   * <p>The release of ADR-0036, Entscheidung 9 is deliberately <b>not</b> asked here: every caller
   * of {@link #grant} is {@code SYSTEM_ADMIN}-only ({@code CapabilityController}, held by {@code
   * CapabilityEnforcementIntegrationTest#managingCapabilitiesIsReservedToTheSystemAdministration}),
   * and {@code GroupSubjectDirectory#isSelectableBy} answers true for a system administrator
   * whatever the release says. Opening this path to another role means adding the check.
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
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("capability", grant.getCapability().name());
    if (grant.getScope() != null) {
      payload.put("scope", grant.getScope());
    }
    payload.put("subjectType", grant.getSubjectType().name());
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
