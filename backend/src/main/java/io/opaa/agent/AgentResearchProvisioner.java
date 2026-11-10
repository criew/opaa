package io.opaa.agent;

import io.opaa.api.types.AgentPlanStep;
import io.opaa.api.types.AssetRole;
import io.opaa.asset.AssetGrantService;
import io.opaa.asset.AssetGrantUpsert;
import io.opaa.asset.AssetShellService;
import io.opaa.auth.CurrentUser;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Provisions the delivered agent "Recherche" for an organization the first time agents are switched
 * on there: an ordinary agent, owned by the administrator who switched them on, readable for all
 * accounts and changeable like any other. Provisioned at most once per organization - the row in
 * {@code agent_seeds} is taken first, so a deleted "Recherche" never comes back and two instances
 * switching at once create one.
 */
@Component
class AgentResearchProvisioner {

  static final String RESEARCH_SEED = "research";
  static final String RESEARCH_NAME = "Recherche";
  static final String RESEARCH_PURPOSE =
      "Trägt Wissen in mehreren Schritten zusammen und antwortet gegliedert mit Fundstellen.";
  static final String RESEARCH_INSTRUCTION =
      "Zerlege die Frage in Teilfragen und suche zu jeder gezielt. Lies bei wichtigen Fundstellen im"
          + " Dokument weiter. Antworte gegliedert, belege jede Aussage mit ihrer Fundstelle und"
          + " benenne Lücken und Widersprüche ausdrücklich. Ist die Frage unklar, frage nach, bevor"
          + " du suchst.";

  private final JdbcTemplate jdbc;
  private final AgentRepository agentRepository;
  private final AssetShellService shellService;
  private final AssetGrantService grantService;

  AgentResearchProvisioner(
      JdbcTemplate jdbc,
      AgentRepository agentRepository,
      AssetShellService shellService,
      AssetGrantService grantService) {
    this.jdbc = jdbc;
    this.agentRepository = agentRepository;
    this.shellService = shellService;
    this.grantService = grantService;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  void provisionResearch(CurrentUser administrator) {
    int taken =
        jdbc.update(
            "INSERT INTO agent_seeds (organization_id, seed_key) VALUES (?, ?)"
                + " ON CONFLICT DO NOTHING",
            administrator.organizationId(),
            RESEARCH_SEED);
    if (taken == 0) {
      return;
    }
    Agent agent =
        Agent.ownedByUser(
            administrator.organizationId(), RESEARCH_NAME, RESEARCH_PURPOSE, administrator.id());
    agent.markDelivered(RESEARCH_SEED);
    agent.configure(
        new AgentConfiguration(
            RESEARCH_INSTRUCTION,
            Set.of(),
            Set.copyOf(AgentTools.ASSIGNABLE),
            AgentPlanStep.OFF,
            AgentLimits.INSTALLATION));
    Agent saved = agentRepository.saveAndFlush(agent);
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("name", saved.getName());
    shellService.registerCreated(saved, administrator.id(), payload);
    grantService.upsertGrant(
        Agent.ASSET_TYPE,
        saved.getId(),
        AssetGrantUpsert.forAllAccounts(AssetRole.VIEWER),
        administrator);
  }
}
