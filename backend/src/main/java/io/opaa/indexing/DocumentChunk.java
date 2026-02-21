package io.opaa.indexing;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "document_chunks")
public class DocumentChunk {

  @Id private UUID id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "document_id", nullable = false)
  private Document document;

  @Column(name = "chunk_index", nullable = false)
  private int chunkIndex;

  @Column(name = "chunk_text", nullable = false, columnDefinition = "text")
  private String chunkText;

  @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
  private Instant createdAt;

  // embedding column is managed via native SQL (pgvector type)

  protected DocumentChunk() {}

  public DocumentChunk(Document document, int chunkIndex, String chunkText) {
    this.id = UUID.randomUUID();
    this.document = document;
    this.chunkIndex = chunkIndex;
    this.chunkText = chunkText;
  }

  public UUID getId() {
    return id;
  }

  public Document getDocument() {
    return document;
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
