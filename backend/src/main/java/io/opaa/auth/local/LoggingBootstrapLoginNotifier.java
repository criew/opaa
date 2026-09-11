package io.opaa.auth.local;

import io.opaa.auth.User;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The {@link BootstrapLoginNotifier} until the mail subsystem exists (#1536/#1537): one WARN line
 * with the account id - never the address - so an operator reading the application log sees the
 * emergency access was used.
 */
@Component
public class LoggingBootstrapLoginNotifier implements BootstrapLoginNotifier {

  private static final Logger log = LoggerFactory.getLogger(LoggingBootstrapLoginNotifier.class);

  @Override
  public void bootstrapAccountSignedIn(User bootstrapAccount, Instant at) {
    log.warn(
        "The bootstrap system administrator account {} signed in at {} (audited as"
            + " LOCAL_BOOTSTRAP_ACCOUNT_LOGIN); the notification mail to the other system"
            + " administrators follows with #1537",
        bootstrapAccount.getId(),
        at);
  }
}
