package io.opaa.indexing;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "documents")
public class Document {

  @Id private UUID id;

  @Column(name = "file_name", nullable = false, length = 500)
  private String fileName;

  @Column(name = "file_path", nullable = false, length = 2000)
  private String filePath;

  @Column(name = "content_type")
  private String contentType;

  @Column(name = "file_size")
  private Long fileSize;

  @Column(name = "chunk_count")
  private int chunkCount;

  @Column(name = "indexed_at")
  private Instant indexedAt;

  @Column(name = "checksum", length = 64)
  private String checksum;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 20)
  private DocumentStatus status = DocumentStatus.PENDING;

  @Enumerated(EnumType.STRING)
  @Column(name = "source_type", nullable = false, length = 20)
  private DocumentSourceType sourceType = DocumentSourceType.FILESYSTEM;

  protected Document() {}

  public Document(String fileName, String filePath, String contentType, Long fileSize) {
    this.id = UUID.randomUUID();
    this.fileName = fileName;
    this.filePath = filePath;
    this.contentType = contentType;
    this.fileSize = fileSize;
    this.status = DocumentStatus.PENDING;
  }

  public Document(
      String fileName,
      String filePath,
      String contentType,
      Long fileSize,
      DocumentSourceType sourceType) {
    this(fileName, filePath, contentType, fileSize);
    this.sourceType = sourceType;
  }

  public UUID getId() {
    return id;
  }

  public String getFileName() {
    return fileName;
  }

  public String getFilePath() {
    return filePath;
  }

  public String getContentType() {
    return contentType;
  }

  public Long getFileSize() {
    return fileSize;
  }

  public int getChunkCount() {
    return chunkCount;
  }

  public void setChunkCount(int chunkCount) {
    this.chunkCount = chunkCount;
  }

  public Instant getIndexedAt() {
    return indexedAt;
  }

  public void setIndexedAt(Instant indexedAt) {
    this.indexedAt = indexedAt;
  }

  public DocumentStatus getStatus() {
    return status;
  }

  public void setStatus(DocumentStatus status) {
    this.status = status;
  }

  public String getChecksum() {
    return checksum;
  }

  public void setChecksum(String checksum) {
    this.checksum = checksum;
  }

  public DocumentSourceType getSourceType() {
    return sourceType;
  }

  public void setSourceType(DocumentSourceType sourceType) {
    this.sourceType = sourceType;
  }
}
