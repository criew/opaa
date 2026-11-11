package io.opaa.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.api.types.AgentPlanStep;
import io.opaa.api.types.AssetGrantSubjectType;
import io.opaa.api.types.AssetRole;
import io.opaa.api.types.Capability;
import io.opaa.api.types.SystemRole;
import io.opaa.asset.AssetGrantService;
import io.opaa.asset.AssetGrantUpsert;
import io.opaa.auth.CurrentUser;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.common.AccessDeniedException;
import io.opaa.common.FieldValidationException;
import io.opaa.common.NotFoundException;
import io.opaa.knowledge.SourceType;
import io.opaa.library.KnowledgeLibraryService;
import io.opaa.library.LibraryCreationBuilder;
import io.opaa.organization.Organization;
import io.opaa.organization.OrganizationRepository;
import io.opaa.permission.CapabilityService;
import io.opaa.template.DocumentTemplateCreation;
import io.opaa.template.DocumentTemplateService;
import io.opaa.template.DocumentTemplateSettingsService;
import io.opaa.template.TestDocx;
import io.opaa.test.OpaaIntegrationTest;
import io.opaa.test.OwnLibraryFixtures;
import io.opaa.test.OwnOrganizationFixtures;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The acceptance criteria on the service level: the switch hides every agent and the right, the
 * right is delivered to nobody, a library or template assignment never widens what the editing
 * person reads, list and single view agree, each change leaves one audit entry and switching on
 * provisions "Recherche" once per organization.
 */
@OpaaIntegrationTest
class AgentServiceIntegrationTest {

  @Autowired private AgentService agentService;
  @Autowired private AgentCurrentRevisions currentRevisions;
  @Autowired private AgentSettingsService settingsService;
  @Autowired private AssetGrantService grantService;
  @Autowired private CapabilityService capabilityService;
  @Autowired private KnowledgeLibraryService libraryService;
  @Autowired private UserRepository userRepository;
  @Autowired private OrganizationRepository organizationRepository;
  @Autowired private OwnOrganizationFixtures ownOrganizationFixtures;
  @Autowired private OwnLibraryFixtures libraryFixtures;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private DocumentTemplateService templateService;
  @Autowired private DocumentTemplateSettingsService templateSettings;

  private UUID organization;
  private UUID foreignOrganization;
  private UUID creator;
  private UUID reader;
  private UUID outsider;
  private UUID administrator;
  private UUID foreigner;
  private final List<UUID> libraries = new ArrayList<>();

  @BeforeEach
  void setUp() {
    organization = createOrganization("Agenten");
    foreignOrganization = createOrganization("Fremde Organisation");
    creator = createUser(organization, "Anlegerin");
    reader = createUser(organization, "Leserin");
    outsider = createUser(organization, "Außenstehende");
    administrator = createUser(organization, "Systemverwaltung");
    foreigner = createUser(foreignOrganization, "Fremde");
    jdbc.update("UPDATE agent_settings SET enabled = true WHERE id = 1");
  }

  @AfterEach
  void tearDown() {
    jdbc.update("UPDATE agent_settings SET enabled = false, updated_by = NULL WHERE id = 1");
    jdbc.update("DELETE FROM agent_settings_tools WHERE settings_id = 1");
    for (String tool : List.of("search_knowledge", "read_document", "list_sources", "ask_user")) {
      jdbc.update("INSERT INTO agent_settings_tools (settings_id, tool_name) VALUES (1, ?)", tool);
    }
    jdbc.update("DELETE FROM document_template_settings");
    libraryFixtures.removeLibraries(libraries.toArray(UUID[]::new));
    libraries.clear();
    for (UUID id : List.of(organization, foreignOrganization)) {
      jdbc.update("DELETE FROM assets WHERE organization_id = ?", id);
      jdbc.update("DELETE FROM asset_grant_history WHERE organization_id = ?", id);
      jdbc.update("DELETE FROM asset_ownership_history WHERE organization_id = ?", id);
      jdbc.update("DELETE FROM capability_grants WHERE organization_id = ?", id);
      jdbc.update("DELETE FROM capability_grant_history WHERE organization_id = ?", id);
    }
    ownOrganizationFixtures.removeOrganizations(organization, foreignOrganization);
  }

