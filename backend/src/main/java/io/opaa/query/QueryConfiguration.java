package io.opaa.query;

import io.opaa.query.answer.ConversationMemoryConfiguration;
import io.opaa.query.filter.MetadataFilterProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * The query package's configuration properties. The retrieval pipeline and the query metrics are
 * wired in {@code io.opaa.retrieval.config.RetrievalConfiguration}, the chat memory in {@link
 * ConversationMemoryConfiguration}.
 */
@Configuration
@EnableConfigurationProperties(MetadataFilterProperties.class)
public class QueryConfiguration {}
