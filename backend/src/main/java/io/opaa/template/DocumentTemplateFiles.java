package io.opaa.template;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The bytes of a template's file, apart from the entity so a list never loads them. Deleting the
 * template deletes them by foreign key.
 */
@Component
class DocumentTemplateFiles {

  private final NamedParameterJdbcTemplate jdbc;

  DocumentTemplateFiles(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  void store(UUID templateId, byte[] content) {
    jdbc.update(
        "INSERT INTO document_template_files (template_id, content) VALUES (:id, :content)"
            + " ON CONFLICT (template_id) DO UPDATE SET content = EXCLUDED.content",
        Map.of("id", templateId, "content", content));
  }

  Optional<byte[]> load(UUID templateId) {
    return jdbc
        .query(
            "SELECT content FROM document_template_files WHERE template_id = :id",
            Map.of("id", templateId),
            (row, index) -> row.getBytes("content"))
        .stream()
        .findFirst();
  }
}
