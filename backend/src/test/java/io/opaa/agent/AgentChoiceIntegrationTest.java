package io.opaa.agent;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.api.types.AssetGrantSubjectType;
import io.opaa.api.types.AssetRole;
import io.opaa.api.types.SpaceRole;
import io.opaa.api.types.SystemRole;
import io.opaa.asset.AssetGrantService;
import io.opaa.asset.AssetGrantUpsert;
import io.opaa.auth.CurrentUser;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.organization.Organization;
import io.opaa.organization.OrganizationRepository;
import io.opaa.space.Space;
import io.opaa.space.SpaceAssetAssociationService;
import io.opaa.space.SpaceMembership;
import io.opaa.space.SpaceRepository;
import io.opaa.test.OpaaIntegrationTest;
import io.opaa.test.OwnOrganizationFixtures;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The gate of a chosen agent with the real space association and the formula: offered and resolved
 * only when the agent stands in the chat's space and the caller may read it, while agents are on.
 * Every refusal is the same empty answer, which the tool loop turns into {@code
 * AGENT_NOT_AVAILABLE}.
 */
@OpaaIntegrationTest
class AgentChoiceIntegrationTest {

  @Autowired private AgentChoice agentChoice;
  @Autowired private AgentService agentService;
  @Autowired private AssetGrantService grantService;
  @Autowired private SpaceAssetAssociationService associationService;
  @Autowired private SpaceRepository spaceRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private OrganizationRepository organizationRepository;
  @Autowired private OwnOrganizationFixtures ownOrganizationFixtures;
  @Autowired private JdbcTemplate jdbc;

  private UUID organization;
  private UUID foreignOrganization;
  private UUID owner;
  private UUID member;
  private UUID outsider;
  private UUID foreigner;
  private UUID space;
  private UUID agent;

  @BeforeEach
  void setUp() {
    organization = createOrganization("Agentenwahl");
    foreignOrganization = createOrganization("Fremde Organisation");
    owner = createUser(organization, "Eigentümerin");
    member = createUser(organization, "Mitglied");
    outsider = createUser(organization, "Außenstehende");
    foreigner = createUser(foreignOrganization, "Fremde");
    jdbc.update("UPDATE agent_settings SET enabled = true WHERE id = 1");
    jdbc.update(
        "INSERT INTO capability_grants (id, organization_id, capability, subject_type,"
            + " subject_user_id) VALUES (gen_random_uuid(), ?, 'CREATE_AGENT', 'USER', ?)",
        organization,
        owner);
    Space created = new Space("Vergabe", null, false, owner, organization);
    created.addMembership(SpaceMembership.ofUser(owner, SpaceRole.ADMIN, organization));
    created.addMembership(SpaceMembership.ofUser(member, SpaceRole.MEMBER, organization));
    space = spaceRepository.save(created).getId();
    agent =
        agentService
            .create(new AgentCreation("Prüfhilfe", null, null, null), as(owner))
            .agent()
            .getId();
    associationService.associate(space, Agent.ASSET_TYPE, agent, as(owner));
  }

  @AfterEach
  void tearDown() {
    jdbc.update("UPDATE agent_settings SET enabled = false, updated_by = NULL WHERE id = 1");
    for (UUID id : List.of(organization, foreignOrganization)) {
      jdbc.update("DELETE FROM space_asset_associations WHERE organization_id = ?", id);
      jdbc.update("DELETE FROM assets WHERE organization_id = ?", id);
      jdbc.update("DELETE FROM asset_grant_history WHERE organization_id = ?", id);
      jdbc.update("DELETE FROM asset_ownership_history WHERE organization_id = ?", id);
      jdbc.update("DELETE FROM capability_grants WHERE organization_id = ?", id);
      jdbc.update("DELETE FROM capability_grant_history WHERE organization_id = ?", id);
    }
    ownOrganizationFixtures.removeOrganizations(organization, foreignOrganization);
  }

  @Test
  void theOwnerIsOfferedTheAgentOfTheSpaceAndMayRunIt() {
    assertThat(agentChoice.available(as(owner), space)).extracting(Agent::getId).contains(agent);
    assertThat(agentChoice.resolve(agent, organization, owner, space, Set.of()))
        .hasValueSatisfying(spec -> assertThat(spec.name()).isEqualTo("Prüfhilfe"));
  }

  @Test
  void aMemberWithoutReadAccessIsNeitherOfferedNorAllowedTheAgent() {
    assertThat(agentChoice.available(as(member), space)).isEmpty();
    assertThat(agentChoice.resolve(agent, organization, member, space, Set.of())).isEmpty();

    grantService.upsertGrant(
        Agent.ASSET_TYPE,
        agent,
        new AssetGrantUpsert(AssetGrantSubjectType.USER, member, AssetRole.VIEWER),
        as(owner));

    assertThat(agentChoice.available(as(member), space)).extracting(Agent::getId).contains(agent);
    assertThat(agentChoice.resolve(agent, organization, member, space, Set.of())).isPresent();
  }

  @Test
  void anAgentOutsideTheSpaceIsRefused() {
    UUID elsewhere =
        agentService
            .create(new AgentCreation("Anderswo", null, null, null), as(owner))
            .agent()
            .getId();

    assertThat(agentChoice.available(as(owner), space))
        .extracting(Agent::getId)
        .doesNotContain(elsewhere);
    assertThat(agentChoice.resolve(elsewhere, organization, owner, space, Set.of())).isEmpty();
  }

  @Test
  void aCallerOutsideTheSpaceOrOrganizationIsRefused() {
    grantService.upsertGrant(
        Agent.ASSET_TYPE,
        agent,
        new AssetGrantUpsert(AssetGrantSubjectType.USER, outsider, AssetRole.VIEWER),
        as(owner));

    assertThat(agentChoice.resolve(agent, organization, outsider, space, Set.of())).isEmpty();
    assertThat(agentChoice.resolve(agent, foreignOrganization, foreigner, space, Set.of()))
        .isEmpty();
  }

  @Test
  void switchedOffTheAgentIsNeitherOfferedNorRun() {
    jdbc.update("UPDATE agent_settings SET enabled = false WHERE id = 1");

    assertThat(agentChoice.available(as(owner), space)).isEmpty();
    assertThat(agentChoice.resolve(agent, organization, owner, space, Set.of())).isEmpty();
  }

  private CurrentUser as(UUID userId) {
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
            "agent-choice-" + UUID.randomUUID(),
            "https://issuer.example",
            UUID.randomUUID() + "@example.com",
            displayName);
    user.setOrganizationId(organizationId);
    return userRepository.save(user).getId();
  }
}
