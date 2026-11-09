package io.opaa.agent;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * The single settings row of agents: the installation-wide switch, default off, and the tools
 * switched on (ADR-0070, Entscheidung 6). Both are stored, never derived, and read per call.
 */
@Entity
@Table(name = "agent_settings")
public class AgentSettings {

  public static final int SINGLETON_ID = 1;

  @Id private Integer id;

  @Column(name = "enabled", nullable = false)
  private boolean enabled;

  @ElementCollection(fetch = FetchType.EAGER)
  @CollectionTable(name = "agent_settings_tools", joinColumns = @JoinColumn(name = "settings_id"))
  @Column(name = "tool_name", nullable = false, length = 64)
  private Set<String> enabledTools = new LinkedHashSet<>();

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Column(name = "updated_by")
  private UUID updatedBy;

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  protected AgentSettings() {}

  /** Replaces switch and tools; {@code updatedBy} names the system administrator. */
  void replace(boolean enabled, Set<String> enabledTools, UUID updatedBy, Instant now) {
    this.enabled = enabled;
    this.enabledTools.clear();
    this.enabledTools.addAll(enabledTools);
    this.updatedBy = updatedBy;
    this.updatedAt = Objects.requireNonNull(now, "now");
  }

  public boolean isEnabled() {
    return enabled;
  }

  public Set<String> getEnabledTools() {
    return Set.copyOf(enabledTools);
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public UUID getUpdatedBy() {
    return updatedBy;
  }
}
