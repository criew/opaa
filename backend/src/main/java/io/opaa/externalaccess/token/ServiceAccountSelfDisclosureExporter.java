package io.opaa.externalaccess.token;

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
 * The category {@code service-accounts} of the self-disclosure: the service accounts the person is
 * named responsible for, by name and description. A service account is no person and has no
 * self-disclosure of its own; this is what it stores about her.
 */
@Component
public class ServiceAccountSelfDisclosureExporter implements SelfDisclosureExporter {

  public static final SelfDisclosureCategory CATEGORY =
      new SelfDisclosureCategory(
          "service-accounts",
          "Dienstkonten, für die Sie verantwortlich sind",
          List.of(
              new Section(
                  "serviceAccounts",
                  "Dienstkonten",
                  List.of(
                      Field.of("name", "Name", "service_accounts.name"),
                      Field.of("description", "Beschreibung", "service_accounts.description")))));

  private final JdbcTemplate jdbc;
  private final SelfDisclosureSectionReader reader;

  public ServiceAccountSelfDisclosureExporter(
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
        "serviceAccounts",
        (afterTime, afterId, limit) ->
            jdbc.query(
                "SELECT s.id, s.name, s.description, s.created_at FROM service_accounts s"
                    + " WHERE s.responsible_user_id = ? AND s.organization_id = ?"
                    + " AND (s.created_at, s.id) > (?, ?) ORDER BY s.created_at, s.id LIMIT ?",
                (rs, i) -> {
                  Map<String, Object> record = keyed(rs, "created_at", "id");
                  record.put("name", rs.getString("name"));
                  record.put("description", rs.getString("description"));
                  return record;
                },
                subject.accountId(),
                subject.organizationId(),
                afterTime,
                afterId,
                limit));
  }
}
