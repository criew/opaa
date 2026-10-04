package io.opaa.test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** A UTC clock a test moves forward; safe to read from other threads. */
public final class MutableClock extends Clock {

  private volatile Instant now;

  public MutableClock(Instant now) {
    this.now = now;
  }

  public synchronized void advance(Duration duration) {
    now = now.plus(duration);
  }

  @Override
  public Instant instant() {
    return now;
  }

  @Override
  public ZoneId getZone() {
    return ZoneOffset.UTC;
  }

  @Override
  public Clock withZone(ZoneId zone) {
    return this;
  }
}
