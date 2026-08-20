package io.opaa.chat;

import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * The dedicated thread pool for #557's asynchronous chat title generation ({@link
 * ChatTitleGenerationService#generateTitleAsync}) - separate from {@code indexingTaskExecutor}
 * ({@code io.opaa.indexing.IndexingConfiguration}) so a burst of indexing work can never starve
 * title generation, and vice versa. {@code @EnableAsync} itself is already declared on {@code
 * IndexingConfiguration} - one declaration anywhere in the application context enables
 * {@code @Async} processing for all of it, so it is deliberately not repeated here.
 *
 * <p>Deliberately tiny: title generation is a single short LLM call per chat's first turn, never a
 * queue-worthy background job.
 */
@Configuration
public class ChatConfiguration {

  @Bean
  TaskExecutor chatTitleTaskExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(2);
    executor.setMaxPoolSize(4);
    executor.setQueueCapacity(50);
    executor.setThreadNamePrefix("chat-title-");
    executor.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardPolicy());
    executor.initialize();
    return executor;
  }
}
