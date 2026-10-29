package io.opaa.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opaa.audit.AuditEventRecorder;
import io.opaa.common.ValidationException;
import io.opaa.security.SettingsEncryptor;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;

/** A stored access key stays with the endpoint it was entered for. */
class LlmModelServiceTest {

  private static final UUID ORGANIZATION = UUID.randomUUID();
  private static final UUID ACTOR = UUID.randomUUID();

  private final LlmModelRepository repository = mock(LlmModelRepository.class);
  private final SettingsEncryptor encryptor = mock(SettingsEncryptor.class);
  private final LlmModelService service =
      new LlmModelService(
          repository,
          encryptor,
          mock(AuditEventRecorder.class),
          mock(ApplicationEventPublisher.class),
          ModelAddressPolicy.disabled(),
          mock(PlatformTransactionManager.class));

  // regression guard for #3338: a new address without a new key must not inherit the stored one
  @Test
  void aChangedAddressWithoutANewKeyIsRefusedWhileAKeyIsStored() {
    LlmModel model = stored("https://anbieter.example/v1", "cipher-alt");

    assertThatThrownBy(() -> update(model, "https://fremd.example/v1", null))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("Basis-Adresse hat sich geändert");

    assertThat(model.getBaseUrl()).isEqualTo("https://anbieter.example/v1");
    assertThat(model.getApiKeyCiphertext()).isEqualTo("cipher-alt");
    verify(repository, never()).save(any());
  }

  @Test
  void anotherPathOnTheSameHostIsAnotherEndpoint() {
    LlmModel model = stored("https://anbieter.example/v1", "cipher-alt");

    assertThatThrownBy(() -> update(model, "https://anbieter.example/anderer/v1", null))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void theSameEndpointKeepsTheStoredKey() {
    LlmModel model = stored("https://anbieter.example/v1", "cipher-alt");

    LlmModel updated = update(model, "https://ANBIETER.example:443/v1/", null);

    assertThat(updated.getApiKeyCiphertext()).isEqualTo("cipher-alt");
  }

  @Test
  void aChangedAddressTakesANewKeyOrNone() {
    LlmModel withNewKey = stored("https://anbieter.example/v1", "cipher-alt");
    when(encryptor.encrypt("sk-neu")).thenReturn("cipher-neu");
    assertThat(update(withNewKey, "https://fremd.example/v1", "sk-neu").getApiKeyCiphertext())
        .isEqualTo("cipher-neu");

    LlmModel cleared = stored("https://anbieter.example/v1", "cipher-alt");
    when(encryptor.encrypt("")).thenReturn(null);
    assertThat(update(cleared, "https://fremd.example/v1", "").getApiKeyCiphertext()).isNull();
  }

  @Test
  void aChangedAddressWithoutAnyStoredKeyNeedsNone() {
    LlmModel model = stored("https://anbieter.example/v1", null);

    LlmModel updated = update(model, "https://fremd.example/v1", null);

    assertThat(updated.getBaseUrl()).isEqualTo("https://fremd.example/v1");
    assertThat(updated.getApiKeyCiphertext()).isNull();
  }

  private LlmModel stored(String baseUrl, String ciphertext) {
    LlmModel model =
        new LlmModel("Anbieter", baseUrl, "gpt-4o", new BigDecimal("0.70"), 2000, ciphertext);
    when(repository.findById(model.getId())).thenReturn(Optional.of(model));
    return model;
  }

  private LlmModel update(LlmModel model, String baseUrl, String apiKey) {
    return service.updateModel(
        ORGANIZATION,
        ACTOR,
        model.getId(),
        model.getDisplayName(),
        baseUrl,
        model.getModelIdentifier(),
        model.getTemperature(),
        model.getMaxTokens(),
        apiKey);
  }
}
