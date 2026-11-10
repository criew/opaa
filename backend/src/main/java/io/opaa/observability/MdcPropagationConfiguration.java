package io.opaa.observability;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Gives every {@link ThreadPoolTaskExecutor} and {@link SimpleAsyncTaskExecutor} bean the {@link
 * MdcTaskDecorator}, so {@code @Async} work and every other hand-off to such a pool logs with the
 * request id and indexing run of its submitter. Both executors read the decorator per task, so
 * setting it after the bean initialised itself takes effect; no bean sets a decorator of its own.
 */
@Configuration(proxyBeanMethods = false)
public class MdcPropagationConfiguration {

  @Bean
  static BeanPostProcessor mdcPropagatingExecutors() {
    MdcTaskDecorator decorator = new MdcTaskDecorator();
    return new BeanPostProcessor() {
      @Override
      public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (bean instanceof ThreadPoolTaskExecutor pool) {
          pool.setTaskDecorator(decorator);
        } else if (bean instanceof SimpleAsyncTaskExecutor simple) {
          simple.setTaskDecorator(decorator);
        }
        return bean;
      }
    };
  }
}