  @Test
  void theSwitchHidesEveryAgentAndTheRight() {
    UUID agent = agentOf(admin(), "Prüfhilfe");
    jdbc.update("UPDATE agent_settings SET enabled = false WHERE id = 1");

    assertThat(capabilityService.capabilitiesOf(admin())).doesNotContain(Capability.CREATE_AGENT);
    assertThatThrownBy(() -> agentService.list(admin())).isInstanceOf(NotFoundException.class);
    assertThatThrownBy(() -> agentService.get(agent, admin()))
        .isInstanceOf(NotFoundException.class);
    assertThatThrownBy(() -> agentService.create(creation("Neu"), admin()))
        .isInstanceOf(NotFoundException.class);

    jdbc.update("UPDATE agent_settings SET enabled = true WHERE id = 1");
    assertThat(capabilityService.capabilitiesOf(admin())).contains(Capability.CREATE_AGENT);
    assertThat(agentService.get(agent, admin()).agent().getName()).isEqualTo("Prüfhilfe");
  }

  @Test
  void nobodyCreatesAnAgentUntilTheRightIsGranted() {
    assertThatThrownBy(() -> agentService.create(creation("Ohne Recht"), callerOf(creator)))
        .isInstanceOf(AccessDeniedException.class);

    grantCreateAgent(creator);
    AgentView created = agentService.create(creation("Mit Recht"), callerOf(creator));

    Agent agent = created.agent();
    assertThat(created.myRole()).isEqualTo(AssetRole.OWNER);
    assertThat(agent.getInstruction()).isEmpty();
    assertThat(agent.getLibraryIds()).isEmpty();
    assertThat(agent.getToolNames()).isEqualTo(AgentTools.DEFAULT);
    assertThat(agent.getPlanStep()).isEqualTo(AgentPlanStep.OFF);
    assertThat(agent.isDelivered()).isFalse();
    assertThat(auditEvents(agent.getId())).contains("AGENT_CREATED");
  }

  @Test
  void anAssignedLibraryNeverWidensWhatTheEditingPersonReads() {
    grantCreateAgent(creator);
    UUID agent = agentOf(callerOf(creator), "Recherchehilfe");
    UUID hidden = libraryOf("Personalakten");
    UUID foreign = libraryOf("Haushalt");

    assertThatThrownBy(() -> agentService.update(agent, withLibraries(Set.of(foreign)), creator()))
        .isInstanceOf(FieldValidationException.class)
        .satisfies(
            error ->
                assertThat(((FieldValidationException) error).fieldErrors())
                    .extracting(FieldValidationException.FieldError::code)
                    .containsExactly("UNKNOWN"));

    // The administration assigns a library the creator cannot read; her next save keeps it hidden.
    agentService.update(agent, withLibraries(Set.of(hidden)), admin());
    AgentView seen = agentService.update(agent, withLibraries(Set.of()), creator());

    assertThat(seen.agent().getLibraryIds()).containsExactly(hidden);
    assertThat(seen.libraries()).isEmpty();
    assertThat(seen.hiddenLibraryCount()).isOne();
    assertThat(agentService.get(agent, admin()).libraries())
        .extracting(AgentView.LibraryRef::name)
        .containsExactly("Personalakten");
  }

  @Test
  void aDeletedLibraryIsNeitherHiddenNorKeptOnTheNextChange() {
    grantCreateAgent(creator);
    UUID agent = agentOf(callerOf(creator), "Recherchehilfe");
    UUID gone = libraryOf("Altakten");
    agentService.update(agent, withLibraries(Set.of(gone)), admin());
    libraryFixtures.removeLibraries(gone);
    libraries.remove(gone);

    assertThat(agentService.get(agent, creator()).hiddenLibraryCount()).isZero();
    AgentView saved = agentService.update(agent, withLibraries(Set.of()), creator());

    assertThat(saved.agent().getLibraryIds()).isEmpty();
  }

  @Test
  void anAssignedTemplateNeverWidensWhatTheEditingPersonReadsAndCountsTheRevision() {
    grantCreateAgent(creator);
    templateSettings.update(true, admin());
    grantCreateTemplate(creator);
    grantCreateTemplate(administrator);
    UUID agent = agentOf(callerOf(creator), "Berichtshilfe");
    UUID own = templateOf(creator(), "Bericht");
    UUID hidden = templateOf(admin(), "Personalvermerk");

    assertThatThrownBy(() -> agentService.update(agent, withTemplates(Set.of(hidden)), creator()))
        .isInstanceOf(FieldValidationException.class)
        .satisfies(
            error ->
                assertThat(((FieldValidationException) error).fieldErrors())
                    .extracting(
                        FieldValidationException.FieldError::field,
                        FieldValidationException.FieldError::code)
                    .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("templateIds", "UNKNOWN")));

