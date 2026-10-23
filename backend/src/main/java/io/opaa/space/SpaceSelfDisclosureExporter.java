package io.opaa.space;

import static io.opaa.common.SelfDisclosureSectionReader.instant;
import static io.opaa.common.SelfDisclosureSectionReader.keyed;

import io.opaa.common.SelfDisclosureSectionReader;
import io.opaa.compliance.SelfDisclosureCategory;
import io.opaa.compliance.SelfDisclosureCategory.Field;
import io.opaa.compliance.SelfDisclosureCategory.Section;
import io.opaa.compliance.SelfDisclosureExporter;
import io.opaa.compliance.SelfDisclosureSink;
import io.opaa.compliance.SelfDisclosureSubject;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The category {@code spaces} of the self-disclosure: the spaces the person owns with their model
 * instruction, her own memberships and the assets she assigned to a space. Memberships through a
 * group are not hers and stay out; her group memberships are in the category {@code groups}.
 */
@Component
public class SpaceSelfDisclosureExporter implements SelfDisclosureExporter {

  public static final SelfDisclosureCategory CATEGORY =
      new SelfDisclosureCategory(
          "spaces",
          "Spaces",
          List.of(
              new Section(
                  "ownSpaces",
                  "Ihre Spaces",
                  List.of(
                      Field.of("name", "Name", "spaces.name"),
                      Field.of("description", "Beschreibung", "spaces.description"),
                      Field.of("modelInstruction", "Modellanweisung", "spaces.model_instruction"),
                      Field.of("personal", "Persönlicher Space", "spaces.is_default"),
                      Field.of("archived", "Archiviert", "spaces.archived"),
                      Field.of("createdAt", "Angelegt", "spaces.created_at"))),
              new Section(
                  "memberships",
                  "Ihre Mitgliedschaften",
                  List.of(
                      Field.of("space", "Space", "space_memberships.space_id", "spaces.name"),
                      Field.of("role", "Rolle", "space_memberships.role"),
                      Field.of("createdAt", "Seit", "space_memberships.created_at"))),
              new Section(
                  "assignments",
                  "Von Ihnen zugeordnete Assets",
                  List.of(
                      Field.of(
                          "space", "Space", "space_asset_associations.space_id", "spaces.name"),
                      Field.of("assetType", "Art", "assets.asset_type"),
                      Field.of(
                          "asset", "Asset", "space_asset_associations.asset_id", "assets.name"),
                      Field.of(
                          "createdAt", "Zugeordnet", "space_asset_associations.created_at")))));

  private final JdbcTemplate jdbc;
  private final SelfDisclosureSectionReader reader;

  public SpaceSelfDisclosureExporter(
      JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
    this.jdbc = jdbc;
    this.reader = new SelfDisclosureSectionReader(transactionManager);
  }

  @Override
  public SelfDisclosureCategory category() {
    return CATEGORY;
  }

  @Override
  public void export(SelfDisclosureSubject subject, SelfDisclosureSink sink) throws IOException {
    reader.copy(
        sink,
        "ownSpaces",
        (afterTime, afterId, limit) ->
            jdbc.query(
                "SELECT id, name, description, model_instruction, is_default, archived,"
                    + " created_at FROM spaces WHERE owner_id = ? AND organization_id = ?"
                    + " AND (created_at, id) > (?, ?) ORDER BY created_at, id LIMIT ?",
                (rs, i) -> {
                  Map<String, Object> record = keyed(rs, "created_at", "id");
                  record.put("name", rs.getString("name"));
                  record.put("description", rs.getString("description"));
                  record.put("modelInstruction", rs.getString("model_instruction"));
                  record.put("personal", rs.getBoolean("is_default"));
                  record.put("archived", rs.getBoolean("archived"));
                  record.put("createdAt", instant(rs.getTimestamp("created_at")));
                  return record;
                },
                subject.accountId(),
                subject.organizationId(),
                afterTime,
                afterId,
                limit));
    reader.copy(
        sink,
        "memberships",
        (afterTime, afterId, limit) ->
            jdbc.query(
                "SELECT m.id, s.name, m.role, m.created_at FROM space_memberships m"
                    + " JOIN spaces s ON s.id = m.space_id"
                    + " WHERE m.user_id = ? AND m.organization_id = ?"
                    + " AND (m.created_at, m.id) > (?, ?) ORDER BY m.created_at, m.id LIMIT ?",
                (rs, i) -> {
                  Map<String, Object> record = keyed(rs, "created_at", "id");
                  record.put("space", rs.getString("name"));
                  record.put("role", rs.getString("role"));
                  record.put("createdAt", instant(rs.getTimestamp("created_at")));
                  return record;
                },
                subject.accountId(),
                subject.organizationId(),
                afterTime,
                afterId,
                limit));
    reader.copy(
        sink,
        "assignments",
        (afterTime, afterId, limit) ->
            jdbc.query(
                "SELECT x.id, s.name AS space_name, a.asset_type, a.name AS asset_name,"
                    + " x.created_at FROM space_asset_associations x"
                    + " LEFT JOIN spaces s ON s.id = x.space_id"
                    + " LEFT JOIN assets a ON a.id = x.asset_id"
                    + " WHERE x.created_by_user_id = ? AND x.organization_id = ?"
                    + " AND (x.created_at, x.id) > (?, ?) ORDER BY x.created_at, x.id LIMIT ?",
                (rs, i) -> {
                  Map<String, Object> record = keyed(rs, "created_at", "id");
                  record.put("space", rs.getString("space_name"));
                  record.put("assetType", rs.getString("asset_type"));
                  record.put("asset", rs.getString("asset_name"));
                  record.put("createdAt", instant(rs.getTimestamp("created_at")));
                  return record;
                },
                subject.accountId(),
                subject.organizationId(),
                afterTime,
                afterId,
                limit));
  }
}
