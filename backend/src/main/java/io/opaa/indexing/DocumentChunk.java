package io.opaa.indexing;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "document_chunks")
public class DocumentChunk {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(name = "document_id", nullable = false)
  private UUID documentId;

  @Column(name = "chunk_index", nullable = false)
  private int chunkIndex;

  @Column(name = "chunk_text", nullable = false, columnDefinition = "text")
  private String chunkText;

  @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
  private Instant createdAt;

  // embedding column is managed via native SQL (pgvector type)

  protected DocumentChunk() {}

  public DocumentChunk(UUID documentId, int chunkIndex, String chunkText) {
    this.documentId = documentId;
    this.chunkIndex = chunkIndex;
    this.chunkText = chunkText;
  }

  public UUID getId() {
    return id;
  }

  public UUID getDocumentId() {
    return documentId;
  }

  public int getChunkIndex() {
    return chunkIndex;
  }

  public String getChunkText() {
    return chunkText;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
