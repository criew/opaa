package io.opaa.test;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.task.SyncTaskExecutor;

/**
 * Runs {@code ChatConfiguration#chatTitleTaskExecutor} on the calling thread for the classes of
 * {@link OpaaMockedChatModelIntegrationTest}.
 *
 * <p>#616: the real executor runs the chat-title LLM call on a separate thread, racing a test's
 * {@code when(chatModel...)} re-stubbing against that call landing on the very same shared mock.
 * Mockito's stubbing API is not thread-safe against a concurrent invocation of the mock being
 * stubbed. Running the title job inline means it has always finished before the triggering call
 * returns.
 *
 * <p>Replaces the instance behind the existing definition rather than declaring a second bean: a
 * rename of the production bean makes {@code getBeanDefinition} fail at startup, where a same-name
 * {@code @Bean} would silently become an additional bean and let the flake back in unnoticed.
 */
@TestConfiguration(proxyBeanMethods = false)
public class SynchronousChatTitleExecutorConfiguration {

  @Bean
  static BeanFactoryPostProcessor synchronousChatTitleTaskExecutor() {
    return beanFactory ->
        ((AbstractBeanDefinition) beanFactory.getBeanDefinition("chatTitleTaskExecutor"))
            .setInstanceSupplier(SyncTaskExecutor::new);
  }
}
