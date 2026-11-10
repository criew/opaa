package io.opaa.agent;

import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditObjectType;
import io.opaa.asset.AssetTypeDefinition;
import io.opaa.permission.AssetType;
import org.springframework.stereotype.Component;

/**
 * The agent's declaration on the asset shell. Active only while the installation switch for agents
 * is on ({@link AgentSwitch#isEnabled()}): switched off, no read interface lists an agent and a
 * request naming one answers like an unknown asset, while ownership transfer, group and account
 * removal and the audit keep handling existing agents.
 */
@Component
class AgentAssetType implements AssetTypeDefinition {

  private final AgentSwitch agentSwitch;

  AgentAssetType(AgentSwitch agentSwitch) {
    this.agentSwitch = agentSwitch;
  }

  @Override
  public boolean enabled() {
    return agentSwitch.isEnabled();
  }

  @Override
  public AssetType assetType() {
    return Agent.ASSET_TYPE;
  }

  @Override
  public AuditObjectType auditObjectType() {
    return AuditObjectType.AGENT;
  }

  @Override
  public AuditEventType createdAuditEventType() {
    return AuditEventType.AGENT_CREATED;
  }

  @Override
  public String singular() {
    return "Agent";
  }

  @Override
  public boolean masculine() {
    return true;
  }

  @Override
  public String oblique() {
    return "Agenten";
  }

  @Override
  public String plural() {
    return "Agenten";
  }
}
