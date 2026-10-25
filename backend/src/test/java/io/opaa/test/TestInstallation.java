package io.opaa.test;

import io.opaa.installation.InstallationService;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.test.context.TestConfiguration;

/**
 * Installs the shared test database before the start-up of the installation decides (ADR-0060), so
 * no class of an {@code oidc} family meets the installation mode by the order the suite runs in: a
 * pending installation is completed the way the {@code dev} mode completes its own, with the chat
 * model at {@code spring.ai.openai.base-url}. Classes that test the installation itself put the
 * database back into a pending installation for their own methods.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestInstallation implements SmartInitializingSingleton {

  private final InstallationService installation;

  TestInstallation(InstallationService installation) {
    this.installation = installation;
  }

  @Override
  public void afterSingletonsInstantiated() {
    installation.completeAtStartupIfDue(true);
  }
}
