package io.opaa.audit;

import io.opaa.api.types.AuditOutcome;
import io.opaa.common.AccessDeniedException;
import io.opaa.common.NotFoundException;
import io.opaa.common.ValidationException;

/**
 * How a failed protocol access is recorded in the trail. {@link AuditOutcome#DENIED} means the
 * attempt was turned away - missing role, missing reason, malformed or unanswerable request. A
 * query that broke after those checks passed was not turned away, and recording it as {@code
 * DENIED} would make a malfunction indistinguishable from an attempted overreach for whoever reads
 * the trail; that case is {@link AuditOutcome#FAILURE}.
 */
public final class AuditAccessOutcome {

  private AuditAccessOutcome() {}

  public static AuditOutcome of(RuntimeException thrown) {
    return thrown instanceof AccessDeniedException
            || thrown instanceof ValidationException
            || thrown instanceof NotFoundException
            || thrown instanceof IllegalArgumentException
        ? AuditOutcome.DENIED
        : AuditOutcome.FAILURE;
  }
}
