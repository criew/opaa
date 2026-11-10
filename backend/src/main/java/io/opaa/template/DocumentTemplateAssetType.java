package io.opaa.template;

import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditObjectType;
import io.opaa.asset.AssetTypeDefinition;
import io.opaa.permission.AssetType;
import org.springframework.stereotype.Component;

/**
 * The document template's declaration on the asset shell. Active only while the installation switch
 * "Vorlagen" is on; switched off, the type stays registered so ownership transfer, group and
 * account removal and the audit keep handling existing templates.
 */
@Component
class DocumentTemplateAssetType implements AssetTypeDefinition {

  private final DocumentTemplateSettingsService settings;

  DocumentTemplateAssetType(DocumentTemplateSettingsService settings) {
    this.settings = settings;
  }

  @Override
  public boolean enabled() {
    return settings.isEnabled();
  }

  @Override
  public AssetType assetType() {
    return DocumentTemplate.ASSET_TYPE;
  }

  @Override
  public AuditObjectType auditObjectType() {
    return AuditObjectType.DOCUMENT_TEMPLATE;
  }

  @Override
  public AuditEventType createdAuditEventType() {
    return AuditEventType.DOCUMENT_TEMPLATE_CREATED;
  }

  @Override
  public String singular() {
    return "Vorlage";
  }

  @Override
  public String plural() {
    return "Vorlagen";
  }
}
