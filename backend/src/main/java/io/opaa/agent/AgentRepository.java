package io.opaa.agent;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** The agents - rights and reach are the shell's, never asked here. */
public interface AgentRepository extends JpaRepository<Agent, UUID> {

  /**
   * Locks the agent's row until the transaction ends, before it is read for a change: two changes
   * to one agent, also on two instances, run one after the other on the committed state.
   */
  @Query(value = "SELECT id FROM agents WHERE id = :agentId FOR UPDATE", nativeQuery = true)
  List<UUID> lockById(@Param("agentId") UUID agentId);

  /** Tools per agent, for the catalog's extent. */
  @Query(
      "SELECT a.id AS agentId, COUNT(t) AS toolCount FROM Agent a JOIN a.toolNames t"
          + " WHERE a.id IN :agentIds GROUP BY a.id")
  List<AgentToolCount> countToolsByAgentIdIn(Collection<UUID> agentIds);

  /** One row of {@link #countToolsByAgentIdIn}. */
  interface AgentToolCount {
    UUID getAgentId();

    long getToolCount();
  }
}
