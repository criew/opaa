package io.opaa.indexing.trigger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import io.opaa.indexing.job.IndexingJobService;
import io.opaa.indexing.job.IndexingRunEventRepository;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.KnowledgeLibraryRepository;
import io.opaa.test.BareLibraries;
import io.opaa.test.OpaaIntegrationTest;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Two instances tick for the same due time of a short library run (ADR-0053, Entscheidung 7): the
 * run is over before the second instance ticks, so no running job holds the library any more, and
 * only the conditional booking on the library row keeps the second tick from starting it again.
 * Both schedulers see this class's library only.
 */
@OpaaIntegrationTest
class LibraryScheduleBookingIntegrationTest {

  private static final String DAILY_AT_THREE = "0 0 3 * * *";
  private static final Instant DUE = Instant.parse("2026-10-20T03:00:00Z");

  @Autowired private KnowledgeLibraryRepository libraries;
  @Autowired private IndexingJobService indexingJobService;
  @Autowired private IndexingRunEventRepository runEvents;
  @Autowired private JdbcTemplate jdbcTemplate;

  private final UUID libraryId = UUID.randomUUID();
  private final DocumentIndexingService indexing = mock(DocumentIndexingService.class);

  @BeforeEach
  void createScheduledLibrary() {
    BareLibraries.create(jdbcTemplate, libraryId);
    jdbcTemplate.update(
        "UPDATE knowledge_libraries SET source_type = 'HTTP_DIRECTORY', schedule_enabled = true,"
            + " schedule_cron = ? WHERE id = ?",
        DAILY_AT_THREE,
        libraryId);
  }

  @AfterEach
  void removeLibrary() {
    BareLibraries.remove(jdbcTemplate, libraryId);
  }

  @Test
  void aShortRunStartsOncePerDueTimeWhenTwoInstancesTick() {
    LibraryIndexingScheduler first = instanceTickingAt(DUE.plusSeconds(1));
    LibraryIndexingScheduler second = instanceTickingAt(DUE.plusSeconds(4));

    first.triggerDueLibraries();
    second.triggerDueLibraries();
    first.triggerDueLibraries();

    verify(indexing, times(1)).triggerScheduledIndexing(any());
    Map<String, Object> row =
        jdbcTemplate.queryForMap(
            "SELECT schedule_booked_at, schedule_booked_cron FROM knowledge_libraries WHERE id = ?",
            libraryId);
    assertThat(((Timestamp) row.get("schedule_booked_at")).toInstant()).isEqualTo(DUE);
    assertThat(row.get("schedule_booked_cron")).isEqualTo(DAILY_AT_THREE);
  }

  @Test
  void theNextDueTimeIsBookedAgain() {
    instanceTickingAt(DUE.plusSeconds(1)).triggerDueLibraries();

    instanceTickingAt(DUE.plusSeconds(24 * 3600 + 2)).triggerDueLibraries();

    verify(indexing, times(2)).triggerScheduledIndexing(any());
  }

  @Test
  void aChangedScheduleIsBookedUnderItsOwnCron() {
    instanceTickingAt(DUE.plusSeconds(1)).triggerDueLibraries();
    jdbcTemplate.update(
        "UPDATE knowledge_libraries SET schedule_cron = '0 30 3 * * *' WHERE id = ?", libraryId);

    instanceTickingAt(DUE.plusSeconds(30 * 60 + 1)).triggerDueLibraries();

    verify(indexing, times(2)).triggerScheduledIndexing(any());
  }

  private LibraryIndexingScheduler instanceTickingAt(Instant now) {
    return new LibraryIndexingScheduler(
        ownLibraryOnly(),
        indexing,
        indexingJobService,
        runEvents,
        () -> now,
        Clock.system(ZoneOffset.UTC));
  }

  /** The repository, with the scheduled libraries narrowed to this class's own. */
  private KnowledgeLibraryRepository ownLibraryOnly() {
    return (KnowledgeLibraryRepository)
        Proxy.newProxyInstance(
            KnowledgeLibraryRepository.class.getClassLoader(),
            new Class<?>[] {KnowledgeLibraryRepository.class},
            (proxy, method, args) -> {
              if (method.getName().equals("findByScheduleEnabledTrue")) {
                List<KnowledgeLibrary> all = libraries.findByScheduleEnabledTrue();
                return all.stream().filter(library -> library.getId().equals(libraryId)).toList();
              }
              try {
                return method.invoke(libraries, args);
              } catch (InvocationTargetException e) {
                throw e.getCause();
              }
            });
  }
}
