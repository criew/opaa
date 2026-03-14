package io.opaa.workspace;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "opaa.workspace")
public record WorkspaceProperties(DefaultWorkspace defaultWorkspace) {

  public WorkspaceProperties {
    if (defaultWorkspace == null) {
      defaultWorkspace = new DefaultWorkspace(null, null);
    }
  }

  public record DefaultWorkspace(String name, String description) {

    public DefaultWorkspace {
      if (name == null || name.isBlank()) {
        name = "Default";
      }
      if (description == null || description.isBlank()) {
        description = "Default shared workspace";
      }
    }
  }
}
