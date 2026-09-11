package io.opaa.test;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.test.context.TestPropertySource;

/**
 * {@link OpaaIntegrationTest} with the two property sets that are themselves the subject under
 * test: an embedding base URL that differs from the chat one, and a pinned pgvector dimension with
 * schema initialisation switched off.
 *
 * <p><b>Why these cannot be canonical:</b> {@code ProviderConfigurationTest} asserts the
 * <em>default</em> embedding base URL, so the override below must not reach it; and the pgvector
 * guard's class drops and recreates {@code vector_store} with a deliberately wrong dimension, which
 * no other class may see in its database - with {@code initialize-schema=false} nothing puts the
 * table back.
 *
 * <p>The two property sets do not contradict each other, so both classes share one context rather
 * than one each.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@OpaaIntegrationTest
@TestPropertySource(
    properties = {
      "spring.ai.openai.embedding.base-url=http://model-server.invalid:8000/v1",
      "spring.ai.vectorstore.pgvector.dimensions=1536",
      "spring.ai.vectorstore.pgvector.initialize-schema=false"
    })
public @interface OpaaPropertyVariantIntegrationTest {}
