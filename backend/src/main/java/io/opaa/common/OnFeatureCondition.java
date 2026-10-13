package io.opaa.common;

import java.util.Map;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * The condition behind {@link ConditionalOnFeature}: binds {@code opaa.features.<key>.enabled} with
 * the same {@link Binder} rules as {@link FeatureProperties} and defaults to on, so the condition
 * and {@link FeatureFlags} cannot disagree about a flag.
 */
class OnFeatureCondition implements Condition {

  @Override
  public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
    Map<String, Object> attributes =
        metadata.getAnnotationAttributes(ConditionalOnFeature.class.getName());
    if (attributes == null) {
      return true;
    }
    Feature feature = (Feature) attributes.get("value");
    return Binder.get(context.getEnvironment())
        .bind(feature.enabledProperty(), Boolean.class)
        .orElse(true);
  }
}
