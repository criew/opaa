package io.opaa.selfdisclosure;

import io.opaa.compliance.SelfDisclosureCategory;
import io.opaa.compliance.SelfDisclosureCategory.Field;
import io.opaa.compliance.SelfDisclosureCategory.Section;
import io.opaa.compliance.SelfDisclosureExporter;
import io.opaa.compliance.SelfDisclosureSink;
import io.opaa.compliance.SelfDisclosureSubject;
import io.opaa.selfdisclosure.SelfDisclosureUsageRepository.Usage;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The category {@code self-disclosure}: what OPAA keeps about the person's own self-disclosures -
 * the counters of the day, the last completed export and the result of the last attempt. The
 * reservation and the ticket stay out; they only concern the running export.
 */
@Component
public class SelfDisclosureStateExporter implements SelfDisclosureExporter {

  public static final SelfDisclosureCategory CATEGORY =
      new SelfDisclosureCategory(
          "self-disclosure",
          "Selbstauskunft",
          List.of(
              new Section(
                  "state",
                  "Stand Ihrer Selbstauskunft",
                  List.of(
                      Field.of("day", "Tag der Zähler", "self_disclosure_usage.count_day"),
                      Field.of(
                          "starts", "Starts an diesem Tag", "self_disclosure_usage.starts_count"),
                      Field.of(
                          "completed",
                          "Vollständige Exporte an diesem Tag",
                          "self_disclosure_usage.completed_count"),
                      Field.of(
                          "lastCompletedAt",
                          "Letzter vollständiger Export",
                          "self_disclosure_usage.last_completed_at"),
                      Field.of(
                          "lastResult",
                          "Ergebnis des letzten beendeten Versuchs",
                          "self_disclosure_usage.last_result")))));

  private final SelfDisclosureUsageRepository usage;
  private final TransactionTemplate readOnly;

  public SelfDisclosureStateExporter(
      SelfDisclosureUsageRepository usage, PlatformTransactionManager transactionManager) {
    this.usage = usage;
    this.readOnly = new TransactionTemplate(transactionManager);
    this.readOnly.setReadOnly(true);
  }

  @Override
  public SelfDisclosureCategory category() {
    return CATEGORY;
  }

  @Override
  public void export(SelfDisclosureSubject subject, SelfDisclosureSink sink) throws IOException {
    Optional<Usage> row = readOnly.execute(status -> usage.find(subject.accountId()));
    if (row == null || row.isEmpty()) {
      return;
    }
    Usage state = row.get();
    Map<String, Object> record = new LinkedHashMap<>();
    record.put("day", state.countDay());
    record.put("starts", state.startsCount());
    record.put("completed", state.completedCount());
    record.put("lastCompletedAt", state.lastCompletedAt());
    record.put(
        "lastResult",
        state.lastResult() == null
            ? null
            : "COMPLETED".equals(state.lastResult()) ? "abgeschlossen" : "abgebrochen");
    sink.section("state").write(record);
  }
}
