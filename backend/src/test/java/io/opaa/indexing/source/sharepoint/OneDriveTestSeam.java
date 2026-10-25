package io.opaa.indexing.source.sharepoint;

import io.opaa.indexing.spi.Endpoint;
import io.opaa.indexing.spi.SourceSyncStateRepository;
import io.opaa.msgraph.FakeGraphServer;
import io.opaa.security.TargetAddressValidator;
import io.opaa.sourceaccess.Sleeper;
import io.opaa.test.FakeAuthorizationServer;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.config.BeanPostProcessor;

/**
 * Points the Graph and Entra addresses of the Spring contexts at the shared fakes (ADR-0058,
 * Entscheidung 6): {@code oneDriveSourceConnector} talks to {@link FakeGraphServer#shared()} and
 * signs in at {@link FakeAuthorizationServer#shared()}, and every Graph connection trusts the
 * fake's pre-signed host. The SharePoint connector keeps its fixed address. Replaces the beans by a
 * post-processor, so the context and its cache key stay those of the suite.
 */
public final class OneDriveTestSeam {

  /** The tenant the token endpoint carries in its path, {@code /token/<tenant>}. */
  public static final String TOKEN_PATH = "/token/";

  private OneDriveTestSeam() {}

  /** The post-processor {@code OpaaTestBeans} registers. */
  public static BeanPostProcessor redirectingToTheFakes() {
    return new Redirect();
  }

  private static final class Redirect implements BeanPostProcessor, BeanFactoryAware {

    private BeanFactory beans;

    @Override
    public void setBeanFactory(BeanFactory beanFactory) {
      this.beans = beanFactory;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
      if (bean instanceof GraphConnections graphs) {
        FakeGraphServer graph = FakeGraphServer.shared();
        return new GraphConnections(
            graphs.properties(),
            beans.getBean(TargetAddressValidator.class),
            Sleeper.threadSleep(),
            graph::httpClient);
      }
      if (bean instanceof OneDriveSourceConnector) {
        FakeAuthorizationServer provider = FakeAuthorizationServer.shared();
        return new OneDriveSourceConnector(
            FakeGraphServer.shared().origin(),
            provider.issuer() + "/" + Endpoint.WithTenant.PLACEHOLDER + "/authorize",
            provider.issuer() + TOKEN_PATH + Endpoint.WithTenant.PLACEHOLDER,
            beans.getBean(GraphConnections.class),
            beans.getBean(SourceSyncStateRepository.class));
      }
      return bean;
    }
  }
}
