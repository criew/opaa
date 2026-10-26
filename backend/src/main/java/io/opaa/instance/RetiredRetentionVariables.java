package io.opaa.instance;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.context.SmartLifecycle;
import org.springframework.core.convert.ConversionException;
import org.springframework.core.env.Environment;

/**
 * The retired settings that once set a deletion period the administration now holds in the database
 * (ADR-0060, Entscheidungen 1 und 13). Their value is never taken over, it only brakes: while a
 * live instance still sets one to a longer period, switches the deletion off or sets what the
 * former binding could not read, the deletion it governs pauses until the administration sets a
 * value of its own. Each instance reports its environment into the register after registering, and
 * every run asks the register across all live instances, so the instance that wins the run lock
 * decides the same (ADR-0053).
 */
public class RetiredRetentionVariables implements SmartLifecycle {

  public static final String CHAT_ARCHIVE = "OPAA_CHAT_AUTO_CLEANUP_ARCHIVE_AFTER_DAYS";
  public static final String CHAT_DELETE = "OPAA_CHAT_AUTO_CLEANUP_DELETE_AFTER_DAYS";
  public static final String LOCKED_ENABLED = "OPAA_CHAT_LOCKED_ACCOUNT_CLEANUP_ENABLED";
  public static final String LOCKED_DELETE = "OPAA_CHAT_LOCKED_ACCOUNT_CLEANUP_DELETE_AFTER_DAYS";
  public static final String INCIDENT_SCOPE_YEARS = "OPAA_AUDIT_INCIDENT_SCOPE_RETENTION_YEARS";
  public static final String PRIVATE_LIBRARY_DELETION =
      "OPAA_CONNECTION_PRIVATE_LIBRARY_DELETION_DAYS";

  /** Every retired variable with the property it was bound to, in a fixed order. */
  public static final Map<String, String> PROPERTIES = properties();

  /**
   * A retired setting a live instance still sets to {@code retiredValue} while {@code
   * effectiveValue} applies.
   */
  public record Pause(String variable, String retiredValue, String effectiveValue) {

    /** The error a paused run logs, naming what to do; values are periods, never personal. */
    public String error(String run) {
      return error(run, "Administration > Datenschutz");
    }

    /** {@link #error(String)} for a period the administration sets under {@code place}. */
    public String error(String run, String place) {
      return run
          + ": deletion paused, "
          + variable
          + " is still set to "
          + retiredValue
          + " on a live instance but is no longer read and "
          + effectiveValue
          + " applies; set a value under "
          + place
          + " or remove the variable";
    }
  }

  private final InstanceRegistry registry;
  private final CurrentInstance currentInstance;
  private final Environment environment;
  private volatile boolean running;

  RetiredRetentionVariables(
      InstanceRegistry registry, CurrentInstance currentInstance, Environment environment) {
    this.registry = registry;
    this.currentInstance = currentInstance;
    this.environment = environment;
  }

  /**
   * The retired settings {@code environment} still sets, by variable name: under the property name
   * (any property source, the variable by relaxed binding included) or the variable name.
   */
  static Map<String, String> setIn(Environment environment) {
    Map<String, String> set = new LinkedHashMap<>();
    PROPERTIES.forEach(
        (variable, property) -> {
          String value = environment.getProperty(property, environment.getProperty(variable));
          if (value != null) {
            set.put(variable, value.trim());
          }
        });
    return set;
  }

  /**
   * The pauses for {@code variable}: every live report of a period longer than {@code effective},
   * and every report the former binding would not have read.
   */
  public List<Pause> longerThan(String variable, int effective) {
    List<Pause> pauses = new ArrayList<>();
    for (String reported : reported(variable)) {
      if (keepsLonger(reported, effective)) {
        add(pauses, new Pause(variable, reported, Integer.toString(effective)));
      }
    }
    return pauses;
  }

  /**
   * The pauses for {@code variable}: every live report that switched the deletion off while it is
   * on, and every report the former binding would not have read.
   */
  public List<Pause> switchedOff(String variable, boolean effective) {
    List<Pause> pauses = new ArrayList<>();
    for (String reported : reported(variable)) {
      if (switchesOff(reported, effective)) {
        add(pauses, new Pause(variable, reported, Boolean.toString(effective)));
      }
    }
    return pauses;
  }

  private List<String> reported(String variable) {
    List<String> values = new ArrayList<>();
    for (Map<String, String> instance : registry.liveRetiredSettings()) {
      String value = instance.get(variable);
      if (value != null) {
        values.add(value.trim());
      }
    }
    return values;
  }

  private static void add(List<Pause> pauses, Pause pause) {
    if (!pauses.contains(pause)) {
      pauses.add(pause);
    }
  }

  /**
   * Whether {@code reported}, read the way the former configuration binding read it ({@code +730},
   * {@code 0x2DA}), keeps longer than {@code effective}; a value it could not read pauses as well,
   * the safe side of an irreversible deletion.
   */
  static boolean keepsLonger(String reported, int effective) {
    Integer value = converted(reported, Integer.class);
    return value == null || value > effective;
  }

  /**
   * Whether {@code reported}, read like the former binding ({@code false}, {@code off}, {@code no},
   * {@code 0}), switches off a deletion that is on; a value it could not read pauses as well.
   */
  static boolean switchesOff(String reported, boolean effective) {
    Boolean value = converted(reported, Boolean.class);
    return value == null || (effective && !value);
  }

  private static <T> T converted(String text, Class<T> type) {
    try {
      return ApplicationConversionService.getSharedInstance().convert(text, type);
    } catch (ConversionException e) {
      return null;
    }
  }

  private static Map<String, String> properties() {
    Map<String, String> properties = new LinkedHashMap<>();
    properties.put(CHAT_ARCHIVE, "opaa.chat.auto-cleanup.archive-after-days");
    properties.put(CHAT_DELETE, "opaa.chat.auto-cleanup.delete-after-days");
    properties.put(LOCKED_ENABLED, "opaa.chat.locked-account-cleanup.enabled");
    properties.put(LOCKED_DELETE, "opaa.chat.locked-account-cleanup.delete-after-days");
    properties.put(INCIDENT_SCOPE_YEARS, "opaa.audit.incident-scope-retention-years");
    properties.put(PRIVATE_LIBRARY_DELETION, "opaa.connection.private-library-deletion-days");
    return Collections.unmodifiableMap(properties);
  }

  @Override
  public void start() {
    registry.recordRetiredSettings(currentInstance.id(), setIn(environment));
    running = true;
  }

  @Override
  public void stop() {
    running = false;
  }

  @Override
  public boolean isRunning() {
    return running;
  }

  @Override
  public int getPhase() {
    return CurrentInstance.REGISTERED_PHASE;
  }
}
