package io.opaa.indexing.source;

import static io.opaa.api.types.ConnectionAuthMethod.NONE;
import static io.opaa.api.types.ConnectionAuthMethod.PERSONAL_SECRET;
import static io.opaa.api.types.ConnectionOwnership.BOTH;
import static io.opaa.api.types.ConnectionOwnership.LIBRARY;
import static io.opaa.api.types.ConnectionOwnership.PERSON;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.api.types.ConnectionAuthMethod;
import io.opaa.api.types.ConnectionProfileSupport;
import io.opaa.api.types.PersonalSecretForm;
import io.opaa.common.ValidationException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The profile declaration of a connector - its own consistency rules - and the default reading of a
 * profile's connector defaults against it.
 */
class ProfileDeclarationTest {

  private static final ServiceAccountKeyAuth KEY =
      new ServiceAccountKeyAuth(URI.create("https://oauth.example.org/token"), "scope");

  @Test
  void aSignInAdmitsTheOwnershipsItNames() {
    SignIn libraryOnly = SignIn.personalSecret(PersonalSecretForm.TOKEN, LIBRARY);
    SignIn both = SignIn.of(NONE, LIBRARY, PERSON);

    assertThat(libraryOnly.admits(LIBRARY)).isTrue();
    assertThat(libraryOnly.admits(PERSON)).isFalse();
    assertThat(libraryOnly.admits(BOTH)).isFalse();
    assertThat(both.admits(BOTH)).isTrue();
    assertThatThrownBy(() -> SignIn.of(NONE)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> SignIn.of(NONE, BOTH)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aServiceAccountKeyCarriesItsEndpointAndBelongsToALibrary() {
    assertThat(SignIn.serviceAccountKey(KEY).owners()).containsExactly(LIBRARY);
    assertThatThrownBy(() -> SignIn.of(ConnectionAuthMethod.SERVICE_ACCOUNT_KEY, LIBRARY))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new SignIn(PERSONAL_SECRET, Set.of(LIBRARY), KEY))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(SignIn.serviceAccountKey(KEY).secretForm()).isNull();
    assertThatThrownBy(
            () ->
                new SignIn(ConnectionAuthMethod.SERVICE_ACCOUNT_KEY, Set.of(LIBRARY, PERSON), KEY))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aPersonalSecretAndOnlyThatNamesTheFormOfItsSecret() {
    assertThat(
            SignIn.personalSecret(PersonalSecretForm.USERNAME_AND_PASSWORD, LIBRARY).secretForm())
        .isEqualTo(PersonalSecretForm.USERNAME_AND_PASSWORD);
    assertThatThrownBy(() -> SignIn.of(PERSONAL_SECRET, LIBRARY))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new SignIn(NONE, Set.of(LIBRARY), new PersonalSecretAuth(PersonalSecretForm.TOKEN)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aConnectorAdmittingProfilesOffersASignInAndOneWithoutHasNoDefaults() {
    assertThatThrownBy(() -> ProfileDeclaration.of(ConnectionProfileSupport.OPTIONAL))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> ProfileDeclaration.forbidden().withDefaults(DefaultKey.text("region", "Region")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new ProfileDeclaration(
                    ConnectionProfileSupport.FORBIDDEN,
                    List.of(SignIn.of(NONE, LIBRARY)),
                    ServerAddressRule.web(),
                    ProfileDefaults.none()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                ProfileDeclaration.of(
                    ConnectionProfileSupport.OPTIONAL,
                    SignIn.of(NONE, LIBRARY),
                    SignIn.of(NONE, PERSON)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aConnectorWithoutProfilesNamesOnlyTheKeyTheCoreSignsWith() {
    ProfileDeclaration declaration = ProfileDeclaration.forbiddenWithServiceAccountKey(KEY);

    assertThat(declaration.admitsProfiles()).isFalse();
    assertThat(declaration.serviceAccountKey()).isEqualTo(KEY);
    assertThat(ProfileDeclaration.forbidden().serviceAccountKey()).isNull();
  }

  @Test
  void anAddressRuleNamesSchemesOrAFixedAddress() {
    assertThat(ServerAddressRule.web().schemes()).containsExactly("https", "http");
    assertThat(ServerAddressRule.schemes("SMB", "smb").schemes()).containsExactly("smb");
    assertThat(ServerAddressRule.fixed("https://api.example.org").isFixed()).isTrue();
    assertThatThrownBy(() -> new ServerAddressRule(List.of(), null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ServerAddressRule(List.of("https"), "https://api.example.org"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void onlyAChoiceListsValuesAndAKeyIsDeclaredOnce() {
    assertThatThrownBy(() -> DefaultKey.choice("edition", "Edition"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new DefaultKey(
                    "region", "Region", io.opaa.api.types.ProfileDefaultKind.TEXT, List.of("a")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> ProfileDefaults.of(DefaultKey.text("a", "A"), DefaultKey.bool("a", "B")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void readingKeepsDeclaredKeysOfTheirKindAndDropsEmptyOnes() {
    ProfileDefaults defaults = defaults();
    Map<String, Object> requested = new LinkedHashMap<>();
    requested.put("region", " eu-central-1 ");
    requested.put("pathStyle", true);
    requested.put("edition", "DC");
    requested.put("prefix", " ");

    assertThat(defaults.read(ConnectorData.of(requested)).asMap())
        .containsExactly(
            Map.entry("region", "eu-central-1"),
            Map.entry("pathStyle", true),
            Map.entry("edition", "DC"));
    assertThat(defaults.read(ConnectorData.of(Map.of("prefix", "")))).isNull();
    assertThat(defaults.read(null)).isNull();
  }

  @Test
  void readingRefusesAnUndeclaredKeyAndAWrongKind() {
    ProfileDefaults defaults = defaults();

    assertThatThrownBy(() -> defaults.read(ConnectorData.of(Map.of("spaces", 1))))
        .isInstanceOf(ValidationException.class)
        .hasMessage("connectorSettings: das Feld spaces kann ein Zugang nicht vorgeben");
    assertThatThrownBy(() -> defaults.read(ConnectorData.of(Map.of("region", 1))))
        .isInstanceOf(ValidationException.class)
        .hasMessage("connectorSettings.region muss ein Text sein");
    assertThatThrownBy(() -> defaults.read(ConnectorData.of(Map.of("pathStyle", "ja"))))
        .isInstanceOf(ValidationException.class)
        .hasMessage("connectorSettings.pathStyle muss ja oder nein sein");
    assertThatThrownBy(() -> defaults.read(ConnectorData.of(Map.of("edition", "SERVER"))))
        .isInstanceOf(ValidationException.class)
        .hasMessage("connectorSettings.edition muss einer der Werte CLOUD, DC sein");
  }

  private static ProfileDefaults defaults() {
    return ProfileDefaults.of(
        DefaultKey.text("region", "Region"),
        DefaultKey.bool("pathStyle", "Pfad-Adressierung"),
        DefaultKey.choice("edition", "Edition", "CLOUD", "DC"),
        DefaultKey.text("prefix", "Präfix"));
  }
}
