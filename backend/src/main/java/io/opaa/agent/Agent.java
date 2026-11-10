package io.opaa.agent;

import io.opaa.api.types.AgentPlanStep;
import io.opaa.api.types.AssetOwnerType;
import io.opaa.asset.Asset;
import io.opaa.permission.AssetType;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.PrimaryKeyJoinColumn;
import jakarta.persistence.Table;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * An agent: a catalog entry that runs the tool loop (ADR-0070) with its own instruction, libraries,
 * tools, plan step and limits. Name, description, owner and reach are the shell's.
 *
 * <p>{@link #revision} counts every saved change of what a run uses (the "Fassung"); the shell's
 * {@code updatedAt} moves with it. The libraries are ids only: a library that is deleted or no
 * longer readable stays assigned, and the agent says so instead of losing it silently. The same
 * holds for the document templates, except that a deleted template drops out of the assignment.
 */
@Entity
@Table(name = "agents")
@PrimaryKeyJoinColumn(name = "id")
public class Agent extends Asset {

  /** The value {@code assets.asset_type} and every grant on an agent carry. */
  public static final AssetType ASSET_TYPE = AssetType.of("AGENT");

  /** The shell's organization repeated on the type row, held equal by the shell's key. */
  @Column(name = "organization_id", nullable = false, updatable = false)
  private UUID agentOrganizationId;

  @Column(name = "instruction", nullable = false)
  private String instruction = "";

  @Enumerated(EnumType.STRING)
  @Column(name = "plan_step", nullable = false, length = 20)
  private AgentPlanStep planStep = AgentPlanStep.OFF;

  @Column(name = "max_tool_calls")
  private Integer maxToolCalls;

  @Column(name = "max_search_calls")
  private Integer maxSearchCalls;

  @Column(name = "max_duration_seconds")
  private Integer maxDurationSeconds;

  @Column(name = "max_collected_chars")
  private Integer maxCollectedChars;

  @Column(name = "revision", nullable = false)
  private int revision = 1;

  /** Set only on an agent the installation delivered ("Recherche"); it stays an ordinary agent. */
  @Column(name = "seed_key", updatable = false, length = 40)
  private String seedKey;

  @ElementCollection(fetch = FetchType.EAGER)
  @CollectionTable(name = "agent_libraries", joinColumns = @JoinColumn(name = "agent_id"))
  @Column(name = "library_id", nullable = false)
  private Set<UUID> libraryIds = new LinkedHashSet<>();

  @ElementCollection(fetch = FetchType.EAGER)
  @CollectionTable(name = "agent_tools", joinColumns = @JoinColumn(name = "agent_id"))
  @Column(name = "tool_name", nullable = false, length = 64)
  private Set<String> toolNames = new LinkedHashSet<>();

  @ElementCollection(fetch = FetchType.EAGER)
  @CollectionTable(name = "agent_templates", joinColumns = @JoinColumn(name = "agent_id"))
  @Column(name = "template_id", nullable = false)
  private Set<UUID> templateIds = new LinkedHashSet<>();

  protected Agent() {}

  private Agent(
      UUID organizationId,
      String name,
      String description,
      AssetOwnerType ownerType,
      UUID ownerId) {
    super(ASSET_TYPE, organizationId, name, description, ownerType, ownerId);
    this.agentOrganizationId = organizationId;
  }

  public static Agent ownedByUser(
      UUID organizationId, String name, String description, UUID ownerUserId) {
    return new Agent(organizationId, name, description, AssetOwnerType.USER, ownerUserId);
  }

  public static Agent ownedByGroup(
      UUID organizationId, String name, String description, UUID ownerGroupId) {
    return new Agent(organizationId, name, description, AssetOwnerType.GROUP, ownerGroupId);
  }

  /** Marks the agent as the installation's delivered one; set once, before it is saved. */
  void markDelivered(String seedKey) {
    this.seedKey = seedKey;
  }

  /** Whether the installation delivered this agent rather than a person creating it. */
  public boolean isDelivered() {
    return seedKey != null;
  }

  /** Replaces what a run uses; the caller has validated every value and counts the revision. */
  void configure(AgentConfiguration configuration) {
    this.instruction = configuration.instruction();
    this.planStep = configuration.planStep();
    this.maxToolCalls = configuration.limits().maxToolCalls();
    this.maxSearchCalls = configuration.limits().maxSearchCalls();
    this.maxDurationSeconds = configuration.limits().maxDurationSeconds();
    this.maxCollectedChars = configuration.limits().maxCollectedChars();
    replace(this.libraryIds, configuration.libraryIds());
    replace(this.toolNames, configuration.toolNames());
    if (configuration.templateIds() != null) {
      replace(this.templateIds, configuration.templateIds());
    }
    touch();
  }

  /** A new revision: something a run uses or the agent's name or purpose changed. */
  void countRevision() {
    this.revision++;
    touch();
  }

  /** The configuration as stored, for comparison and for a run. */
  public AgentConfiguration configuration() {
    return new AgentConfiguration(
        instruction,
        Set.copyOf(libraryIds),
        Set.copyOf(toolNames),
        planStep,
        new AgentLimits(maxToolCalls, maxSearchCalls, maxDurationSeconds, maxCollectedChars),
        Set.copyOf(templateIds));
  }

  private static <T> void replace(Set<T> target, Collection<T> values) {
    target.clear();
    target.addAll(values);
  }

  public String getInstruction() {
    return instruction;
  }

  public AgentPlanStep getPlanStep() {
    return planStep;
  }

  public Set<UUID> getLibraryIds() {
    return Set.copyOf(libraryIds);
  }

  /**
   * The assigned document templates; a deleted template drops out with its row, one the reader may
   * not read stays assigned.
   */
  public Set<UUID> getTemplateIds() {
    return Set.copyOf(templateIds);
  }

  public Set<String> getToolNames() {
    return Set.copyOf(toolNames);
  }

  public int getRevision() {
    return revision;
  }
}
