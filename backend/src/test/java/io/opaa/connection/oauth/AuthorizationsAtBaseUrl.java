package io.opaa.connection.oauth;

import io.opaa.common.PublicBaseUrl;
import io.opaa.common.PublicBaseUrlProperties;
import io.opaa.connection.account.ConnectedAccountService;
import io.opaa.connection.consent.SourceConsentService;
import io.opaa.connection.profile.ConnectionProfileRepository;
import io.opaa.connection.profile.EffectiveSourceSettings;
import io.opaa.connection.profile.ProfileRegistrations;
import io.opaa.indexing.registry.SourceConnectorRegistry;
import io.opaa.security.CredentialsEncryptor;
import io.opaa.security.TargetAddressValidator;
import java.time.Clock;
import org.springframework.context.ApplicationContext;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The context's authorization service under a public base URL, which the suite's signatures leave
 * unset: a test of another package starts a consent with it, everything else is the context's.
 */
public final class AuthorizationsAtBaseUrl {

  private AuthorizationsAtBaseUrl() {}

  public static ConnectionAuthorizationService service(
      ApplicationContext context, String publicBaseUrl) {
    return new ConnectionAuthorizationService(
        context.getBean(ConnectionAuthorizationRepository.class),
        context.getBean(ConnectionProfileRepository.class),
        context.getBean(ProfileRegistrations.class),
        context.getBean(ConnectedAccountService.class),
        context.getBean(SourceConsentService.class),
        context.getBean(EffectiveSourceSettings.class),
        context.getBeanProvider(SourceConnectorRegistry.class),
        new PublicBaseUrl(new PublicBaseUrlProperties(publicBaseUrl)),
        context.getBean(CredentialsEncryptor.class),
        context.getBean(TargetAddressValidator.class),
        context.getBean(PlatformTransactionManager.class),
        Clock.systemUTC());
  }
}
