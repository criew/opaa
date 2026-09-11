package io.opaa.test;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.task.SyncTaskExecutor;

/**
 * Runs the two asynchronous chat jobs ({@code ChatConfiguration#chatTitleTaskExecutor}, {@code
 * ChatConfiguration#chatNoteTaskExecutor}) on the calling thread for the classes of {@link
 * OpaaMockedChatModelIntegrationTest}.
 *
 * <p>#616: the real executors run their LLM call on a separate thread, racing a test's {@code
 * when(chatModel...)} re-stubbing against that call landing on the very same shared mock. Mockito's
 * stubbing API is not thread-safe against a concurrent invocation of the mock being stubbed.
 * Running the jobs inline means they have always finished before the triggering call returns -
 * which is also what lets a test assert on the Gesprächsnotiz right after a query (#1487) without
 * an {@code Awaitility} wait.
 *
 * <p>Replaces the instance behind each existing definition rather than declaring a second bean: a
 * rename of a production bean makes {@code getBeanDefinition} fail at startup, where a same-name
 * {@code @Bean} would silently become an additional bean and let the flake back in unnoticed.
 */
@TestConfiguration(proxyBeanMethods = false)
public class SynchronousChatTitleExecutorConfiguration {

  @Bean
  static BeanFactoryPostProcessor synchronousChatTaskExecutors() {
    return beanFactory -> {
      for (String beanName : new String[] {"chatTitleTaskExecutor", "chatNoteTaskExecutor"}) {
        ((AbstractBeanDefinition) beanFactory.getBeanDefinition(beanName))
            .setInstanceSupplier(SyncTaskExecutor::new);
      }
    };
  }
}