    AgentView assigned = agentService.update(agent, withTemplates(Set.of(own)), creator());
    assertThat(assigned.templates())
        .extracting(AgentView.TemplateRef::name)
        .containsExactly("Bericht");
    assertThat(assigned.agent().getRevision()).isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                "SELECT after FROM audit_log WHERE object_id = ?"
                    + " AND event_type = 'AGENT_CHANGED'",
                String.class,
                agent.toString()))
        .contains("templates")
        .doesNotContain("Bericht\"");

    // The administration adds a template the creator cannot read; her next save keeps it hidden.
    agentService.update(agent, withTemplates(Set.of(own, hidden)), admin());
    AgentView seen = agentService.update(agent, withTemplates(Set.of(own)), creator());
    assertThat(seen.agent().getTemplateIds()).containsExactlyInAnyOrder(own, hidden);
    assertThat(seen.templates()).extracting(AgentView.TemplateRef::id).containsExactly(own);
    assertThat(seen.hiddenTemplateCount()).isOne();

    // A change that leaves the templates out keeps them and counts no revision.
    int revision = seen.agent().getRevision();
    AgentView unchanged =
        agentService.update(
            agent,
            new AgentUpdate(
                "Berichtshilfe",
                null,
                new AgentConfiguration(
                    "", Set.of(), AgentTools.DEFAULT, AgentPlanStep.OFF, AgentLimits.INSTALLATION)),
            creator());
    assertThat(unchanged.agent().getTemplateIds()).containsExactlyInAnyOrder(own, hidden);
    assertThat(unchanged.agent().getRevision()).isEqualTo(revision);

    // A deleted template drops out of the assignment.
    templateService.delete(hidden, admin());
    AgentView afterDeletion = agentService.get(agent, creator());
    assertThat(afterDeletion.agent().getTemplateIds()).containsExactly(own);
    assertThat(afterDeletion.hiddenTemplateCount()).isZero();
  }

  @Test
  void whileTemplatesAreSwitchedOffNoTemplateIsAssignedNamedOrCounted() {
    grantCreateAgent(creator);
    templateSettings.update(true, admin());
    grantCreateTemplate(creator);
    grantCreateTemplate(administrator);
    UUID agent = agentOf(callerOf(creator), "Berichtshilfe");
    UUID own = templateOf(creator(), "Bericht");
    UUID other = templateOf(creator(), "Vermerk");
    UUID hidden = templateOf(admin(), "Personalvermerk");
    agentService.update(agent, withTemplates(Set.of(own)), creator());
    agentService.update(agent, withTemplates(Set.of(own, hidden)), admin());

    templateSettings.update(false, admin());
    AgentView changed = agentService.update(agent, withTemplates(Set.of(other)), creator());

    assertThat(changed.agent().getTemplateIds()).containsExactlyInAnyOrder(own, hidden);
    assertThat(changed.templates()).isEmpty();
    assertThat(changed.hiddenTemplateCount()).isZero();
    assertThat(agentService.get(agent, creator()).templates()).isEmpty();
    assertThat(agentService.list(creator()))
        .filteredOn(view -> view.agent().getId().equals(agent))
        .singleElement()
        .satisfies(
            view -> {
              assertThat(view.templates()).isEmpty();
              assertThat(view.hiddenTemplateCount()).isZero();
            });

    templateSettings.update(true, admin());
    assertThat(agentService.get(agent, creator()).templates())
        .extracting(AgentView.TemplateRef::id)
        .containsExactly(own);
  }

  @Test
  void theAdministrationAloneNeitherReadsNorChangesTheInstruction() {
    grantCreateAgent(creator);
    UUID agent = agentOf(callerOf(creator), "Auftragshilfe");
    agentService.update(
        agent,
        new AgentUpdate(
            "Auftragshilfe",
            null,
            new AgentConfiguration(
                "Nur intern.",
                Set.of(),
                AgentTools.DEFAULT,
                AgentPlanStep.OFF,
                AgentLimits.INSTALLATION)),
        creator());

    assertThat(agentService.get(agent, admin()).instructionReadable()).isFalse();
    agentService.update(agent, withLibraries(Set.of()), admin());

    AgentView own = agentService.get(agent, creator());
    assertThat(own.instructionReadable()).isTrue();
    assertThat(own.agent().getInstruction()).isEqualTo("Nur intern.");
  }

  @Test
  void listAndSingleViewAgreeForEveryPerson() {
    grantCreateAgent(creator);
    UUID agent = agentOf(callerOf(creator), "Bescheidhilfe");
    grantService.upsertGrant(
        Agent.ASSET_TYPE,
        agent,
        new AssetGrantUpsert(AssetGrantSubjectType.USER, reader, AssetRole.VIEWER),
        creator());

    assertThat(listedBy(callerOf(reader))).contains(agent);
    assertThat(agentService.get(agent, callerOf(reader)).myRole()).isEqualTo(AssetRole.VIEWER);
    assertThat(listedBy(callerOf(outsider))).doesNotContain(agent);
    assertThatThrownBy(() -> agentService.get(agent, callerOf(outsider)))
        .as("an agent one may not read is not there")
        .isInstanceOf(NotFoundException.class);
    assertThatThrownBy(() -> agentService.get(agent, callerOf(foreigner)))
        .isInstanceOf(NotFoundException.class);
    assertThatThrownBy(() -> agentService.update(agent, withLibraries(Set.of()), callerOf(reader)))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void everyChangeLeavesOneAuditEntryAndAnUnchangedSaveNone() {
    grantCreateAgent(creator);
    UUID agent = agentOf(callerOf(creator), "Vermerkhilfe");

    AgentView changed =
        agentService.update(
            agent,
            new AgentUpdate(
                "Vermerkhilfe",
                "Schreibt Vermerke.",
                new AgentConfiguration(
                    "Antworte knapp.",
                    Set.of(),
                    AgentTools.DEFAULT,
                    AgentPlanStep.CONFIRM,
                    AgentLimits.INSTALLATION)),
            creator());
    agentService.update(
        agent,
        new AgentUpdate("Vermerkhilfe", "Schreibt Vermerke.", changed.agent().configuration()),
        creator());

    assertThat(changed.agent().getRevision()).isEqualTo(2);
    assertThat(auditEvents(agent)).containsOnlyOnce("AGENT_CHANGED");
    assertThat(
            jdbc.queryForObject(
                "SELECT after FROM audit_log WHERE object_id = ?"
                    + " AND event_type = 'AGENT_CHANGED'",
                String.class,
                agent.toString()))
        .contains("description", "instruction", "planStep")
        .doesNotContain("Antworte knapp");

    grantService.upsertGrant(
        Agent.ASSET_TYPE,
        agent,
        new AssetGrantUpsert(AssetGrantSubjectType.USER, reader, AssetRole.MANAGER),
        creator());
    assertThatThrownBy(() -> agentService.delete(agent, callerOf(reader)))
        .as("deleting stays with the owner")
        .isInstanceOf(AccessDeniedException.class);
    agentService.delete(agent, creator());
    assertThat(auditEvents(agent)).contains("AGENT_DELETED");
  }

  @Test
  void switchingOnProvisionsRechercheOncePerOrganization() {
    jdbc.update("UPDATE agent_settings SET enabled = false WHERE id = 1");
    Set<String> tools = settingsService.enabledTools();

    settingsService.update(admin(), true, tools);
    settingsService.update(admin(), false, tools);
    settingsService.update(admin(), true, tools);

    List<AgentView> visible = agentService.list(callerOf(outsider));
    assertThat(visible)
        .filteredOn(view -> view.agent().isDelivered())
        .singleElement()
        .satisfies(
            view -> {
              assertThat(view.agent().getName()).isEqualTo("Recherche");
              assertThat(view.myRole()).isEqualTo(AssetRole.VIEWER);
              assertThat(view.agent().getToolNames())
                  .containsExactlyInAnyOrder(
                      AgentTools.SEARCH_KNOWLEDGE,
                      AgentTools.READ_DOCUMENT,
                      AgentTools.LIST_SOURCES,
                      AgentTools.ASK_USER);
            });
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM audit_log WHERE organization_id = ?"
                    + " AND event_type = 'AGENT_SETTINGS_CHANGED'",
                Long.class,
                organization))
        .isEqualTo(3);
  }

  @Test
  void theCurrentRevisionReachesOnlyWhoMayReadTheAgent() {
    grantCreateAgent(creator);
    UUID agent = agentOf(callerOf(creator), "Fassungshilfe");
    agentService.update(agent, withLibraries(Set.of()), creator());
    assertThat(agentService.get(agent, creator()).agent().getRevision())
        .as("a new name alone is no new revision")
        .isEqualTo(1);
    agentService.update(
        agent,
        new AgentUpdate(
            "Recherchehilfe",
            null,
            new AgentConfiguration(
                "Antworte knapp.",
                Set.of(),
                AgentTools.DEFAULT,
                AgentPlanStep.OFF,
                AgentLimits.INSTALLATION)),
        creator());
    int revision = agentService.get(agent, creator()).agent().getRevision();

    assertThat(revision).isEqualTo(2);
    assertThat(currentRevisions.current(Set.of(agent), organization, creator))
        .containsExactly(java.util.Map.entry(agent, 2));
    assertThat(currentRevisions.current(Set.of(agent), organization, outsider)).isEmpty();
    assertThat(currentRevisions.current(Set.of(agent), foreignOrganization, foreigner)).isEmpty();
    jdbc.update("UPDATE agent_settings SET enabled = false WHERE id = 1");
    assertThat(currentRevisions.current(Set.of(agent), organization, creator)).isEmpty();
  }

  private UUID agentOf(CurrentUser caller, String name) {
    return agentService.create(creation(name), caller).agent().getId();
  }

  private UUID libraryOf(String name) {
    UUID id =
        libraryService
            .createLibrary(
                LibraryCreationBuilder.libraryCreation(name, SourceType.UPLOAD).build(), admin())
            .library()
            .getId();
    libraries.add(id);
    return id;
  }

  private UUID templateOf(CurrentUser caller, String name) {
    return templateService
        .create(
            new DocumentTemplateCreation(
                name,
                null,
                null,
                null,
                TestDocx.upload("vorlage.docx", TestDocx.withParagraphs("{{ titel }}"))),
            caller)
        .template()
        .getId();
  }

  private static AgentUpdate withTemplates(Set<UUID> templateIds) {
    return new AgentUpdate(
        "Berichtshilfe",
        null,
        new AgentConfiguration(
            "",
            Set.of(),
            AgentTools.DEFAULT,
            AgentPlanStep.OFF,
            AgentLimits.INSTALLATION,
            templateIds));
  }

  private void grantCreateTemplate(UUID userId) {
    jdbc.update(
        "INSERT INTO capability_grants (id, organization_id, capability, subject_type,"
            + " subject_user_id) VALUES (gen_random_uuid(), ?, 'CREATE_TEMPLATE', 'USER', ?)",
        organization,
        userId);
  }

  private Set<UUID> listedBy(CurrentUser caller) {
    Set<UUID> ids = new HashSet<>();
    agentService.list(caller).forEach(view -> ids.add(view.agent().getId()));
    return ids;
  }

  private static AgentCreation creation(String name) {
    return new AgentCreation(name, null, null, null);
  }

  private static AgentUpdate withLibraries(Set<UUID> libraryIds) {
    return new AgentUpdate(
        "Recherchehilfe",
        null,
        new AgentConfiguration(
            "", libraryIds, AgentTools.DEFAULT, AgentPlanStep.OFF, AgentLimits.INSTALLATION));
  }

  private void grantCreateAgent(UUID userId) {
    jdbc.update(
        "INSERT INTO capability_grants (id, organization_id, capability, subject_type,"
            + " subject_user_id) VALUES (gen_random_uuid(), ?, 'CREATE_AGENT', 'USER', ?)",
        organization,
        userId);
  }

  private List<String> auditEvents(UUID objectId) {
    return jdbc.queryForList(
        "SELECT event_type FROM audit_log WHERE object_id = ? ORDER BY recorded_at, event_id",
        String.class,
        objectId.toString());
  }

  private CurrentUser creator() {
    return callerOf(creator);
  }

  private CurrentUser admin() {
    return CurrentUser.of(administrator, organization, SystemRole.SYSTEM_ADMIN, "Verwaltung");
  }

  private CurrentUser callerOf(UUID userId) {
    UUID organizationId = userId.equals(foreigner) ? foreignOrganization : organization;
    return CurrentUser.of(userId, organizationId, SystemRole.USER, "Sachbearbeitung");
  }

  private UUID createOrganization(String name) {
    return organizationRepository
        .save(new Organization(UUID.randomUUID(), name + " " + UUID.randomUUID()))
        .getId();
  }

  private UUID createUser(UUID organizationId, String displayName) {
    User user =
        new User(
            "agent-" + UUID.randomUUID(),
            "https://issuer.example",
            UUID.randomUUID() + "@example.com",
            displayName);
    user.setOrganizationId(organizationId);
    return userRepository.save(user).getId();
  }
}
