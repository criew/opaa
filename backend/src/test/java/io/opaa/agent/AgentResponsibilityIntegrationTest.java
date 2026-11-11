package io.opaa.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.api.types.AssetGrantSubjectType;
import io.opaa.api.types.AssetLockCause;
import io.opaa.api.types.AssetRole;
import io.opaa.api.types.SystemRole;
import io.opaa.asset.AssetGrantService;
import io.opaa.asset.AssetGrantUpsert;
import io.opaa.asset.AssetResponsibilityView;
import io.opaa.asset.ResponsibilityLockPeriod;
import io.opaa.asset.ResponsibilityLockRun;
import io.opaa.asset.ResponsibleUnit;
import io.opaa.auth.CurrentUser;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.common.AccessDeniedException;
import io.opaa.common.ConflictException;
import io.opaa.common.FieldValidationException;
import io.opaa.organization.Organization;
import io.opaa.organization.OrganizationRepository;
import io.opaa.template.DocumentTemplateCreation;
import io.opaa.template.DocumentTemplateService;
import io.opaa.template.RunTemplates;
import io.opaa.template.TestDocx;
import io.opaa.test.OpaaIntegrationTest;
import io.opaa.test.OwnOrganizationFixtures;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The responsible unit and the lock of agents and document templates: no object without a unit, a
 * lock by the system administration that refuses every run and is lifted again, the lock after the
 * period without a unit that keeps the object, and one audit entry per creation, change, lock and
 * unlock - none for a use.
 */
@OpaaIntegrationTest
class AgentResponsibilityIntegrationTest {

  private static final ResponsibleUnit REFERAT = ResponsibleUnit.ofFunction("Referat Z3");

  @Autowired private AgentService agentService;
  @Autowired private DocumentTemplateService templateService;
  @Autowired private RunTemplates runTemplates;
  @Autowired private ResponsibilityLockRun lockRun;
  @Autowired private ResponsibilityLockPeriod lockPeriod;
  @Autowired private UserRepository userRepository;
  @Autowired private OrganizationRepository organizationRepository;
  @Autowired private OwnOrganizationFixtures ownOrganizationFixtures;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private AssetGrantService grantService;
  @Autowired private PlatformTransactionManager transactionManager;

  private UUID organization;
  private UUID administrator;
  private UUID reader;

  @BeforeEach
  void setUp() {
    organization =
        organizationRepository
            .save(new Organization(UUID.randomUUID(), "Stellen " + UUID.randomUUID()))
            .getId();
    administrator = createUser("Systemverwaltung");
    reader = createUser("Leserin");
    jdbc.update("UPDATE agent_settings SET enabled = true WHERE id = 1");
    jdbc.update(
        "INSERT INTO document_template_settings (id, enabled, updated_at) VALUES (1, true, now())"
            + " ON CONFLICT (id) DO UPDATE SET enabled = true");
  }

  @AfterEach
  void tearDown() {
    lockPeriod.set(null);
    jdbc.update("UPDATE agent_settings SET enabled = false, updated_by = NULL WHERE id = 1");
    jdbc.update("UPDATE document_template_settings SET enabled = false WHERE id = 1");
    jdbc.update("DELETE FROM assets WHERE organization_id = ?", organization);
    jdbc.update("DELETE FROM asset_grant_history WHERE organization_id = ?", organization);
    jdbc.update("DELETE FROM asset_ownership_history WHERE organization_id = ?", organization);
    jdbc.update("DELETE FROM groups WHERE organization_id = ?", organization);
    ownOrganizationFixtures.removeOrganizations(organization);
  }

  @Test
  void neitherAnAgentNorATemplateIsCreatedWithoutAResponsibleUnit() {
    assertThatThrownBy(
            () ->
                agentService.create(
                    new AgentCreation("Ohne Stelle", null, null, null, null), admin()))
        .isInstanceOfSatisfying(
            FieldValidationException.class,
            e -> assertThat(e.fieldErrors()).anyMatch(f -> f.field().equals("responsible")));
    assertThatThrownBy(
            () ->
                agentService.create(
                    new AgentCreation(
                        "Beides", null, null, null, new ResponsibleUnit(aGroup(), "Referat")),
                    admin()))
        .isInstanceOf(FieldValidationException.class);
    assertThatThrownBy(
            () ->
                templateService.create(
                    new DocumentTemplateCreation(
                        "Ohne Stelle",
                        null,
                        null,
                        null,
                        TestDocx.upload("vorlage.docx", TestDocx.withParagraphs("{{ titel }}")),
                        ResponsibleUnit.ofFunction("   ")),
                    admin()))
        .isInstanceOf(FieldValidationException.class);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM assets WHERE organization_id = ?", Long.class, organization))
        .isZero();
  }

  @Test
  void aLockedAgentIsLeftOutAndEveryRunIsRefusedUntilTheLockIsLifted() {
    UUID agent = anAgent("Vergabeprüfung", ResponsibleUnit.ofGroup(aGroup()));
    assertThat(lockOf(agent)).isNull();

    assertThatThrownBy(() -> agentService.lock(agent, "Prüfung", callerOf(reader)))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> agentService.lock(agent, "  ", admin()))
        .isInstanceOf(FieldValidationException.class);

