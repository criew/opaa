package io.opaa.mail;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Persistence for the {@link MailTemplate} overrides (#1536). */
@Repository
public interface MailTemplateRepository extends JpaRepository<MailTemplate, UUID> {

  Optional<MailTemplate> findByTemplateKeyAndLocale(String templateKey, String locale);

  /**
   * Every override for one locale in one query - the overview lists all twelve keys, and twelve
   * single lookups would be twelve round trips for a page nobody opens often enough to warrant
   * them.
   */
  List<MailTemplate> findByLocale(String locale);
}
