package io.opaa.prompt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.api.types.AssetGrantSubjectType;
import io.opaa.api.types.AssetOrigin;
import io.opaa.api.types.AssetOwnerType;
import io.opaa.api.types.AssetRole;
import io.opaa.api.types.GroupKind;
import io.opaa.api.types.PromptVariableType;
import io.opaa.api.types.SpaceRole;
import io.opaa.api.types.SpaceVisibility;
import io.opaa.api.types.SystemRole;
import io.opaa.asset.AssetGrantService;
import io.opaa.asset.AssetGrantUpsert;
import io.opaa.auth.CurrentUser;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.common.AccessDeniedException;
import io.opaa.common.ConflictException;
import io.opaa.common.NotFoundException;
import io.opaa.common.ValidationException;
import io.opaa.group.Group;
import io.opaa.group.GroupMembership;
import io.opaa.group.GroupRepository;
import io.opaa.organization.Organization;
import io.opaa.organization.OrganizationRepository;
import io.opaa.permission.AssetGrantRepository;
import io.opaa.space.Space;
import io.opaa.space.SpaceAssetAssociationService;
import io.opaa.space.SpaceMembership;
import io.opaa.space.SpaceRepository;
import io.opaa.succession.SuccessionDetectionService;
import io.opaa.succession.SuccessionService;
import io.opaa.test.OpaaIntegrationTest;
import io.opaa.test.OwnOrganizationFixtures;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The acceptance criteria of #1901 on the service level: a prompt library owned by a person or a
 * group, filled with prompts, given to a person, a group and the whole organization through the one
 * grant service of the shell; list and single view agreeing for every person (#406); the
 * organization boundary on every way; the frozen reach of an open succession; the variable rules;
 * and one audit entry per change.
 */
@OpaaIntegrationTest
class PromptLibraryServiceIntegrationTest {

  @Autowired private PromptLibraryService libraryService;
  @Autowired private PromptService promptService;
  @Autowired private AssetGrantService grantService;
  @Autowired private AssetGrantRepository grantRepository;
  @Autowired private SpaceAssetAssociationService associationService;
  @Autowired private SpaceRepository spaceRepository;
  @Autowired private SuccessionDetectionService detectionService;
  @Autowired private SuccessionService successionService;
  @Autowired private UserRepository userRepository;
  @Autowired private GroupRepository groupRepository;
  @Autowired private OrganizationRepository organizationRepository;
  @Autowired private OwnOrganizationFixtures ownOrganizationFixtures;
  @Autowired private JdbcTemplate jdbcTemplate;

  private UUID organization;
  private UUID foreignOrganization;
  private UUID owner;
  private UUID reader;
  private UUID member;
  private UUID secondMember;
  private UUID outsider;
  private UUID administrator;
  private UUID foreigner;
  private UUID group;

  @BeforeEach
  void setUp() {
    organization = createOrganization("Prompt-Bibliothek");
    foreignOrganization = createOrganization("Fremde Organisation");
    owner = createUser(organization, "Eigentümerin");
    reader = createUser(organization, "Leserin");
    member = createUser(organization, "Gruppenmitglied");
    secondMember = createUser(organization, "Zweites Mitglied");
    outsider = createUser(organization, "Außenstehende");
    administrator = createUser(organization, "Systemverwaltung");
    foreigner = createUser(foreignOrganization, "Fremde");
    group = createGroup(organization, member, secondMember);
  }

  @AfterEach
  void tearDown() {
    for (UUID id : List.of(organization, foreignOrganization)) {
      jdbcTemplate.update("DELETE FROM assets WHERE organization_id = ?", id);
      jdbcTemplate.update("DELETE FROM asset_grant_history WHERE organization_id = ?", id);
      jdbcTemplate.update("DELETE FROM asset_ownership_history WHERE organization_id = ?", id);
      jdbcTemplate.update("DELETE FROM succession_cases WHERE organization_id = ?", id);
      jdbcTemplate.update("DELETE FROM group_membership_history WHERE organization_id = ?", id);
      jdbcTemplate.update("DELETE FROM group_memberships WHERE organization_id = ?", id);
      jdbcTemplate.update("DELETE FROM groups WHERE organization_id = ?", id);
    }
    ownOrganizationFixtures.removeOrganizations(organization, foreignOrganization);
  }

  @Test
  void aPersonCreatesAPrivateUnlistedLibraryAndOwnsIt() {
    PromptLibraryView created =
        libraryService.create(creation("Formulierungshilfen", null, null), callerOf(owner));

    PromptLibrary library = created.library();
    assertThat(library.getOwnerType()).isEqualTo(AssetOwnerType.USER);
    assertThat(library.getOwnerId()).isEqualTo(owner);
    assertThat(library.isListed()).as("listing is a deliberate act").isFalse();
    assertThat(library.getOrigin()).isEqualTo(AssetOrigin.LOCAL);
    assertThat(library.getAssetType()).isEqualTo(PromptLibrary.ASSET_TYPE);
    assertThat(created.myRole()).isEqualTo(AssetRole.OWNER);
    assertThat(created.ownerName()).isEqualTo("Eigentümerin");
    assertThat(auditEvents(library.getId()))
        .as("the creation, beside the owner's grant the shell records")
        .containsExactlyInAnyOrder("PROMPT_LIBRARY_CREATED", "ASSET_GRANT_GRANTED");
  }

  @Test
  void aGroupOwnsALibraryOneOfItsMembersCreatedAndEveryMemberManagesIt() {
    PromptLibraryView created =
        libraryService.create(
            creation("Referatsvorlagen", AssetOwnerType.GROUP, group), callerOf(member));
    UUID id = created.library().getId();

    assertThat(created.library().getOwnerType()).isEqualTo(AssetOwnerType.GROUP);
    assertThat(created.library().getOwnerId()).isEqualTo(group);
    assertThat(libraryService.get(id, callerOf(secondMember)).myRole())
        .isEqualTo(AssetRole.MANAGER);
    assertThatCode(
            () ->
                promptService.create(
                    id, content("anhoerung", "Anhörung", List.of()), callerOf(secondMember)))
        .doesNotThrowAnyException();
    assertThatThrownBy(
            () ->
                libraryService.create(
                    creation("Fremde Vorlagen", AssetOwnerType.GROUP, group), callerOf(outsider)))
        .isInstanceOf(AccessDeniedException.class)
        .hasMessage(
            "Nur Mitglieder der Gruppe können eine Prompt-Bibliothek in ihrem Namen anlegen");
  }

  @Test
  void aPersonAGroupAndTheOrganizationAreGivenTheLibraryThroughTheShell() {
    UUID id = libraryOf(owner, "Vorlagen");
    promptService.create(id, content("vermerk", "Vermerk", List.of()), callerOf(owner));
    assertThat(readableBy(reader)).doesNotContain(id);

    grantService.upsertGrant(
        PromptLibrary.ASSET_TYPE,
        id,
        new AssetGrantUpsert(AssetGrantSubjectType.USER, reader, AssetRole.VIEWER),
        callerOf(owner));
    grantService.upsertGrant(
        PromptLibrary.ASSET_TYPE,
        id,
        new AssetGrantUpsert(AssetGrantSubjectType.GROUP, group, AssetRole.EDITOR),
        callerOf(owner));

    assertThat(promptService.list(id, callerOf(reader)))
        .extracting(Prompt::getName)
        .containsExactly("vermerk");
    assertThatThrownBy(
            () -> promptService.create(id, content("x", "X", List.of()), callerOf(reader)))
        .as("a VIEWER reads, an EDITOR writes")
        .isInstanceOf(AccessDeniedException.class);
    assertThatCode(
            () ->
                promptService.create(id, content("gruppe", "Gruppe", List.of()), callerOf(member)))
        .doesNotThrowAnyException();
    assertThat(readableBy(outsider)).doesNotContain(id);

    // #1931: Die organisationsweite Reichweite ist eine Freigabe an "Alle Konten" - derselbe Weg
    // wie fuer Person und Gruppe, nur ohne benannten Empfaenger.
    grantService.upsertGrant(
        PromptLibrary.ASSET_TYPE,
        id,
        AssetGrantUpsert.forAllAccounts(AssetRole.VIEWER),
        callerOf(owner));

    assertThat(readableBy(outsider)).contains(id);
    assertThat(promptService.list(id, callerOf(outsider))).hasSize(2);
    assertThat(auditEvents(id))
        .contains(
            "PROMPT_LIBRARY_CREATED",
            "ASSET_GRANT_GRANTED",
            "ASSET_GRANT_GRANTED",
            "ASSET_GRANT_GRANTED");
  }

  /** Regression class #406: the single view never judges a library differently than the list. */
  @Test
  void theSingleViewAndTheListAgreeOnEveryLibraryForEveryPerson() {
    UUID own = libraryOf(owner, "Eigene");
    UUID direct = libraryOf(owner, "Direkt");
    grantService.upsertGrant(
        PromptLibrary.ASSET_TYPE,
        direct,
        new AssetGrantUpsert(AssetGrantSubjectType.USER, reader, AssetRole.VIEWER),
        callerOf(owner));
    UUID viaGroup = libraryOf(owner, "Gruppe");
    grantService.upsertGrant(
        PromptLibrary.ASSET_TYPE,
        viaGroup,
        new AssetGrantUpsert(AssetGrantSubjectType.GROUP, group, AssetRole.VIEWER),
        callerOf(owner));
    UUID organizationWide = libraryOf(owner, "Organisationsweit");
    libraryService.update(
        organizationWide,
        new PromptLibraryUpdate("Organisationsweit", null, false),
        callerOf(owner));
    UUID groupOwned =
        libraryService
            .create(creation("Gruppeneigen", AssetOwnerType.GROUP, group), callerOf(member))
            .library()
            .getId();
    List<UUID> libraries = List.of(own, direct, viaGroup, organizationWide, groupOwned);

    for (UUID person : List.of(owner, reader, member, secondMember, outsider, foreigner)) {
      CurrentUser caller = callerOf(person);
      Set<UUID> listed = readableBy(person);
      for (UUID library : libraries) {
        boolean single = succeeds(() -> libraryService.get(library, caller));
        boolean content = succeeds(() -> promptService.list(library, caller));
        assertThat(single)
            .as("single view of %s for %s", library, person)
            .isEqualTo(listed.contains(library));
        assertThat(content)
            .as("prompts of %s for %s", library, person)
            .isEqualTo(listed.contains(library));
      }
    }
  }

  @Test
  void theOrganizationBoundaryHoldsOnEveryWay() {
    UUID id = libraryOf(owner, "Hausintern");
    libraryService.update(id, new PromptLibraryUpdate("Hausintern", null, false), callerOf(owner));

    assertThat(readableBy(foreigner)).as("the list").doesNotContain(id);
    assertThatThrownBy(() -> libraryService.get(id, callerOf(foreigner)))
        .as("the single view")
        .isInstanceOf(NotFoundException.class);
    assertThatThrownBy(() -> promptService.list(id, callerOf(foreigner)))
        .isInstanceOf(NotFoundException.class);
    assertThatThrownBy(
            () ->
                grantService.upsertGrant(
                    PromptLibrary.ASSET_TYPE,
                    id,
                    new AssetGrantUpsert(AssetGrantSubjectType.USER, foreigner, AssetRole.VIEWER),
                    callerOf(owner)))
        .as("a grant to a person of another organization")
        .isInstanceOf(NotFoundException.class);
    assertThatThrownBy(
            () ->
                grantService.upsertGrant(
                    PromptLibrary.ASSET_TYPE,
                    id,
                    new AssetGrantUpsert(AssetGrantSubjectType.USER, foreigner, AssetRole.VIEWER),
                    callerOf(foreigner)))
        .as("a grant by a person of another organization")
        .isInstanceOf(NotFoundException.class);
    UUID foreignSpace = createSpace(foreigner, foreignOrganization);
    assertThatThrownBy(
            () ->
                associationService.associate(
                    foreignSpace, PromptLibrary.ASSET_TYPE, id, callerOf(foreigner)))
        .as("an association into a space of another organization")
        .isInstanceOf(NotFoundException.class);
    assertThatThrownBy(
            () ->
                associationService.associate(
                    foreignSpace, PromptLibrary.ASSET_TYPE, id, callerOf(owner)))
        .as("its owner cannot hang it into a space of another organization either")
        .isInstanceOf(NotFoundException.class);
    assertThat(grantRepository.findByAssetTypeAndAssetId(PromptLibrary.ASSET_TYPE, id))
        .extracting(grant -> grant.getSubjectId())
        .containsExactly(owner);
  }

  @Test
  void anOwnerWhoLeftOpensTheSuccessionAndAFurtherGrantIsRefusedWithItsReason() {
    UUID id = libraryOf(owner, "Verwaist");
    jdbcTemplate.update("UPDATE users SET directory_locked_at = now() WHERE id = ?", owner);

    detectionService.runFor(organization);

    assertThat(successionService.findingForAsset(PromptLibrary.ASSET_TYPE, id)).isPresent();
    assertThat(libraryService.get(id, callerOf(administrator, true)).succession()).isNotNull();
    assertThatThrownBy(
            () ->
                grantService.upsertGrant(
                    PromptLibrary.ASSET_TYPE,
                    id,
                    new AssetGrantUpsert(AssetGrantSubjectType.USER, reader, AssetRole.VIEWER),
                    callerOf(administrator, true)))
        .isInstanceOf(ConflictException.class)
        .hasMessageStartingWith("Für dieses Objekt ist die Nachfolge offen")
        .hasMessageContaining("Zuständig: die Systemverwaltung");
    assertThatThrownBy(
            () ->
                libraryService.update(
                    id,
                    new PromptLibraryUpdate("Verwaist", null, true),
                    callerOf(administrator, true)))
        .isInstanceOf(ConflictException.class);
  }

  @Test
  void theVariablesAreCheckedAgainstTheTextInBothDirections() {
    UUID id = libraryOf(owner, "Variablen");
    PromptVariable aktenzeichen =
        new PromptVariable(
            "aktenzeichen", "Aktenzeichen", PromptVariableType.TEXT, true, null, List.of());

    assertThatThrownBy(
            () ->
                promptService.create(
                    id,
                    new PromptContent(
                        "undefiniert", "Titel", null, "Zu {{aktenzeichen}}", List.of(), 0),
                    callerOf(owner)))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("nicht definiert");
    assertThatThrownBy(
            () ->
                promptService.create(
                    id,
                    new PromptContent(
                        "ungenutzt", "Titel", null, "Ohne Variable", List.of(aktenzeichen), 0),
                    callerOf(owner)))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("nicht verwendet");

    Prompt created =
        promptService.create(
            id,
            new PromptContent(
                "anhoerung",
                "Anhörung",
                null,
                "Anhörung zu {{aktenzeichen}}, Stand {{CURRENT_DATE}}",
                List.of(aktenzeichen),
                0),
            callerOf(owner));

    assertThat(promptService.get(id, created.getId(), callerOf(owner)).getVariables())
        .containsExactly(aktenzeichen);
  }

  @Test
  void blanksInPlaceholdersAreStoredNormalizedAndResavingThemChangesNothing() {
    UUID id = libraryOf(owner, "Schreibweisen");
    PromptVariable aktenzeichen =
        new PromptVariable(
            "aktenzeichen", "Aktenzeichen", PromptVariableType.TEXT, true, null, List.of());
    PromptContent spaced =
        new PromptContent(
            "anhoerung",
            "Anhörung",
            null,
            "Zu {{ aktenzeichen }}, Stand {{ current_date }}",
            List.of(aktenzeichen),
            0);

    Prompt created = promptService.create(id, spaced, callerOf(owner));
    promptService.update(id, created.getId(), spaced, callerOf(owner));

    assertThat(promptService.get(id, created.getId(), callerOf(owner)).getText())
        .isEqualTo("Zu {{aktenzeichen}}, Stand {{CURRENT_DATE}}");
    assertThat(auditEvents(created.getId()))
        .as("the same text in another spelling is no change")
        .containsExactly("PROMPT_CREATED");
  }

  @Test
  void everyChangeOfLibraryAndPromptWritesOneAuditEntry() {
    UUID id = libraryOf(owner, "Protokoll");
    Prompt prompt =
        promptService.create(id, content("vermerk", "Vermerk", List.of()), callerOf(owner));
    promptService.update(
        id, prompt.getId(), content("vermerk", "Vermerk neu", List.of()), callerOf(owner));
    promptService.update(
        id, prompt.getId(), content("vermerk", "Vermerk neu", List.of()), callerOf(owner));
    libraryService.update(
        id, new PromptLibraryUpdate("Protokoll neu", "Beschreibung", false), callerOf(owner));
    promptService.delete(id, prompt.getId(), callerOf(owner));

    assertThat(auditEvents(prompt.getId()))
        .as("an unchanged replace writes nothing")
        .containsExactly("PROMPT_CREATED", "PROMPT_CHANGED", "PROMPT_DELETED");
    assertThat(auditEvents(id))
        .filteredOn(event -> event.startsWith("PROMPT_LIBRARY"))
        .containsExactly("PROMPT_LIBRARY_CREATED", "PROMPT_LIBRARY_CHANGED");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM audit_log WHERE object_id = ? AND object_type = 'PROMPT'"
                    + " AND (coalesce(before::text, '') || coalesce(after::text, ''))"
                    + " LIKE '%Vermerk%'",
                Long.class, prompt.getId().toString()))
        .as("neither title nor text enter the log")
        .isZero();
  }

  @Test
  void deletingTheLibraryTakesPromptsGrantsAndAssociationsWithIt() {
    UUID id = libraryOf(owner, "Weg damit");
    promptService.create(id, content("eins", "Eins", List.of()), callerOf(owner));
    promptService.create(id, content("zwei", "Zwei", List.of()), callerOf(owner));
    grantService.upsertGrant(
        PromptLibrary.ASSET_TYPE,
        id,
        new AssetGrantUpsert(AssetGrantSubjectType.USER, reader, AssetRole.MANAGER),
        callerOf(owner));
    UUID space = createSpace(owner, organization);
    associationService.associate(space, PromptLibrary.ASSET_TYPE, id, callerOf(owner));

    assertThatThrownBy(() -> libraryService.delete(id, callerOf(reader)))
        .as("deleting needs OWNER")
        .isInstanceOf(AccessDeniedException.class);
    libraryService.delete(id, callerOf(owner));

    assertThat(count("SELECT count(*) FROM prompts WHERE library_id = ?", id)).isZero();
    assertThat(count("SELECT count(*) FROM prompt_libraries WHERE id = ?", id)).isZero();
    assertThat(count("SELECT count(*) FROM asset_grants WHERE asset_id = ?", id)).isZero();
    assertThat(count("SELECT count(*) FROM space_asset_associations WHERE asset_id = ?", id))
        .isZero();
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT before::jsonb ->> 'promptsRemoved' FROM audit_log WHERE object_id = ?"
                    + " AND event_type = 'PROMPT_LIBRARY_DELETED'",
                String.class,
                id.toString()))
        .isEqualTo("2");
  }

  @Test
  void administeringALibraryIsNotReadingItsPrompts() {
    UUID id = libraryOf(owner, "Vertraulich");
    promptService.create(id, content("geheim", "Geheim", List.of()), callerOf(owner));

    assertThat(libraryService.get(id, callerOf(administrator, true)).myRole())
        .isEqualTo(AssetRole.OWNER);
    assertThatThrownBy(() -> promptService.list(id, callerOf(administrator, true)))
        .isInstanceOf(AccessDeniedException.class)
        .hasMessage(
            "Für diese Prompt-Bibliothek liegt keine Leseberechtigung vor; Verwaltungsrechte"
                + " genügen dafür nicht.");
    assertThat(readableBy(administrator)).doesNotContain(id);
  }

  @Test
  void aPromptNameIsUniqueWithinItsLibrary() {
    UUID id = libraryOf(owner, "Namen");
    promptService.create(id, content("vermerk", "Vermerk", List.of()), callerOf(owner));

    assertThatThrownBy(
            () ->
                promptService.create(id, content("vermerk", "Nochmal", List.of()), callerOf(owner)))
        .isInstanceOf(ConflictException.class)
        .hasMessageContaining("„vermerk“");
    assertThatThrownBy(
            () -> promptService.create(id, content("Vermerk", "Groß", List.of()), callerOf(owner)))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void creatingNeedsTheCapabilityDeliveredToAllAccounts() {
    assertThatCode(() -> libraryOf(outsider, "Darf ich")).doesNotThrowAnyException();

    jdbcTemplate.update(
        "DELETE FROM capability_grants WHERE organization_id = ?"
            + " AND capability = 'CREATE_PROMPT_LIBRARY'",
        organization);

    assertThatThrownBy(() -> libraryOf(outsider, "Jetzt nicht mehr"))
        .isInstanceOf(AccessDeniedException.class)
        .hasMessageContaining("Prompt-Bibliotheken anlegen");
  }

  private UUID libraryOf(UUID person, String name) {
    return libraryService.create(creation(name, null, null), callerOf(person)).library().getId();
  }

  private Set<UUID> readableBy(UUID person) {
    return libraryService.list(callerOf(person)).stream()
        .map(view -> view.library().getId())
        .collect(Collectors.toSet());
  }

  private static boolean succeeds(Runnable action) {
    try {
      action.run();
      return true;
    } catch (NotFoundException | AccessDeniedException refused) {
      return false;
    }
  }

  private List<String> auditEvents(UUID objectId) {
    return jdbcTemplate.queryForList(
        "SELECT event_type FROM audit_log WHERE object_id = ? ORDER BY recorded_at, event_id",
        String.class,
        objectId.toString());
  }

  private long count(String sql, UUID id) {
    return jdbcTemplate.queryForObject(sql, Long.class, id);
  }

  private static PromptLibraryCreation creation(
      String name, AssetOwnerType ownerType, UUID ownerId) {
    return new PromptLibraryCreation(name, null, ownerType, ownerId, null);
  }

  private static PromptContent content(String name, String title, List<PromptVariable> variables) {
    return new PromptContent(name, title, null, "Bitte formulieren.", variables, 0);
  }

  private UUID createOrganization(String name) {
    return organizationRepository
        .save(new Organization(UUID.randomUUID(), name + " " + UUID.randomUUID()))
        .getId();
  }

  private UUID createUser(UUID organizationId, String displayName) {
    User user =
        new User(
            "prompt-" + UUID.randomUUID(),
            "https://issuer.example",
            UUID.randomUUID() + "@example.com",
            displayName);
    user.setOrganizationId(organizationId);
    return userRepository.save(user).getId();
  }

  private UUID createGroup(UUID organizationId, UUID... members) {
    Group created =
        new Group(
            organizationId,
            GroupKind.AD_HOC,
            "Referat 50",
            "Ad-hoc-Gruppe",
            null,
            null,
            null,
            null);
    created.release(true);
    for (UUID memberId : members) {
      created.addMembership(new GroupMembership(memberId, organizationId));
    }
    return groupRepository.save(created).getId();
  }

  private UUID createSpace(UUID admin, UUID organizationId) {
    Space space = new Space("Space", null, false, SpaceVisibility.PRIVATE, admin, organizationId);
    space.addMembership(SpaceMembership.ofUser(admin, SpaceRole.ADMIN, organizationId));
    return spaceRepository.save(space).getId();
  }

  private CurrentUser callerOf(UUID userId) {
    return callerOf(userId, false);
  }

  private CurrentUser callerOf(UUID userId, boolean systemAdmin) {
    UUID organizationId = userId.equals(foreigner) ? foreignOrganization : this.organization;
    return CurrentUser.of(
        userId,
        organizationId,
        systemAdmin ? SystemRole.SYSTEM_ADMIN : SystemRole.USER,
        "Sachbearbeitung");
  }
}
