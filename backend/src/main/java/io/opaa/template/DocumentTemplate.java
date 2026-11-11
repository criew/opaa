package io.opaa.template;

import io.opaa.api.types.AssetOwnerType;
import io.opaa.asset.Asset;
import io.opaa.permission.AssetType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.PrimaryKeyJoinColumn;
import jakarta.persistence.Table;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A document template: one DOCX file with placeholders that an agent fills through the sandbox.
 * Name, owner and release level are the shell's; the type row holds the file's description and the
 * placeholders read from it. The bytes live in {@code document_template_files} ({@link
 * DocumentTemplateFiles}), loaded only for a download or a filling.
 */
@Entity
@Table(name = "document_templates")
@PrimaryKeyJoinColumn(name = "id")
public class DocumentTemplate extends Asset {

  /** The value {@code assets.asset_type} and every grant on a document template carry. */
  public static final AssetType ASSET_TYPE = AssetType.of("DOCUMENT_TEMPLATE");

  /** The shell's organization repeated on the type row. */
  @Column(name = "organization_id", nullable = false, updatable = false)
  private UUID templateOrganizationId;

  @Column(name = "file_name", nullable = false)
  private String fileName;

  @Column(name = "size_bytes", nullable = false)
  private long sizeBytes;

  @Column(name = "sha256", nullable = false, length = 64)
  private String sha256;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "placeholders", nullable = false, columnDefinition = "jsonb")
  private String placeholders;

  /** Counts every replacement of the file (the "Fassung"); {@code null} is read as {@code 1}. */
  @Column(name = "revision")
  private Integer revision = 1;

  protected DocumentTemplate() {}

  private DocumentTemplate(
      UUID organizationId,
      String name,
      String description,
      AssetOwnerType ownerType,
      UUID ownerId,
      StoredFile file) {
    super(ASSET_TYPE, organizationId, name, description, ownerType, ownerId);
    this.templateOrganizationId = organizationId;
    apply(file);
  }

  public static DocumentTemplate ownedByUser(
      UUID organizationId, String name, String description, UUID ownerUserId, StoredFile file) {
    return new DocumentTemplate(
        organizationId, name, description, AssetOwnerType.USER, ownerUserId, file);
  }

  public static DocumentTemplate ownedByGroup(
      UUID organizationId, String name, String description, UUID ownerGroupId, StoredFile file) {
    return new DocumentTemplate(
        organizationId, name, description, AssetOwnerType.GROUP, ownerGroupId, file);
  }

  /** The file was replaced; the template counts as changed with it and gets a new revision. */
  void replaceFile(StoredFile file) {
    apply(file);
    this.revision = getRevision() + 1;
    touch();
  }

  private void apply(StoredFile file) {
    this.fileName = file.fileName();
    this.sizeBytes = file.sizeBytes();
    this.sha256 = file.sha256();
    this.placeholders = PlaceholdersJson.write(file.placeholders());
  }

  public String getFileName() {
    return fileName;
  }

  public long getSizeBytes() {
    return sizeBytes;
  }

  public String getSha256() {
    return sha256;
  }

  /** The file's revision, from 1; an agent answer and its result file name the one they filled. */
  public int getRevision() {
    return revision == null ? 1 : revision;
  }

  public List<TemplatePlaceholder> getPlaceholders() {
    return PlaceholdersJson.read(placeholders);
  }

  /** What an admitted upload left behind: its name, size, digest and placeholders. */
  public record StoredFile(
      String fileName, long sizeBytes, String sha256, List<TemplatePlaceholder> placeholders) {}
}
