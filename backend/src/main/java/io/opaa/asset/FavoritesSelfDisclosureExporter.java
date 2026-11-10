package io.opaa.asset;

import static io.opaa.common.SelfDisclosureSectionReader.instant;
import static io.opaa.common.SelfDisclosureSectionReader.keyed;

import io.opaa.common.SelfDisclosureSectionReader;
import io.opaa.compliance.SelfDisclosureCategory;
import io.opaa.compliance.SelfDisclosureCategory.Field;
import io.opaa.compliance.SelfDisclosureCategory.Section;
import io.opaa.compliance.SelfDisclosureExporter;
import io.opaa.compliance.SelfDisclosureSink;
import io.opaa.compliance.SelfDisclosureSubject;
import io.opaa.permission.ReadableAssetNames;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The category {@code favorites} of the self-disclosure: the assets the person marked as favorite,
 * with type, name and when; the name only while she may still read the asset ({@link
 * ReadableAssetNames}).
 */
@Component
public class FavoritesSelfDisclosureExporter implements SelfDisclosureExporter {

  public static final SelfDisclosureCategory CATEGORY =
      new SelfDisclosureCategory(
          "favorites",
          "Favoriten",
          List.of(
              new Section(
                  "favorites",
                  "Ihre Favoriten",
                  List.of(
                      Field.of("assetType", "Art", "assets.asset_type"),
                      Field.of("asset", "Asset", "asset_favorites.asset_id", "assets.name"),
                      Field.of("createdAt", "Markiert", "asset_favorites.created_at")))));

  private final JdbcTemplate jdbc;
  private final SelfDisclosureSectionReader reader;
  private final ReadableAssetNames names;

  public FavoritesSelfDisclosureExporter(
      JdbcTemplate jdbc, PlatformTransactionManager transactionManager, ReadableAssetNames names) {
    this.jdbc = jdbc;
    this.names = names;
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
        "favorites",
        (afterTime, afterId, limit) ->
            names.apply(
                subject.accountId(),
                jdbc.query(
                    "SELECT f.asset_id, a.asset_type, a.name, f.created_at FROM asset_favorites f"
                        + " JOIN assets a ON a.id = f.asset_id"
                        + " WHERE f.user_id = ? AND f.organization_id = ?"
                        + " AND (f.created_at, f.asset_id) > (?, ?)"
                        + " ORDER BY f.created_at, f.asset_id LIMIT ?",
                    (rs, i) -> {
                      Map<String, Object> record = keyed(rs, "created_at", "asset_id");
                      record.put("assetType", rs.getString("asset_type"));
                      record.put("asset", rs.getString("name"));
                      record.put("createdAt", instant(rs.getTimestamp("created_at")));
                      ReadableAssetNames.mark(
                          record, rs.getObject("asset_id", UUID.class), rs.getString("asset_type"));
                      return record;
                    },
                    subject.accountId(),
                    subject.organizationId(),
                    afterTime,
                    afterId,
                    limit),
                "asset"));
  }
}
