package io.opaa.succession;

import io.opaa.api.types.SuccessionKind;
import io.opaa.organization.Organization;
import io.opaa.organization.OrganizationRepository;
import io.opaa.permission.SuccessionFinding;
import io.opaa.permission.SuccessionFindingSource;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The named, regular detection run of ADR-0036, Entscheidung 6. It compares what the sources report
 * right now with the open cases and writes the two timestamps that cannot be derived: when a state
 * was first seen, and when it ended.
 *
 * <p><b>The derivation stays the truth.</b> A run that never happened changes no answer - the list
 * derives its entries on every read; it would only lose the age. And because a derived state has no
 * writing trigger, without the run "Alter" would mean "seit dem letzten Hinsehen".
 *
 * <p>One transaction per organization, opened by a {@link TransactionTemplate} rather than by
 * {@code @Transactional}: the loop calls the pass on the same bean, where an annotation would never
 * reach a proxy. An organization whose pass fails is logged and left untouched - the following ones
 * still run. Single process by ADR-0021, so no lock beyond that.
 */
@Service
public class SuccessionDetectionService {

  private static final Logger log = LoggerFactory.getLogger(SuccessionDetectionService.class);

  private final List<SuccessionFindingSource> sources;
  private final SuccessionCaseRepository cases;
  private final OrganizationRepository organizations;
  private final Clock clock;
  private final TransactionTemplate transactionTemplate;

  SuccessionDetectionService(
      List<SuccessionFindingSource> sources,
      SuccessionCaseRepository cases,
      OrganizationRepository organizations,
      Clock clock,
      PlatformTransactionManager transactionManager) {
    this.sources = sources;
    this.cases = cases;
    this.organizations = organizations;
    this.clock = clock;
    this.transactionTemplate = new TransactionTemplate(transactionManager);
  }

  /** One pass over every organization; returns how many cases it opened and closed. */
  public SuccessionDetectionRun runOnce() {
    int opened = 0;
    int closed = 0;
    for (Organization organization : organizations.findAll()) {
      try {
        SuccessionDetectionRun run = runFor(organization.getId());
        opened += run.opened();
        closed += run.closed();
      } catch (RuntimeException e) {
        // One organization's pass is one transaction and one failure: it is rolled back whole and
        // redone by the next run, while the organizations behind it still get their pass.
        log.warn("Succession detection failed for organization {}", organization.getId(), e);
      }
    }
    if (opened > 0 || closed > 0) {
      log.info("Succession detection: {} case(s) opened, {} closed", opened, closed);
    }
    return new SuccessionDetectionRun(opened, closed);
  }

  /** One organization's pass, in its own transaction - see this class's Javadoc. */
  public SuccessionDetectionRun runFor(UUID organizationId) {
    return transactionTemplate.execute(status -> detect(organizationId));
  }

  private SuccessionDetectionRun detect(UUID organizationId) {
    Instant now = clock.instant();
    Set<CaseKey> found = new HashSet<>();
    int opened = 0;

    for (SuccessionFindingSource source : sources) {
      for (SuccessionFinding finding : source.findingsOf(organizationId)) {
        CaseKey key = new CaseKey(source.kind(), finding.objectType().name(), finding.objectId());
        if (!found.add(key)) {
          continue;
        }
        SuccessionCase open =
            cases
                .findByKindAndObjectTypeAndObjectIdAndClosedAtIsNull(
                    source.kind(), finding.objectType(), finding.objectId())
                .orElse(null);
        if (open == null) {
          cases.save(
              new SuccessionCase(
                  organizationId,
                  source.kind(),
                  finding.objectType(),
                  finding.assetType(),
                  finding.objectId(),
                  now));
          opened++;
        } else {
          open.seenAt(now);
          cases.save(open);
        }
      }
    }

    int closed = 0;
    for (SuccessionCase open : cases.findByOrganizationIdAndClosedAtIsNull(organizationId)) {
      CaseKey key = new CaseKey(open.getKind(), open.getObjectType().name(), open.getObjectId());
      if (!found.contains(key)) {
        // Nobody is named: the state simply stopped holding - a group has an active member again,
        // an object was deleted. Where a person's action ended it, that action closes the case
        // itself and names them (SuccessionService#closeFor).
        open.close(now, null);
        cases.save(open);
        closed++;
      }
    }
    return new SuccessionDetectionRun(opened, closed);
  }

  private record CaseKey(SuccessionKind kind, String objectType, UUID objectId) {}
}
