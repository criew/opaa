package io.opaa.prompt;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A named, reusable instruction in exactly one {@link PromptLibrary}
 * (docs/features/spaces-and-assets.md#prompt-bibliothek). {@link #name} is unique within the
 * library ({@code uk_prompts_library_name}) and is what the slash command names; the text and its
 * variables are only ever stored together, as {@link PromptTemplate#validate} accepted them.
 */
@Entity
@Table(name = "prompts")
public class Prompt {

  @Id private UUID id;

  @Column(name = "library_id", nullable = false, updatable = false)
  private UUID libraryId;

  @Column(name = "organization_id", nullable = false, updatable = false)
  private UUID organizationId;

  @Column(name = "name", nullable = false, length = 64)
  private String name;

  @Column(name = "title", nullable = false, length = 255)
  private String title;

  @Column(name = "description", length = 2000)
  private String description;

  @Column(name = "text", nullable = false)
  private String text;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "variables", nullable = false, columnDefinition = "jsonb")
  private String variables;

  @Column(name = "sort_order", nullable = false)
  private int sortOrder;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected Prompt() {}

  Prompt(PromptLibrary library, PromptContent content) {
    this.id = UUID.randomUUID();
    this.libraryId = library.getId();
    this.organizationId = library.getOrganizationId();
    apply(content);
  }

  /** Replaces everything but identity and library. */
  void apply(PromptContent content) {
    Objects.requireNonNull(content, "content");
    this.name = content.name();
    this.title = content.title();
    this.description = content.description();
    this.text = content.text();
    this.variables = PromptVariablesJson.write(content.variables());
    this.sortOrder = content.sortOrder();
  }

  @PrePersist
  void onCreate() {
    Instant now = Instant.now();
    this.createdAt = now;
    this.updatedAt = now;
  }

  @PreUpdate
  void onUpdate() {
    this.updatedAt = Instant.now();
  }

  public UUID getId() {
    return id;
  }

  public UUID getLibraryId() {
    return libraryId;
  }

  public UUID getOrganizationId() {
    return organizationId;
  }

  public String getName() {
    return name;
  }

  public String getTitle() {
    return title;
  }

  public String getDescription() {
    return description;
  }

  public String getText() {
    return text;
  }

  public List<PromptVariable> getVariables() {
    return PromptVariablesJson.read(variables);
  }

  public int getSortOrder() {
    return sortOrder;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
