package io.opaa.indexing.metadata;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * One delivered Dokumentart (migration 018 seed): a stable code that never changes once documents
 * reference it, its German label and the synonyms {@link DocumentTypeVocabulary} matches exactly
 * against. Read-only from the application's point of view - an installation extends the list by
 * inserting rows.
 */
@Entity
@Table(name = "document_type_vocabulary")
public class DocumentTypeVocabularyEntry {

  @Id
  @Column(name = "code", length = 50)
  private String code;

  @Column(name = "label", nullable = false, length = 100)
  private String label;

  @Column(name = "sort_order", nullable = false)
  private int sortOrder;

  @ElementCollection(fetch = FetchType.EAGER)
  @CollectionTable(name = "document_type_synonyms", joinColumns = @JoinColumn(name = "code"))
  @Column(name = "synonym", nullable = false, length = 100)
  private Set<String> synonyms = new LinkedHashSet<>();

  protected DocumentTypeVocabularyEntry() {}

  public DocumentTypeVocabularyEntry(
      String code, String label, int sortOrder, Set<String> synonyms) {
    this.code = code;
    this.label = label;
    this.sortOrder = sortOrder;
    this.synonyms = new LinkedHashSet<>(synonyms);
  }

  public String getCode() {
    return code;
  }

  public String getLabel() {
    return label;
  }

  public int getSortOrder() {
    return sortOrder;
  }

  public Set<String> getSynonyms() {
    return synonyms;
  }
}
