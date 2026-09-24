package io.opaa.test;

import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditObjectType;
import io.opaa.asset.AssetTypeDefinition;
import io.opaa.permission.AssetType;

/**
 * A second asset type the tests define themselves (#1899, #1900): a declaration and nothing else -
 * no table of its own, no entity, no grant or history logic. Assets of this type are written
 * straight into {@code assets}; everything else the shell does for them. It borrows the audit
 * vocabulary of a library: the audit log accepts no object type the product does not know.
 */
public final class TestPromptLibraryAssetType implements AssetTypeDefinition {

  public static final AssetType PROMPT_LIBRARY = AssetType.of("PROMPT_LIBRARY");

  @Override
  public AssetType assetType() {
    return PROMPT_LIBRARY;
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
    return "Prompt-Bibliothek";
  }

  @Override
  public String plural() {
    return "Prompt-Bibliotheken";
  }
}
