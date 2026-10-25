package io.opaa.library;

import io.opaa.auth.CurrentUser;
import io.opaa.common.FieldValidationException;
import io.opaa.indexing.registry.SourceConnectorRegistry;
import io.opaa.indexing.spi.FullSyncRhythm;
import io.opaa.indexing.spi.SourceConnectorDescriptor;
import io.opaa.settings.SettingsSection;
import io.opaa.settings.SettingsValues;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * The installation-wide full-sync rhythm per source type, keyed by the type key: the days the
 * administration set, {@code null} while the factory default applies. Applied through {@link
 * FullSyncRhythmAdministration#change}, which writes and audits nothing for an unchanged value.
 */
@Component
class FullSyncRhythmSettingsSection implements SettingsSection {

  static final String OUT_OF_RANGE = "OUT_OF_RANGE";

  private final SourceConnectorRegistry connectors;
  private final FullSyncRhythmAdministration administration;

  FullSyncRhythmSettingsSection(
      SourceConnectorRegistry connectors, FullSyncRhythmAdministration administration) {
    this.connectors = connectors;
    this.administration = administration;
  }

  @Override
  public String key() {
    return "fullSyncRhythm";
  }

  @Override
  public String label() {
    return "Vollabgleich-Rhythmus";
  }

  @Override
  public Set<String> tables() {
    return Set.of("full_sync_rhythm_settings");
  }

  @Override
  public Map<String, String> keyLabels() {
    Map<String, String> labels = new LinkedHashMap<>();
    for (SourceConnectorDescriptor descriptor : withRhythm()) {
      labels.put(
          descriptor.type().key(), "Vollabgleich " + descriptor.displayName() + " alle … Tage");
    }
    return labels;
  }

  /**
   * A longer rhythm keeps what the source deleted findable longer, a shorter one sends more
   * requests to the source: like a retention period, every change is marked.
   */
  @Override
  public Set<String> securityRelevantKeys() {
    return withRhythm().stream()
        .map(descriptor -> descriptor.type().key())
        .collect(Collectors.toUnmodifiableSet());
  }

  @Override
  public Map<String, Object> export(CurrentUser actor) {
    Map<String, Object> values = new LinkedHashMap<>();
    for (FullSyncRhythmAdministration.Rhythm rhythm : administration.list(actor)) {
      values.put(rhythm.type().key(), rhythm.overridden() ? rhythm.intervalDays() : null);
    }
    return values;
  }

  @Override
  public void apply(CurrentUser actor, SettingsValues values) {
    for (SourceConnectorDescriptor descriptor : withRhythm()) {
      String key = descriptor.type().key();
      Long days = values.optionalLong(key);
      if (days != null && (days < FullSyncRhythm.MIN_DAYS || days > FullSyncRhythm.MAX_DAYS)) {
        throw FieldValidationException.of(
            key,
            OUT_OF_RANGE,
            "Der Vollabgleich-Rhythmus von "
                + descriptor.displayName()
                + " muss zwischen "
                + FullSyncRhythm.MIN_DAYS
                + " und "
                + FullSyncRhythm.MAX_DAYS
                + " Tagen liegen");
      }
      administration.change(actor, key, days == null ? null : days.intValue());
    }
  }

  private List<SourceConnectorDescriptor> withRhythm() {
    return connectors.descriptors().stream()
        .filter(descriptor -> descriptor.fullSyncInterval() != null)
        .toList();
  }
}
