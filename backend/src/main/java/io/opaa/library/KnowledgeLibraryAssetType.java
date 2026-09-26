package io.opaa.library;

import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditObjectType;
import io.opaa.asset.Asset;
import io.opaa.asset.AssetTypeDefinition;
import io.opaa.common.ConflictException;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.permission.AssetType;
import org.hibernate.Hibernate;
import org.springframework.stereotype.Component;

/**
 * The knowledge library's declaration on the asset shell, and its one restriction: the share cap of
 * a connector library (#797, Maintainer-Festlegung vom 21.09.2026). A request above the cap is a
 * {@code 409}, not a {@code 403} - the caller's role is not in question, the requested state
 * conflicts with a ceiling the system administration set on this library. An upload library never
 * carries a narrower cap ({@code chk_knowledge_libraries_share_cap_upload_unrestricted}).
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

  @Override
  public void requireListedWithinLimits(Asset asset, boolean listed) {
    KnowledgeLibrary library = cappedLibrary(asset);
    if (library != null && listed && !library.isListedCap()) {
      throw new ConflictException(
          "Diese Bibliothek darf laut Systemverwaltung nicht im Katalog gelistet werden.");
    }
  }

  /**
   * The half of the cap that moved to the grant path with #1931: organization-wide reach is a grant
   * to "Alle Konten", so the ceiling has to be asked where that grant is written and not where a
   * reach field used to be set.
   */
  @Override
  public void requireAllAccountsGrantAllowed(Asset asset) {
    KnowledgeLibrary library = cappedLibrary(asset);
    if (library != null && !library.isAllAccountsGrantAllowed()) {
      throw new ConflictException(
          "Diese Bibliothek darf laut Systemverwaltung nicht an alle Konten freigegeben"
              + " werden.");
    }
  }

  /**
   * Resolves a proxy of the shell to the library behind it; an asset of this type that is no
   * library is refused rather than let past the cap. {@code null} for an upload library, which
   * carries no cap at all.
   */
  private static KnowledgeLibrary cappedLibrary(Asset asset) {
    if (!(Hibernate.unproxy(asset) instanceof KnowledgeLibrary library)) {
      throw new IllegalStateException(
          "asset " + asset.getId() + " of type " + asset.getAssetType() + " is no library");
    }
    return library.getSourceType().hasIndexingRun() ? library : null;
  }
}
