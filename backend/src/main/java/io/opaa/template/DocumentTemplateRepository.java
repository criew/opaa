package io.opaa.template;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** The document templates - rights and reach are the shell's, never asked here. */
public interface DocumentTemplateRepository extends JpaRepository<DocumentTemplate, UUID> {

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
