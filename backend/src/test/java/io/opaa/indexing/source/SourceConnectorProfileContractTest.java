package io.opaa.indexing.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.api.types.ConnectionOwnership;
import io.opaa.api.types.ConnectionProfileSupport;
import io.opaa.common.ValidationException;
import io.opaa.test.OpaaIntegrationTest;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The profile declaration of every registered connector against ADR-0038, read from the beans the
 * application actually wires: profiles forbidden for uploads and local sources, required exactly
 * with an app registration on the profile, defaults only on settings keys, and a default reading
 * that takes every declared key of its kind and refuses any other.
 */
@OpaaIntegrationTest
class SourceConnectorProfileContractTest {

  @Autowired private List<SourceConnector> connectors;

  @Test
  void everyRegisteredConnectorDeclaresItsProfilesAsTheAdrSays() {
    assertThat(connectors).hasSizeGreaterThan(9);
    SoftAssertions softly = new SoftAssertions();
    for (SourceConnector connector : connectors) {
      SourceConnectorDescriptor descriptor = connector.descriptor();
      ProfileDeclaration declaration = descriptor.profileDeclaration();
      String type = descriptor.type().key();
      if (descriptor.uploads() || !descriptor.remote()) {
        softly
            .assertThat(declaration.support())
            .as(type)
            .isEqualTo(ConnectionProfileSupport.FORBIDDEN);
      }
      boolean registrationOnProfile =
          declaration.signIns().stream().anyMatch(signIn -> signIn.method().requiresProfile());
      softly
          .assertThat(declaration.support() == ConnectionProfileSupport.REQUIRED)
          .as(type)
          .isEqualTo(registrationOnProfile);
      softly
          .assertThat(connector.settingsKeys())
          .as(type)
          .containsAll(declaration.defaults().keys().stream().map(DefaultKey::key).toList());
      for (SignIn signIn : declaration.signIns()) {
        softly
            .assertThat(signIn.owners())
            .as(type)
            .doesNotContain(ConnectionOwnership.BOTH)
            .isNotEmpty();
      }
      if (declaration.serviceAccountKey() != null) {
        softly.assertThat(declaration.admitsProfiles()).as(type).isFalse();
      }
    }
    softly.assertAll();
  }

  /**
   * {@code settingsKeys} and {@code readSettings} are independent in the SPI; every key the former
   * names must pass the latter's field check, and a key outside it must not.
   */
  @Test
  void everySettingsKeyIsAFieldTheConnectorReadsAndNoOtherIs() {
    SoftAssertions softly = new SoftAssertions();
    for (SourceConnector connector : connectors) {
      String type = connector.descriptor().type().key();
      for (String key : connector.settingsKeys()) {
        softly
            .assertThat(fieldRefusal(connector, key))
            .as(type + "." + key + " is a settings key, so readSettings must know it")
            .isNull();
      }
      softly
          .assertThat(fieldRefusal(connector, "keinEinstellungsFeld"))
          .as(type + " must refuse a key outside settingsKeys")
          .isNotNull();
    }
    softly.assertAll();
  }

  /** The refusal of {@code key} as an unknown field, {@code null} when readSettings knows it. */
  private static String fieldRefusal(SourceConnector connector, String key) {
    try {
      connector.readSettings(ConnectorData.of(Map.of(key, "Wert")));
      return null;
    } catch (ValidationException e) {
      return e.getMessage().endsWith("das Feld " + key + " ist nicht vorgesehen")
          ? e.getMessage()
          : null;
    }
  }

  @Test
  void everyRegisteredConnectorReadsItsDeclaredDefaultsAndRefusesAnyOther() {
    for (SourceConnector connector : connectors) {
      String type = connector.descriptor().type().key();
      for (DefaultKey key : connector.descriptor().profileDeclaration().defaults().keys()) {
        Object sample =
            switch (key.kind()) {
              case TEXT -> "Wert";
              case BOOLEAN -> true;
              case CHOICE -> key.choices().getFirst();
            };
        ProfileDefaults defaults = connector.descriptor().profileDeclaration().defaults();
        ConnectorData read = defaults.read(ConnectorData.of(Map.of(key.key(), sample)));
        assertThat(connector.readProfileDefaults(read))
            .as(type + "." + key.key())
            .isEqualTo(ConnectorData.of(Map.of(key.key(), sample)));
      }
      assertThatThrownBy(
              () ->
                  connector
                      .descriptor()
                      .profileDeclaration()
                      .defaults()
                      .read(ConnectorData.of(Map.of("keinVorgabeFeld", "x"))))
          .as(type)
          .isInstanceOf(ValidationException.class);
    }
  }
}
