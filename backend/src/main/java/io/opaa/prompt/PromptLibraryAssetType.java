package io.opaa.prompt;

import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditObjectType;
import io.opaa.asset.AssetTypeDefinition;
import io.opaa.permission.AssetType;
import org.springframework.stereotype.Component;

/**
 * The prompt library's declaration on the asset shell. It restricts no reach: a prompt binds no
 * knowledge, so every release level and the listing are open to its managers.
 */
@Component
class PromptLibraryAssetType implements AssetTypeDefinition {

  @Override
  public AssetType assetType() {
    return PromptLibrary.ASSET_TYPE;
  }

  @Override
  public AuditObjectType auditObjectType() {
    return AuditObjectType.PROMPT_LIBRARY;
  }

  @Override
  public AuditEventType createdAuditEventType() {
    return AuditEventType.PROMPT_LIBRARY_CREATED;
  }

  @Override
  public String singular() {
    return "Prompt-Bibliothek";
  }

  @Override
  public String plural() {
    return "Prompt-Bibliotheken";
  }
}
