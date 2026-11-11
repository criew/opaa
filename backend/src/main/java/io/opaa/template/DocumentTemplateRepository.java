package io.opaa.template;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** The document templates - rights and reach are the shell's, never asked here. */
public interface DocumentTemplateRepository extends JpaRepository<DocumentTemplate, UUID> {

  /**
   * Locks the template's row until the transaction ends, before its file is replaced: two
   * replacements, also on two instances, each count their own revision.
   */
  @Query(
      value = "SELECT id FROM document_templates WHERE id = :templateId FOR UPDATE",
      nativeQuery = true)
  List<UUID> lockById(@Param("templateId") UUID templateId);

  /** The number of placeholders of each template - its extent in the catalog. */
  @Query(
      value =
          "SELECT CAST(id AS varchar) AS templateId, jsonb_array_length(placeholders) AS count"
              + " FROM document_templates WHERE id IN (:ids)",
      nativeQuery = true)
  List<PlaceholderCount> countPlaceholders(@Param("ids") Collection<UUID> ids);

  interface PlaceholderCount {
    String getTemplateId();

    long getCount();
  }
}
