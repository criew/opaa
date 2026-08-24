package io.opaa.library;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Navigational structure inside a {@link KnowledgeLibrary} (#820, Epic #520 Phase 2, ADR-0020) - a
 * real entity with a self-referencing parent, not a virtual path prefix derived from document
 * names. {@code null} {@link #parentFolderId} means the folder sits at the library's root; {@code
 * documents.folder_id} uses the identical convention one level down.
 *
 * <p><b>Ordner sind reine Navigation, keine Rechtegrenze</b> (ADR-0020, Entscheidung 3): this
 * entity carries no permission of its own. Every access check for a folder resolves through {@link
 * LibraryAccessService} against the owning {@link #libraryId}, exactly like a {@link
 * io.opaa.indexing.Document} does.
 */
@Entity
@Table(name = "library_folders")
public class LibraryFolder {

  @Id private UUID id;

  @Column(name = "library_id", nullable = false)
  private UUID libraryId;

  @Column(name = "parent_folder_id")
  private UUID parentFolderId;

  @Column(name = "name", nullable = false, length = 255)
  private String name;

  @Column(name = "organization_id", nullable = false)
  private UUID organizationId;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected LibraryFolder() {}

  public LibraryFolder(UUID libraryId, UUID parentFolderId, String name, UUID organizationId) {
    this.id = UUID.randomUUID();
    this.libraryId = libraryId;
    this.parentFolderId = parentFolderId;
    this.name = name;
    this.organizationId = organizationId;
    this.createdAt = Instant.now();
  }

  public void rename(String name) {
    this.name = name;
  }

  public UUID getId() {
    return id;
  }

  public UUID getLibraryId() {
    return libraryId;
  }

  public UUID getParentFolderId() {
    return parentFolderId;
  }

  public String getName() {
    return name;
  }

  public UUID getOrganizationId() {
    return organizationId;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
