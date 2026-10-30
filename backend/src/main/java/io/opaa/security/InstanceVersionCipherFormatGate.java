package io.opaa.security;

import io.opaa.instance.CurrentInstance;
import io.opaa.instance.InstanceProperties;
import java.time.Duration;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Opens the keyed format once every live instance runs this version (ADR-0053, Entscheidung 13),
 * unless {@link CipherFormatProperties#keyed()} holds it closed for a rollback window (ADR-0064,
 * Entscheidung 5): an older version reads only {@code enc:v1:}. The answer of the instance register
 * is kept per instance for one heartbeat interval, the resolution the register itself has, so a
 * write does not query it each time (ADR-0053).
 *
 * <p>{@link CurrentInstance} is looked up on first use: the encryptors are built together with the
 * JPA converters, before the register and its transaction manager exist.
 */
@Component
class InstanceVersionCipherFormatGate implements CipherFormatGate {

  private final boolean keyed;
  private final BooleanSupplier allOnThisVersion;
  private final long cacheNanos;
  private final LongSupplier nanoTime;

  private volatile boolean known;
  private volatile boolean cached;
  private volatile long cachedAt;

  @Autowired
  InstanceVersionCipherFormatGate(
      ObjectProvider<CurrentInstance> currentInstance,
      InstanceProperties instance,
      CipherFormatProperties format) {
    this(
        format.keyed(),
        () -> currentInstance.getObject().allLiveInstancesRunThisVersion(),
        instance.heartbeatInterval(),
        System::nanoTime);
  }

  InstanceVersionCipherFormatGate(
      boolean keyed, BooleanSupplier allOnThisVersion, Duration cacheFor, LongSupplier nanoTime) {
    this.keyed = keyed;
    this.allOnThisVersion = allOnThisVersion;
    this.cacheNanos = cacheFor.toNanos();
    this.nanoTime = nanoTime;
  }

  @Override
  public boolean writesKeyedFormat() {
    if (!keyed) {
      return false;
    }
    long now = nanoTime.getAsLong();
    if (known && now - cachedAt < cacheNanos) {
      return cached;
    }
    boolean answer = allOnThisVersion.getAsBoolean();
    cached = answer;
    cachedAt = now;
    known = true;
    return answer;
  }
}
