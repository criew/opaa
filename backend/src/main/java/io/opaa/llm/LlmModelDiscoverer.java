package io.opaa.llm;

import io.opaa.security.SettingsEncryptor;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/** Lists the models an OpenAI-compatible endpoint offers. */
@Service
public class LlmModelDiscoverer {

  static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;

  public LlmModelDiscoverer(
      LlmModelRepository repository,
      SettingsEncryptor settingsEncryptor,
      ObjectMapper objectMapper) {}

  public ModelDiscovery discover(String baseUrl, String apiKey, UUID modelId) {
    throw new UnsupportedOperationException("not implemented yet");
  }
}
