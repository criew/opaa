package io.opaa.library;

import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditObjectType;
import io.opaa.asset.AssetTypeDefinition;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.permission.AssetType;
import org.springframework.stereotype.Component;

/**
 * The knowledge library's declaration on the asset shell. It restricts no grant: who may reach
 * "Alle Konten" with a connector library is decided by the right to create it.
 */
@Component
class KnowledgeLibraryAssetType implements AssetTypeDefinition {

  @Override
  public AssetType assetType() {
    return KnowledgeLibrary.ASSET_TYPE;
  }

  @Override
  public AuditObjectType auditObjectType() {
    return AuditObjectType.KNOWLEDGE_LIBRARY;
  }

  @Override
  public AuditEventType createdAuditEventType() {
    return AuditEventType.LIBRARY_CREATED;
  }

  @Override
  public String singular() {
    return "Bibliothek";
  }

  @Override
  public String plural() {
    return "Bibliotheken";
  }
}
