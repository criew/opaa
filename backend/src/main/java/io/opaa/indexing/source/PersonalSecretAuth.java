package io.opaa.indexing.source;

import io.opaa.api.types.PersonalSecretForm;
import java.util.Objects;

/** The sign-in by personal secret, with the form in which the connector takes the secret. */
public record PersonalSecretAuth(PersonalSecretForm form) implements SignInDetails {

  public PersonalSecretAuth {
    Objects.requireNonNull(form, "form");
  }
}
