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
import org.hibernate.annotations.BatchSize;

/**
 * One delivered Dokumentart (migration 018 seed): a stable code that never changes once documents
 * reference it, its German label, the synonyms {@link DocumentTypeVocabulary} matches exactly
 * against, and - since migration 020 (#1263) - the Kompositum endings it may additionally be
 * recognized by, plus the tokens those endings must never claim. Read-only from the application's
 * point of view - an installation extends the lists by inserting rows.
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
  @BatchSize(size = 50)
  @CollectionTable(name = "document_type_synonyms", joinColumns = @JoinColumn(name = "code"))
  @Column(name = "synonym", nullable = false, length = 100)
  private Set<String> synonyms = new LinkedHashSet<>();

  @ElementCollection(fetch = FetchType.EAGER)
  @BatchSize(size = 50)
  @CollectionTable(name = "document_type_suffixes", joinColumns = @JoinColumn(name = "code"))
  private Set<DocumentTypeSuffix> suffixes = new LinkedHashSet<>();

  @ElementCollection(fetch = FetchType.EAGER)
  @BatchSize(size = 50)
  @CollectionTable(
      name = "document_type_suffix_exclusions",
      joinColumns = @JoinColumn(name = "code"))
  @Column(name = "token", nullable = false, length = 100)
  private Set<String> suffixExclusions = new LinkedHashSet<>();

  protected DocumentTypeVocabularyEntry() {}

  public DocumentTypeVocabularyEntry(
      String code, String label, int sortOrder, Set<String> synonyms) {
    this(code, label, sortOrder, synonyms, Set.of(), Set.of());
  }

  public DocumentTypeVocabularyEntry(
      String code,
      String label,
      int sortOrder,
      Set<String> synonyms,
      Set<DocumentTypeSuffix> suffixes,
      Set<String> suffixExclusions) {
    this.code = code;
    this.label = label;
    this.sortOrder = sortOrder;
    this.synonyms = new LinkedHashSet<>(synonyms);
    this.suffixes = new LinkedHashSet<>(suffixes);
    this.suffixExclusions = new LinkedHashSet<>(suffixExclusions);
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

  public Set<DocumentTypeSuffix> getSuffixes() {
    return suffixes;
  }

  /** Tokens the {@link #getSuffixes()} of this Dokumentart must never claim. */
  public Set<String> getSuffixExclusions() {
    return suffixExclusions;
  }
}
