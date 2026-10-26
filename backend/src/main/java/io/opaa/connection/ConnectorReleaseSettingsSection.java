package io.opaa.connection;

import io.opaa.api.types.ConnectionProfileSupport;
import io.opaa.api.types.OwnAddressStock;
import io.opaa.auth.CurrentUser;
import io.opaa.common.ConflictException;
import io.opaa.common.FieldValidationException;
import io.opaa.connection.profile.ConnectorLockService;
import io.opaa.connection.profile.ConnectorLockService.TypeState;
import io.opaa.connection.profile.ProfileRequirementService;
import io.opaa.settings.SettingsSection;
import io.opaa.settings.SettingsValues;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * The connector release per source type, matched by the type key: {@code <TYPE>.locked} for every
 * type that reaches a source, and {@code <TYPE>.profileRequired} ("Nur über Zugänge": {@code null}
 * for off, otherwise what happens to libraries with their own address) where the connector declares
 * profiles optional. Applied through {@link ConnectorLockService} and {@link
 * ProfileRequirementService}, which write and audit nothing for an unchanged value; profiles are no
 * part of the document, so a requirement nothing in this installation could take over is refused.
 */
@Component
class ConnectorReleaseSettingsSection implements SettingsSection {

  private static final String LOCKED = ".locked";
  private static final String PROFILE_REQUIRED = ".profileRequired";

  private final ConnectorLockService locks;
  private final ProfileRequirementService requirements;

  ConnectorReleaseSettingsSection(
      ConnectorLockService locks, ProfileRequirementService requirements) {
    this.locks = locks;
    this.requirements = requirements;
  }

  @Override
  public String key() {
    return "connectorRelease";
  }

  @Override
  public String label() {
    return "Freigabe der Konnektoren";
  }

  @Override
  public Set<String> tables() {
    return Set.of("connector_type_policies");
  }

  @Override
  public Map<String, String> keyLabels() {
    Map<String, String> labels = new LinkedHashMap<>();
    for (TypeState state : locks.typeStates()) {
      String type = state.type().key();
      labels.put(type + LOCKED, state.displayName() + " gesperrt");
      if (switchable(state)) {
        labels.put(
            type + PROFILE_REQUIRED,
            state.displayName()
                + " nur über Zugänge (eigene Adressen: RUNS laufen weiter, LOCKED gesperrt)");
      }
    }
    return labels;
  }

  /** A lock stops the runs of a type, a requirement can lock libraries: every key is marked. */
  @Override
  public Set<String> securityRelevantKeys() {
    return keyLabels().keySet();
  }

  @Override
  public Map<String, Object> export(CurrentUser actor) {
    Map<String, Object> values = new LinkedHashMap<>();
    for (TypeState state : locks.typeStates()) {
      String type = state.type().key();
      values.put(type + LOCKED, state.locked());
      if (switchable(state)) {
        OwnAddressStock stock = state.ownAddressStock();
        values.put(type + PROFILE_REQUIRED, stock == null ? null : stock.name());
      }
    }
    return values;
  }

  @Override
  public void apply(CurrentUser actor, SettingsValues values) {
    List<TypeState> states = locks.typeStates();
    for (TypeState state : states) {
      locks.lockType(actor, state.type(), values.requireBoolean(state.type().key() + LOCKED));
    }
    for (TypeState state : states) {
      if (switchable(state)) {
        applyRequirement(actor, state, values);
      }
    }
  }

  /**
   * Switches "Nur über Zugänge" through the administration; its refusal for want of a profile that
   * could take over names the key, so the whole document is refused under it.
   */
  private void applyRequirement(CurrentUser actor, TypeState state, SettingsValues values) {
    String key = state.type().key() + PROFILE_REQUIRED;
    OwnAddressStock stock = values.optionalEnum(key, OwnAddressStock.class);
    try {
      requirements.require(actor, state.type(), stock != null, stock);
    } catch (ConflictException refused) {
      if (!ProfileRequirementService.NEEDS_PROFILE.equals(refused.getCode())) {
        throw refused;
      }
      throw FieldValidationException.of(
          key,
          ProfileRequirementService.NEEDS_PROFILE,
          refused.getMessage()
              + " Zugänge überträgt die Datei nicht; legen Sie ihn in dieser Installation an.");
    }
  }

  private static boolean switchable(TypeState state) {
    return state.profileSupport() == ConnectionProfileSupport.OPTIONAL;
  }
}