    AssetResponsibilityView locked =
        agentService.lock(agent, "Anweisung wird überarbeitet", admin());

    assertThat(locked.lock().cause()).isEqualTo(AssetLockCause.MANUAL);
    assertThat(locked.lock().reason()).isEqualTo("Anweisung wird überarbeitet");
    assertThat(lockOf(agent)).isNotNull();
    assertThat(agentService.list(admin())).extracting(view -> view.agent().getId()).contains(agent);
    assertThatThrownBy(() -> agentService.requireUsableForRun(stored(agent)))
        .isInstanceOfSatisfying(
            ConflictException.class,
            e -> {
              assertThat(e.getCode()).isEqualTo(AgentService.LOCKED_CODE);
              assertThat(e.getMessage()).contains("Vergabeprüfung").contains("gesperrt");
            });

    agentService.unlock(agent, admin());

    assertThat(lockOf(agent)).isNull();
    agentService.requireUsableForRun(stored(agent));
    assertThat(auditEvents(agent))
        .containsExactly("AGENT_CREATED", "ASSET_LOCKED", "ASSET_UNLOCKED");
  }

  @Test
  void theReasonOfALockReachesManagersAndTheAdministrationButNotReaders() {
    UUID agent = anAgent("Vergabeprüfung", REFERAT);
    grantService.upsertGrant(
        Agent.ASSET_TYPE,
        agent,
        new AssetGrantUpsert(AssetGrantSubjectType.USER, reader, AssetRole.VIEWER),
        admin());
    agentService.lock(agent, "Anweisung wird überarbeitet", admin());

    AssetResponsibilityView.Lock seenByReader =
        agentService.get(agent, callerOf(reader)).responsibility().lock();
    assertThat(seenByReader.lockedAt()).isNotNull();
    assertThat(seenByReader.reason()).isNull();
    assertThat(
            agentService.list(callerOf(reader)).stream()
                .filter(view -> view.agent().getId().equals(agent))
                .findFirst()
                .orElseThrow()
                .responsibility()
                .lock()
                .reason())
        .isNull();
    assertThat(agentService.get(agent, admin()).responsibility().lock().reason())
        .isEqualTo("Anweisung wird überarbeitet");

    grantService.upsertGrant(
        Agent.ASSET_TYPE,
        agent,
        new AssetGrantUpsert(AssetGrantSubjectType.USER, reader, AssetRole.MANAGER),
        admin());
    assertThat(agentService.get(agent, callerOf(reader)).responsibility().lock().reason())
        .isEqualTo("Anweisung wird überarbeitet");
  }

  @Test
  void aLockSetWhileTheUnitChangesIsNotOverwritten() throws Exception {
    UUID agent = anAgent("Vergabeprüfung", REFERAT);
    Agent stored = stored(agent);
    TransactionTemplate transaction = new TransactionTemplate(transactionManager);

    CompletableFuture<Void> concurrentLock =
        transaction.execute(
            status -> {
              agentService.update(
                  agent,
                  new AgentUpdate(
                      stored.getName(),
                      null,
                      stored.configuration(),
                      ResponsibleUnit.ofFunction("Referat Z4")),
                  admin());
              CompletableFuture<Void> lock =
                  CompletableFuture.runAsync(
                      () -> agentService.lock(agent, "Anweisung wird überarbeitet", admin()));
              // the lock waits for the row the change holds until it commits
              assertThatThrownBy(() -> lock.get(500, TimeUnit.MILLISECONDS))
                  .isInstanceOf(TimeoutException.class);
              return lock;
            });
    concurrentLock.get(10, TimeUnit.SECONDS);

    AssetResponsibilityView after = agentService.get(agent, admin()).responsibility();
    assertThat(after.function()).isEqualTo("Referat Z4");
    assertThat(after.lock().reason()).isEqualTo("Anweisung wird überarbeitet");
  }

  @Test
  void changingTheUnitIsAuditedOnceAndUsingTheAgentWritesNothing() {
    UUID agent = anAgent("Recherchehilfe", REFERAT);
    Agent stored = agentService.get(agent, admin()).agent();

    agentService.update(
        agent,
        new AgentUpdate(
            stored.getName(),
            null,
            stored.configuration(),
            ResponsibleUnit.ofFunction("Referat Z4")),
        admin());
    agentService.update(
        agent,
        new AgentUpdate(
            stored.getName(),
            null,
            stored.configuration(),
            ResponsibleUnit.ofFunction("Referat Z4")),
        admin());
    agentService.requireUsableForRun(stored(agent));

    assertThat(agentService.get(agent, admin()).responsibility().function())
        .isEqualTo("Referat Z4");
    assertThat(auditEvents(agent)).containsExactly("AGENT_CREATED", "ASSET_RESPONSIBILITY_CHANGED");
  }

  @Test
  void anObjectWithoutResponsibleUnitIsLockedAfterThePeriodAndNotDeleted() {
    lockPeriod.set(1);
    UUID group = aGroup();
    UUID agent = anAgent("Haushaltshilfe", ResponsibleUnit.ofGroup(group));
    UUID template = aTemplate("Bescheid", ResponsibleUnit.ofGroup(group));

    lockRun.runOnce();
    assertThat(agentService.get(agent, admin()).responsibility().missing()).isFalse();

    jdbc.update("UPDATE groups SET dissolved = true, dissolved_at = now() WHERE id = ?", group);
    lockRun.runOnce();

    AssetResponsibilityView missing = agentService.get(agent, admin()).responsibility();
    assertThat(missing.missing()).isTrue();
    assertThat(missing.missingSince()).isNotNull();
    assertThat(missing.lockDueAt()).isNotNull();
    assertThat(missing.lock()).isNull();

    jdbc.update(
        "UPDATE asset_responsibilities SET missing_since = missing_since - interval '2 days'"
            + " WHERE asset_id IN (?, ?)",
        agent,
        template);
    lockRun.runOnce();
    lockRun.runOnce();

    AssetResponsibilityView lockedAgent = agentService.get(agent, admin()).responsibility();
    assertThat(lockedAgent.lock().cause()).isEqualTo(AssetLockCause.NO_RESPONSIBLE);
    assertThat(templateService.get(template, admin()).responsibility().lock().cause())
        .isEqualTo(AssetLockCause.NO_RESPONSIBLE);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM assets WHERE id IN (?, ?)", Long.class, agent, template))
        .isEqualTo(2);
    assertThat(auditEvents(agent)).containsExactly("AGENT_CREATED", "ASSET_LOCKED");
    assertThat(
            jdbc.queryForObject(
                "SELECT actor_kind FROM audit_log WHERE object_id = ? AND event_type ="
                    + " 'ASSET_LOCKED'",
                String.class,
                template.toString()))
        .isEqualTo("SYSTEM_PROCESS");
  }

  @Test
  void aLockedTemplateIsOfferedToNoRunAndTheLockIsLiftedAgain() {
    UUID template = aTemplate("Vermerk", REFERAT);
    assertThat(offeredTemplates()).containsExactly(template);

    templateService.lock(template, "Inhalt veraltet", admin());
    assertThat(offeredTemplates()).isEmpty();
    assertThat(runTemplates.forFilling(template, organization, administrator)).isEmpty();
    assertThat(templateService.get(template, admin()).responsibility().lock().reason())
        .isEqualTo("Inhalt veraltet");

    templateService.unlock(template, admin());
    assertThat(offeredTemplates()).containsExactly(template);
    assertThat(runTemplates.forFilling(template, organization, administrator)).isPresent();
    assertThat(auditEvents(template))
        .containsExactly("DOCUMENT_TEMPLATE_CREATED", "ASSET_LOCKED", "ASSET_UNLOCKED");
  }

  private Agent stored(UUID agent) {
    return agentService.get(agent, admin()).agent();
  }

  private UUID anAgent(String name, ResponsibleUnit unit) {
    return agentService
        .create(new AgentCreation(name, null, null, null, unit), admin())
        .agent()
        .getId();
  }

  private UUID aTemplate(String name, ResponsibleUnit unit) {
    return templateService
        .create(
            new DocumentTemplateCreation(
                name,
                null,
                null,
                null,
                TestDocx.upload("vorlage.docx", TestDocx.withParagraphs("{{ titel }}")),
                unit),
            admin())
        .template()
        .getId();
  }

  private UUID aGroup() {
    UUID group = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO groups (id, organization_id, kind, name) VALUES (?, ?, 'AD_HOC', ?)",
        group,
        organization,
        "Referat " + group);
    return group;
  }

  private List<UUID> offeredTemplates() {
    return runTemplates.available(organization, administrator).stream()
        .map(RunTemplates.RunTemplate::id)
        .toList();
  }

  private AssetResponsibilityView.Lock lockOf(UUID agent) {
    return agentService.list(admin()).stream()
        .filter(view -> view.agent().getId().equals(agent))
        .findFirst()
        .orElseThrow()
        .responsibility()
        .lock();
  }

  /** The audit entries of the object, without those of its grants. */
  private List<String> auditEvents(UUID objectId) {
    return jdbc.queryForList(
        "SELECT event_type FROM audit_log WHERE object_id = ? AND event_type NOT LIKE"
            + " 'ASSET_GRANT_%' ORDER BY recorded_at, event_id",
        String.class, objectId.toString());
  }

  private CurrentUser admin() {
    return CurrentUser.of(administrator, organization, SystemRole.SYSTEM_ADMIN, "Verwaltung");
  }

  private CurrentUser callerOf(UUID userId) {
    return CurrentUser.of(userId, organization, SystemRole.USER, "Sachbearbeitung");
  }

  private UUID createUser(String displayName) {
    User user =
        new User(
            "stelle-" + UUID.randomUUID(),
            "https://issuer.example",
            UUID.randomUUID() + "@example.com",
            displayName);
    user.setOrganizationId(organization);
    return userRepository.save(user).getId();
  }
}
