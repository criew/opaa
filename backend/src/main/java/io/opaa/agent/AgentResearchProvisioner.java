package io.opaa.agent;

import io.opaa.api.types.AgentPlanStep;
import io.opaa.api.types.AssetRole;
import io.opaa.asset.AssetGrantService;
import io.opaa.asset.AssetGrantUpsert;
import io.opaa.asset.AssetResponsibilityService;
import io.opaa.asset.AssetShellService;
import io.opaa.asset.ResponsibleUnit;
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

  /** The responsible unit of the delivered agent, the same the migration names for an older one. */
  static final String RESEARCH_RESPONSIBLE = "Systemverwaltung";

  static final String RESEARCH_INSTRUCTION =
      "Zerlege die Frage in Teilfragen und suche zu jeder gezielt. Grenzt die Person im Plan auf"
          + " Bibliotheken, Zeiträume oder Dokumentarten ein, halte dich daran. Lies bei wichtigen"
          + " Fundstellen im Dokument weiter. Antworte als gegliederter Bericht: kurze"
          + " Zusammenfassung, dann je Teilfrage die Ergebnisse, jede Aussage mit ihrer Fundstelle."
          + " Widersprechen sich Fundstellen, nenne die Widersprüche ausdrücklich mit beiden"
          + " Fundstellen und entscheide nicht stillschweigend für eine Seite. Benenne Lücken: was"
          + " du gesucht und nicht gefunden hast. Rechne nur mit Code, wenn die Fundstellen Zahlen"
          + " liefern, die verglichen oder summiert werden müssen. Wünscht die Person eine Datei,"
          + " fülle eine Vorlage des Agenten aus; gibt es keine, sage das und antworte im Chat. Ist"
          + " die Frage unklar, frage nach, bevor du suchst.";

  /**
   * Above the default of a self-made agent ({@link AgentService#DEFAULT_LIMITS}) and within the
   * installation's limits; a lower installation limit still wins. The collected volume is 150000
   * characters (about 45k tokens): the cases in {@code eval/agents/recherche} reached the former
   * 60000 in most runs, and the rest of a 128k-token context stays free for rules, history and the
   * report.
   */
  static final AgentLimits RESEARCH_LIMITS = new AgentLimits(12, 8, 240, 150_000);

  private final JdbcTemplate jdbc;
  private final AgentRepository agentRepository;
  private final AssetShellService shellService;
  private final AssetGrantService grantService;
  private final AssetResponsibilityService responsibilityService;

  AgentResearchProvisioner(
      JdbcTemplate jdbc,
      AgentRepository agentRepository,
      AssetShellService shellService,
      AssetGrantService grantService,
      AssetResponsibilityService responsibilityService) {
    this.responsibilityService = responsibilityService;
    this.jdbc = jdbc;
    this.agentRepository = agentRepository;
    this.shellService = shellService;
    this.grantService = grantService;
  }

  /**
   * What "Recherche" is delivered with: every tool - a run is offered only those switched on, and
   * {@code fill_template} only with a template -, a plan to confirm, no fixed library (a run
   * searches what the asker may read) and no template until someone assigns one.
   */
  static AgentConfiguration researchConfiguration() {
    return new AgentConfiguration(
        RESEARCH_INSTRUCTION,
        Set.of(),
        Set.copyOf(AgentTools.ASSIGNABLE),
        AgentPlanStep.CONFIRM,
        RESEARCH_LIMITS,
        Set.of());
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
    agent.configure(researchConfiguration());
    Agent saved = agentRepository.saveAndFlush(agent);
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("name", saved.getName());
    shellService.registerCreated(saved, administrator.id(), payload);
    responsibilityService.establish(saved, ResponsibleUnit.ofFunction(RESEARCH_RESPONSIBLE));
    grantService.upsertGrant(
        Agent.ASSET_TYPE,
        saved.getId(),
        AssetGrantUpsert.forAllAccounts(AssetRole.VIEWER),
        administrator);
  }
}
