package io.opaa;

import io.opaa.auth.AuthProperties;
import io.opaa.indexing.IndexingProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({IndexingProperties.class, AuthProperties.class})
public class OpaaApplication {

  public static void main(String[] args) {
    SpringApplication.run(OpaaApplication.class, args);
  }
}
