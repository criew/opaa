package io.opaa.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaFieldAccess;
import io.opaa.security.TargetAddressValidator;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * A {@code PER_CONNECTION} check pins the name only for the 60 seconds of its binding, so its
 * caller must connect right after it and check again before every later connection (ADR-0063,
 * Entscheidung 3). Every main class naming the scope stands here with how it keeps that; a new
 * caller or one that no longer names it fails, so the list stays exact.
 */
class PerConnectionCheckCallersTest {

  private static final String CHECKED_BEFORE_SAVING =
      "refuses a target before the settings are stored and connects to nothing; every connection"
          + " of a run is checked again by its own client";

  static final Map<String, String> CALLERS =
      Map.of(
          "io.opaa.sourceaccess.RedirectFollowingFetcher",
          "checks each hop and each attempt right before HttpClient#send; the client follows no"
              + " redirect itself",
          "io.opaa.s3.S3RequestGuard$TargetPolicy",
          "the policy S3RequestGuard runs before every transmission attempt of the AWS SDK, before"
              + " its pool connects",
          "io.opaa.knowledge.UploadS3TargetPolicy",
          "is the target check S3RequestGuard runs before every attempt to the originals store",
          "io.opaa.indexing.source.smb.SmbShareClient$ValidatingSocketFactory",
          "checks every socket right before it connects",
          "io.opaa.indexing.source.s3.S3ClientFactory",
          CHECKED_BEFORE_SAVING,
          "io.opaa.indexing.source.smb.SmbSourceConnector",
          CHECKED_BEFORE_SAVING,
          "io.opaa.indexing.source.nextcloud.NextcloudSourceConnector",
          CHECKED_BEFORE_SAVING);

  @Test
  void everyCallerOfAPerConnectionCheckIsListed() {
    Set<String> callers = new TreeSet<>();
    for (JavaClass type : MainClasses.get()) {
      if (type.getName().startsWith(TargetAddressValidator.class.getName())) {
        continue;
      }
      for (JavaFieldAccess access : type.getFieldAccessesFromSelf()) {
        if (access.getTarget().getOwner().isEquivalentTo(TargetAddressValidator.Scope.class)
            && access.getTarget().getName().equals("PER_CONNECTION")) {
          callers.add(type.getName());
        }
      }
    }

    assertThat(callers)
        .as(
            "a PER_CONNECTION check binds the name for 60 seconds only: list a new caller in"
                + " CALLERS with how it connects right after the check (ADR-0063)")
        .isEqualTo(new TreeSet<>(CALLERS.keySet()));
  }
}
