package io.opaa.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.agent.AgentDirectory.AgentEntry;
import io.opaa.api.types.AgentPlanStep;
import io.opaa.api.types.Capability;
import io.opaa.api.types.CapabilitySubjectType;
import io.opaa.api.types.SystemRole;
import io.opaa.asset.ResponsibleUnit;
import io.opaa.auth.CurrentUser;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.common.AccessDeniedException;
import io.opaa.common.NotFoundException;
import io.opaa.knowledge.SourceType;
import io.opaa.library.KnowledgeLibraryService;
import io.opaa.library.LibraryCreationBuilder;
import io.opaa.organization.Organization;
import io.opaa.organization.OrganizationRepository;
import io.opaa.permission.CapabilityService;
import io.opaa.template.DocumentTemplateCreation;
import io.opaa.template.DocumentTemplateService;
import io.opaa.template.TestDocx;
import io.opaa.test.OpaaIntegrationTest;
import io.opaa.test.OwnLibraryFixtures;
import io.opaa.test.OwnOrganizationFixtures;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The acceptance criteria of the agent directory on the service level: whoever holds {@code
 * VIEW_AGENT_DIRECTORY} sees every agent and template of the organization, readable or not, with
 * library names only; without the right the directory is refused; every read and export, a refused
 * one included, leaves one audit entry; templates appear only while their switch is on; a lock
 * shows since when, never why.
 */
@OpaaIntegrationTest
class AgentDirectoryIntegrationTest {

  @Autowired private AgentDirectoryService directoryService;
  @Autowired private AgentService agentService;
  @Autowired private CapabilityService capabilityService;
  @Autowired private KnowledgeLibraryService libraryService;
  @Autowired private DocumentTemplateService templateService;
  @Autowired private UserRepository userRepository;
  @Autowired private OrganizationRepository organizationRepository;
  @Autowired private OwnOrganizationFixtures ownOrganizationFixtures;
  @Autowired private OwnLibraryFixtures libraryFixtures;
  @Autowired private JdbcTemplate jdbc;

  private UUID organization;
  private UUID foreignOrganization;
  private UUID administrator;
  private UUID councillor;
  private UUID clerk;
  private UUID foreigner;
  private final List<UUID> libraries = new ArrayList<>();

  @BeforeEach
  void setUp() {
    organization = createOrganization("Verzeichnis");
    foreignOrganization = createOrganization("Fremde Organisation");
    administrator = createUser(organization, "Systemverwaltung");
    councillor = createUser(organization, "Personalrat");
    clerk = createUser(organization, "Sachbearbeitung");
    foreigner = createUser(foreignOrganization, "Fremde");
    jdbc.update("UPDATE agent_settings SET enabled = true WHERE id = 1");
    jdbc.update(
        "INSERT INTO document_template_settings (id, enabled, updated_at) VALUES (1, true, now())"
            + " ON CONFLICT (id) DO UPDATE SET enabled = true");
  }

