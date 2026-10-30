package io.opaa.mail;

import java.util.UUID;

/**
 * A system administrator changed the SMTP settings or a mail template - the channels every mail of
 * the installation runs through. Published only for an actual change by a person, inside its
 * transaction; the revision is told of it, since the administration controls these channels.
 *
 * @param organizationId the organization of the acting administrator
 * @param what what changed, in German, for the notice
 */
public record MailConfigurationChangedEvent(UUID organizationId, String what) {}
