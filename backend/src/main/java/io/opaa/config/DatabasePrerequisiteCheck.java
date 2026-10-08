package io.opaa.config;

import javax.sql.DataSource;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Refuses to start, before the schema migration, when the database lacks a prerequisite named in
 * {@link DatabasePrerequisites}. Liquibase is made to depend on the check, so it never runs into
 * the missing prerequisite itself.
 */
@Configuration(proxyBeanMethods = false)
public class DatabasePrerequisiteCheck {

  static final String CHECK_BEAN = "databasePrerequisiteVerification";
  static final String LIQUIBASE_BEAN = "liquibase";

  @Bean(CHECK_BEAN)
  InitializingBean databasePrerequisiteVerification(ObjectProvider<DataSource> dataSource) {
    return () ->
        dataSource.ifAvailable(
            source ->
                DatabasePrerequisites.check(DatabasePrerequisites.read(new JdbcTemplate(source)))
                    .ifPresent(
                        missing -> {
                          throw missing;
                        }));
  }

  @Bean
  static BeanFactoryPostProcessor liquibaseDependsOnDatabasePrerequisiteCheck() {
    return beanFactory -> addDependency(beanFactory);
  }

  private static void addDependency(ConfigurableListableBeanFactory beanFactory) {
    if (!beanFactory.containsBeanDefinition(LIQUIBASE_BEAN)
        || !beanFactory.containsBeanDefinition(CHECK_BEAN)) {
      return;
    }
    BeanDefinition liquibase = beanFactory.getBeanDefinition(LIQUIBASE_BEAN);
    String[] dependsOn = liquibase.getDependsOn();
    String[] extended = new String[dependsOn == null ? 1 : dependsOn.length + 1];
    if (dependsOn != null) {
      System.arraycopy(dependsOn, 0, extended, 0, dependsOn.length);
    }
    extended[extended.length - 1] = CHECK_BEAN;
    liquibase.setDependsOn(extended);
  }
}
