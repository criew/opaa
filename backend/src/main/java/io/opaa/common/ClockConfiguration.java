package io.opaa.common;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The one wall clock every service reads the current time from, so a test can substitute a fixed or
 * stepping clock for an expiry, a due time or a deadline. Server local time: the same zone a
 * {@code @Scheduled(cron = ...)} trigger runs in and that the mail formats print dates in. Entities
 * stamp {@code createdAt} and the like themselves, in their constructor or {@code @PrePersist};
 * those stamps are not decisions and stay outside the clock. A point in time another instance reads
 * and compares comes from the {@link DatabaseClock} instead (ADR-0053, Entscheidung 9).
 */
@Configuration
public class ClockConfiguration {

  @Bean
  Clock clock() {
    return Clock.systemDefaultZone();
  }
}
