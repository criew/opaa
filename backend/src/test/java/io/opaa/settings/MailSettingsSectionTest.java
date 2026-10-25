package io.opaa.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import io.opaa.api.types.MailEncryption;
import io.opaa.api.types.SystemRole;
import io.opaa.auth.CurrentUser;
import io.opaa.mail.MailSettingsService;
import io.opaa.mail.MailSettingsUpdate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** The mail section never sets the password: {@code null} keeps the one this installation has. */
class MailSettingsSectionTest {

  @Test
  void anImportLeavesThePasswordOfThisInstallationAsItIs() {
    MailSettingsService service = mock(MailSettingsService.class);
    CurrentUser admin =
        CurrentUser.of(UUID.randomUUID(), UUID.randomUUID(), SystemRole.SYSTEM_ADMIN, "A", "a@x");
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("enabled", true);
    values.put("host", "smtp.intern.example");
    values.put("port", 587);
    values.put("username", "kennung");
    values.put("encryption", "STARTTLS");
    values.put("fromAddress", "opaa@intern.example");
    values.put("fromName", "OPAA");

    new MailSettingsSection(service).apply(admin, SettingsValues.of("mail", values));

    ArgumentCaptor<MailSettingsUpdate> update = ArgumentCaptor.forClass(MailSettingsUpdate.class);
    verify(service).updateSettings(eq(admin.organizationId()), eq(admin.id()), update.capture());
    assertThat(update.getValue().password()).isNull();
    assertThat(update.getValue().port()).isEqualTo(587);
    assertThat(update.getValue().encryption()).isEqualTo(MailEncryption.STARTTLS);
  }
}
