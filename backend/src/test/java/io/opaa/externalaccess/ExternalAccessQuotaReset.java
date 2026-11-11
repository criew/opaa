package io.opaa.externalaccess;

import org.springframework.context.ApplicationContext;

/**
 * Forgets the windows of the context's {@link ExternalAccessQuota}: the window per person outlives
 * the tokens a test class issues, and the development accounts are shared by every class.
 */
public final class ExternalAccessQuotaReset {

  private ExternalAccessQuotaReset() {}

  public static void reset(ApplicationContext context) {
    context.getBeanProvider(ExternalAccessQuota.class).ifAvailable(ExternalAccessQuota::reset);
  }
}