  @AfterEach
  void tearDown() {
    jdbc.update("UPDATE agent_settings SET enabled = false, updated_by = NULL WHERE id = 1");
    jdbc.update("UPDATE document_template_settings SET enabled = false WHERE id = 1");
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
  void withoutTheRightTheDirectoryIsRefusedAndTheAttemptAudited() {
    assertThatThrownBy(() -> directoryService.read(callerOf(councillor)))
        .isInstanceOfSatisfying(
            AccessDeniedException.class,
            e -> {
              assertThat(e.getCode()).isEqualTo(CapabilityService.CAPABILITY_REQUIRED);
              assertThat(e.getMessage()).contains("Recht „Agentenverzeichnis einsehen“");
            });
    assertThatThrownBy(() -> directoryService.exportCsv(callerOf(councillor)))
        .isInstanceOf(AccessDeniedException.class);

    assertThat(accesses()).containsExactly("DENIED VIEW", "DENIED CSV");
  }

  @Test
  void theRightIsDeliveredToNobodyAndHeldByTheAdministration() {
    assertThat(capabilityService.capabilitiesOf(callerOf(clerk)))
        .doesNotContain(Capability.VIEW_AGENT_DIRECTORY);
    assertThat(capabilityService.capabilitiesOf(admin())).contains(Capability.VIEW_AGENT_DIRECTORY);
  }

  @Test
  void theHolderSeesEveryAgentOfTheOrganizationWithLibraryNamesOnly() {
    UUID library = libraryOf("Vergaberecht intern");
    UUID agent = agentOf("Vergabeprüfung");
    agentService.update(
        agent,
        new AgentUpdate(
            "Vergabeprüfung",
            "Prüft Vergabeunterlagen",
            new AgentConfiguration(
                "Prüfe gegen die UVgO.",
                Set.of(library),
                Set.of(AgentTools.READ_DOCUMENT, AgentTools.SEARCH_KNOWLEDGE),
                AgentPlanStep.CONFIRM,
                new AgentLimits(5, null, null, null))),
        admin());
    agentOf(foreignAdmin(), "Fremder Agent");
    grantDirectory(councillor);

    AgentDirectory directory = directoryService.read(callerOf(councillor));

    assertThat(directory.agents()).extracting(AgentEntry::name).containsExactly("Vergabeprüfung");
    AgentEntry entry = directory.agents().getFirst();
    assertThat(entry.description()).isEqualTo("Prüft Vergabeunterlagen");
    assertThat(entry.instruction()).isEqualTo("Prüfe gegen die UVgO.");
    assertThat(entry.libraryNames())
        .as("the councillor reads no library, yet sees its name")
        .containsExactly("Vergaberecht intern");
    assertThat(entry.toolNames()).containsExactly("search_knowledge", "read_document");
    assertThat(entry.planStep()).isEqualTo(AgentPlanStep.CONFIRM);
    assertThat(entry.limits().maxToolCalls()).isEqualTo(5);
    assertThat(entry.revision()).isEqualTo(2);
    assertThat(entry.responsibility().function()).isEqualTo("Referat Z3");
    assertThat(entry.responsibility().missing()).isFalse();
    assertThat(entry.lockedSince()).isNull();
    assertThat(accesses()).containsExactly("SUCCESS VIEW");
  }

  @Test
  void anAgentWhoseLibrariesAreAllGoneSearchesNothingInsteadOfEverything() {
    UUID deleted = libraryOf("Gelöschtes Wissen");
    UUID orphaned = agentOf("Verwaist");
    agentService.update(
        orphaned,
        new AgentUpdate(
            "Verwaist",
            null,
            new AgentConfiguration(
                "",
                Set.of(deleted),
                AgentTools.DEFAULT,
                AgentPlanStep.OFF,
                AgentLimits.INSTALLATION)),
        admin());
    agentOf("Offen");
    libraryFixtures.removeLibraries(deleted);
    libraries.remove(deleted);

    AgentDirectory directory = directoryService.read(admin());
    String csv = new String(directoryService.exportCsv(admin()).content(), StandardCharsets.UTF_8);

    assertThat(directory.agents())
        .extracting(AgentEntry::name, AgentEntry::libraryNames, AgentEntry::allReadableLibraries)
        .containsExactly(
            Tuple.tuple("Offen", List.of(), true), Tuple.tuple("Verwaist", List.of(), false));
    assertThat(csv)
        .contains("\"Verwaist\";\"\";\"\";\"" + AgentDirectoryCsv.ASSIGNED_LIBRARIES_DELETED)
        .contains("\"Offen\";\"\";\"\";\"" + AgentDirectoryCsv.ALL_READABLE_LIBRARIES);
  }

  @Test
  void aLibraryOrTemplateOfAnotherOrganizationNeverAppears() {
    UUID own = libraryOf("Eigenes Wissen");
    UUID foreignLibrary =
        libraryService
            .createLibrary(
                LibraryCreationBuilder.libraryCreation("Fremdes Wissen", SourceType.UPLOAD).build(),
                foreignAdmin())
            .library()
            .getId();
    libraries.add(foreignLibrary);
    UUID ownTemplate = templateOf("Eigene Vorlage");
    UUID foreignTemplate = templateOf(foreignAdmin(), "Fremde Vorlage");
    UUID agent = agentOf("Grenzgänger");
    agentService.update(
        agent,
        new AgentUpdate(
            "Grenzgänger",
            null,
            new AgentConfiguration(
                "",
                Set.of(own),
                AgentTools.DEFAULT,
                AgentPlanStep.OFF,
                AgentLimits.INSTALLATION,
                Set.of(ownTemplate))),
        admin());
    // Smuggled past the service, which would refuse them: only the reading side is under test.
    jdbc.update(
        "INSERT INTO agent_libraries (agent_id, library_id) VALUES (?, ?)", agent, foreignLibrary);
    jdbc.update(
        "INSERT INTO agent_templates (agent_id, template_id) VALUES (?, ?)",
        agent,
        foreignTemplate);

    AgentDirectory directory = directoryService.read(admin());

    AgentEntry entry = directory.agents().getFirst();
    assertThat(entry.libraryNames()).containsExactly("Eigenes Wissen");
    assertThat(entry.templateNames()).containsExactly("Eigene Vorlage");
    assertThat(directory.templates())
        .extracting(AgentDirectory.TemplateEntry::name)
        .containsExactly("Eigene Vorlage");
  }

  @Test
  void aLockShowsSinceWhenButNeverWhy() {
    UUID agent = agentOf("Gesperrt");
    agentService.lock(agent, "Vertraulicher Grund", admin());
    grantDirectory(councillor);

    AgentEntry entry = directoryService.read(callerOf(councillor)).agents().getFirst();
    byte[] csv = directoryService.exportCsv(callerOf(councillor)).content();

    assertThat(entry.lockedSince()).isNotNull();
    assertThat(new String(csv, StandardCharsets.UTF_8)).doesNotContain("Vertraulicher Grund");
    assertThat(accesses()).containsExactly("SUCCESS VIEW", "SUCCESS CSV");
  }

  @Test
  void templatesAppearOnlyWhileTheirSwitchIsOn() {
    UUID template = templateOf("Prüfvermerk");
    UUID agent = agentOf("Berichtshilfe");
    agentService.update(
        agent,
        new AgentUpdate(
            "Berichtshilfe",
            null,
            new AgentConfiguration(
                "",
                Set.of(),
                AgentTools.DEFAULT,
                AgentPlanStep.OFF,
                AgentLimits.INSTALLATION,
                Set.of(template))),
        admin());

    AgentDirectory on = directoryService.read(admin());
    assertThat(on.templatesIncluded()).isTrue();
    assertThat(on.templates())
        .singleElement()
        .satisfies(
            entry -> {
              assertThat(entry.name()).isEqualTo("Prüfvermerk");
              assertThat(entry.placeholderNames()).containsExactly("titel");
              assertThat(entry.fileName()).isEqualTo("vorlage.docx");
            });
    assertThat(on.agents().getFirst().templateNames()).containsExactly("Prüfvermerk");

    jdbc.update("UPDATE document_template_settings SET enabled = false WHERE id = 1");
    AgentDirectory off = directoryService.read(admin());
    assertThat(off.templatesIncluded()).isFalse();
    assertThat(off.templates()).isEmpty();
    assertThat(off.agents().getFirst().templateNames()).isEmpty();
  }

  @Test
  void theSwitchHidesTheDirectoryAndTheRight() {
    grantDirectory(councillor);
    jdbc.update("UPDATE agent_settings SET enabled = false WHERE id = 1");

    assertThat(capabilityService.capabilitiesOf(callerOf(councillor)))
        .doesNotContain(Capability.VIEW_AGENT_DIRECTORY);
    assertThatThrownBy(() -> directoryService.read(callerOf(councillor)))
        .isInstanceOf(NotFoundException.class);
    assertThatThrownBy(() -> directoryService.exportCsv(admin()))
        .isInstanceOf(NotFoundException.class);
    assertThat(accesses()).isEmpty();
  }

  @Test
  void theExportHoldsOneRowPerAgentAndTemplate() {
    agentOf("=Formel");
    templateOf("Prüfvermerk");

    String csv = new String(directoryService.exportCsv(admin()).content(), StandardCharsets.UTF_8);

    assertThat(csv).startsWith("﻿\"Art\";\"Name\"");
    assertThat(csv.lines()).hasSize(3);
    assertThat(csv).contains("\"Agent\";\"'=Formel\"").contains("\"Vorlage\";\"Prüfvermerk\"");
  }

  /** Outcome and form of every directory access in the organization, oldest first. */
  private List<String> accesses() {
    return jdbc
        .queryForList(
            "SELECT outcome, after::jsonb ->> 'form' AS form FROM audit_log"
                + " WHERE organization_id = ? AND event_type = 'AGENT_DIRECTORY_ACCESSED'"
                + " ORDER BY recorded_at, event_id",
            organization)
        .stream()
        .map((Map<String, Object> row) -> row.get("outcome") + " " + row.get("form"))
        .toList();
  }

  private void grantDirectory(UUID userId) {
    capabilityService.grant(
        Capability.VIEW_AGENT_DIRECTORY, CapabilitySubjectType.USER, userId, admin());
  }

  private UUID agentOf(String name) {
    return agentOf(admin(), name);
  }

  private UUID agentOf(CurrentUser caller, String name) {
    return agentService
        .create(
            new AgentCreation(name, null, null, null, ResponsibleUnit.ofFunction("Referat Z3")),
            caller)
        .agent()
        .getId();
  }

  private UUID templateOf(String name) {
    return templateOf(admin(), name);
  }

  private UUID templateOf(CurrentUser caller, String name) {
    return templateService
        .create(
            new DocumentTemplateCreation(
                name,
                null,
                null,
                null,
                TestDocx.upload("vorlage.docx", TestDocx.withParagraphs("{{ titel }}")),
                ResponsibleUnit.ofFunction("Referat Z3")),
            caller)
        .template()
        .getId();
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

  private CurrentUser admin() {
    return CurrentUser.of(administrator, organization, SystemRole.SYSTEM_ADMIN, "Verwaltung");
  }

  private CurrentUser foreignAdmin() {
    return CurrentUser.of(foreigner, foreignOrganization, SystemRole.SYSTEM_ADMIN, "Fremde");
  }

  private CurrentUser callerOf(UUID userId) {
    return CurrentUser.of(userId, organization, SystemRole.USER, "Sachbearbeitung");
  }

  private UUID createOrganization(String name) {
    return organizationRepository
        .save(new Organization(UUID.randomUUID(), name + " " + UUID.randomUUID()))
        .getId();
  }

  private UUID createUser(UUID organizationId, String displayName) {
    User user =
        new User(
            "directory-" + UUID.randomUUID(),
            "https://issuer.example",
            UUID.randomUUID() + "@example.com",
            displayName);
    user.setOrganizationId(organizationId);
    return userRepository.save(user).getId();
  }
}
