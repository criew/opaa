package io.opaa.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.SimpleBeanDefinitionRegistry;
import org.springframework.context.annotation.AnnotationBeanNameGenerator;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.core.type.filter.TypeFilter;
import org.springframework.stereotype.Component;

/**
 * Every component under {@code io.opaa} has a bean name of its own, conditional ones included: two
 * classes of the same simple name in different packages get the same default name, and Spring
 * refuses to start once both conditions hold - a combination of switches no test context may switch
 * on together.
 */
class BeanNameUniquenessTest {

  @Test
  void noTwoComponentsShareABeanName() {
    TypeFilter component = new AnnotationTypeFilter(Component.class);
    // Conditions are not evaluated: a name clash between two conditional beans counts as well.
    ClassPathScanningCandidateComponentProvider scanner =
        new ClassPathScanningCandidateComponentProvider(false) {
          @Override
          protected boolean isCandidateComponent(MetadataReader reader) throws IOException {
            return component.match(reader, getMetadataReaderFactory());
          }
        };
    AnnotationBeanNameGenerator names = AnnotationBeanNameGenerator.INSTANCE;
    SimpleBeanDefinitionRegistry registry = new SimpleBeanDefinitionRegistry();
    Map<String, List<String>> classesByName = new TreeMap<>();
    for (BeanDefinition candidate : scanner.findCandidateComponents("io.opaa")) {
      classesByName
          .computeIfAbsent(names.generateBeanName(candidate, registry), name -> new ArrayList<>())
          .add(candidate.getBeanClassName());
    }
    classesByName.values().removeIf(classes -> classes.size() < 2);

    assertThat(classesByName)
        .as("bean names claimed by more than one component; give one an explicit name")
        .isEmpty();
  }
}
