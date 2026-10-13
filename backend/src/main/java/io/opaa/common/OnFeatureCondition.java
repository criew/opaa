package io.opaa.common;

import java.util.Map;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * The condition behind {@link ConditionalOnFeature}. It reads the flag through {@link
 * FeatureResolution}, the same rule {@link FeatureFlags} uses, so the two cannot disagree.
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
    return FeatureResolution.isEnabled(context.getEnvironment(), feature);
  }
}
