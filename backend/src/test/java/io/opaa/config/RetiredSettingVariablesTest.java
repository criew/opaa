package io.opaa.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * A variable that once preset a setting the administration now holds is no longer read; a start
 * that still finds it set names it together with the place where the value lives now.
 */
class RetiredSettingVariablesTest {

  @Test
  void aStartWithoutRetiredVariablesNamesNone() {
    assertThat(RetiredSettingVariables.foundIn(new MockEnvironment())).isEmpty();
  }

  @Test
  void everyRetiredVariableStillSetIsNamedWithItsNewPlace() {
    MockEnvironment environment =
        new MockEnvironment()
            .withProperty("OPAA_INDEXING_SHAREPOINT_FULL_SYNC_INTERVAL", "3d")
            .withProperty("OPAA_LIBRARY_PRIVATE_STORAGE_QUOTA_BYTES", "0")
            .withProperty("OPAA_LIBRARY_QUOTA_BYTES", "1024");

    assertThat(RetiredSettingVariables.foundIn(environment))
        .containsExactly(
            "OPAA_INDEXING_SHAREPOINT_FULL_SYNC_INTERVAL is no longer read; set the full-sync"
                + " rhythm of SharePoint and of OneDrive, which it also covered, under"
                + " Administration > Anbindungen > Einstellungen",
            "OPAA_LIBRARY_PRIVATE_STORAGE_QUOTA_BYTES is no longer read; set the storage quota of"
                + " private libraries under Administration > Anbindungen > Einstellungen");
  }

  @Test
  void theStartValuesTheInstallationReplacedAreNamedWithItsPlace() {
    MockEnvironment environment =
        new MockEnvironment()
            .withProperty("OPAA_INITIAL_ADMIN_EMAIL", "it@stadt.example")
            .withProperty("OPAA_OIDC_ISSUER_URI", "https://login.stadt.example/realms/opaa")
            .withProperty("OPAA_OPENAI_CHAT_MODEL", "qwen");

    assertThat(RetiredSettingVariables.foundIn(environment))
        .containsExactly(
            "OPAA_INITIAL_ADMIN_EMAIL is no longer read; set the first account in the installation,"
                + " then in the user administration",
            "OPAA_OIDC_ISSUER_URI is no longer read; set the identity provider in the installation,"
                + " then in the Anbieterverwaltung; an internal one needs its hosts in"
                + " OPAA_OIDC_TARGET_VALIDATION_ALLOWLIST",
            "OPAA_OPENAI_CHAT_MODEL is no longer read; set the chat model in the installation, then"
                + " in the model administration");
  }

  @Test
  void theRetiredVariablesAreKnown() {
    assertThat(RetiredSettingVariables.RETIRED)
        .containsOnlyKeys(
            "OPAA_INDEXING_CONFLUENCE_FULL_SYNC_INTERVAL",
            "OPAA_INDEXING_GOOGLE_DRIVE_FULL_SYNC_INTERVAL",
            "OPAA_INDEXING_SHAREPOINT_FULL_SYNC_INTERVAL",
            "OPAA_INDEXING_DROPBOX_FULL_SYNC_INTERVAL",
            "OPAA_LIBRARY_PRIVATE_STORAGE_QUOTA_BYTES",
            "OPAA_INITIAL_ADMIN_EMAIL",
            "OPAA_INITIAL_ADMIN_PASSWORD",
            "OPAA_OIDC_ISSUER_URI",
            "OPAA_OIDC_CLIENT_ID",
            "OPAA_OIDC_JWK_SET_URI",
            "OPAA_OIDC_AUTHORITY",
            "OPAA_OIDC_BOOTSTRAP",
            "OPAA_OPENAI_CHAT_BASE_URL",
            "OPAA_OPENAI_CHAT_API_KEY",
            "OPAA_OPENAI_CHAT_MODEL",
            "OPAA_OPENAI_CHAT_TEMPERATURE",
            "OPAA_OPENAI_CHAT_MAX_TOKENS",
            "OPAA_AI_CHAT_PROVIDER",
            "OPAA_OLLAMA_CHAT_MODEL",
            "OPAA_OLLAMA_BASE_URL",
            "OPAA_OLLAMA_EMBEDDING_MODEL",
            "OPAA_AI_EMBEDDING_PROVIDER");
  }
}
