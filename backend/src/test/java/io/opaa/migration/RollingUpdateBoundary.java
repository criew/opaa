package io.opaa.migration;

import static java.util.Map.entry;

import java.util.Map;
import java.util.Set;

/**
 * The one version boundary without a rolling update so far (backend/docs/liquibase.md, "Rolling
 * Update"): every installation that updates from a state before the expand/contract rule - up to
 * and including {@code v0.1.0-rc.4} - stops all its instances first. The changeSets that existed at
 * that boundary are listed once and frozen; no later changeSet joins either list.
 */
final class RollingUpdateBoundary {

  /** The release that first enforces the rule; every update to it is one without rolling update. */
  static final String FIRST_RELEASE_WITH_THE_RULE = "v0.1.0";

  /**
   * Every changeSet of the master at the boundary. Exempt from {@code lock_timeout} at its start,
   * which an executed changeSet can no longer get without changing its checksum.
   */
  static final Set<String> CHANGESETS_BEFORE =
      Set.of(
          "assistant-2026-09-27-baseline",
          "assistant-2026-10-02-chat-import-key",
          "assistant-2026-10-02-chat-mark-unarchived-at",
          "connections-2026-10-03-connection-profiles",
          "connections-2026-10-03-connector-locks",
          "connections-2026-10-04-connection-log",
          "connections-2026-10-04-log-owner",
          "connections-2026-10-04-oauth-authorizations",
          "connections-2026-10-04-oauth-authorizations--profile-endpoints",
          "connections-2026-10-04-profile-owner-only-guard",
          "connections-2026-10-04-profile-requests",
          "connections-2026-10-04-profile-requirement",
          "connections-2026-10-04-profile-sign-in",
          "connections-2026-10-04-profile-transport",
          "connections-2026-10-04-token-store",
          "connections-2026-10-04-usage-and-person-states",
          "connections-2026-10-04-usage-and-person-states--person-states",
          "connections-2026-10-04-warned-expiry",
          "connections-2026-10-04-warned-expiry--profile-secret",
          "connections-2026-10-05-mcp-server-profiles",
          "connections-2026-10-05-source-consent",
          "connections-2026-10-09-authorization-response-issuer",
          "connections-2026-10-11-drop-profile-requests",
          "connections-2026-10-11-drop-profile-source-proxy",
          "connections-2026-10-12-drop-profile-source-insecure-ssl",
          "connectors-2026-09-27-baseline",
          "connectors-2026-10-11-rss-feed-state-headers-text",
          "external-2026-09-27-baseline",
          "foundation-2026-09-27-baseline",
          "foundation-2026-10-11-outbound-proxy",
          "identity-2026-09-27-baseline",
          "identity-2026-10-09-provider-removals",
          "identity-2026-10-11-drop-profile-request-notifications",
          "identity-2026-10-15-incident-scope-retention",
          "identity-2026-10-15-incident-scope-retention--case-counts",
          "knowledge-2026-09-27-baseline",
          "knowledge-2026-09-27-baseline--vector-store-document-id",
          "knowledge-2026-09-27-baseline--vector-store-library-id",
          "knowledge-2026-10-02-drop-library-listed-cap",
          "knowledge-2026-10-03-indexing-job-unreadable-scopes",
          "knowledge-2026-10-03-source-credentials-text",
          "knowledge-2026-10-03-source-sync-change-cursors",
          "knowledge-2026-10-03-source-sync-subtree-markers",
          "knowledge-2026-10-04-document-hierarchy-path-text",
          "knowledge-2026-10-04-indexing-job-failure-category",
          "knowledge-2026-10-04-library-erasure-marker",
          "knowledge-2026-10-04-private-library-external-access",
          "knowledge-2026-10-04-source-sync-presence",
          "knowledge-2026-10-04-source-sync-revisits",
          "knowledge-2026-10-04-source-sync-scan-progress",
          "knowledge-2026-10-05-private-storage-quota",
          "knowledge-2026-10-09-source-sync-settings-basis",
          "knowledge-2026-10-10-source-sync-quota-held",
          "knowledge-2026-10-11-document-change-marker-text",
          "knowledge-2026-10-11-drop-library-source-proxy",
          "knowledge-2026-10-11-llm-model-temperature-optional",
          "knowledge-2026-10-11-source-values-text",
          "knowledge-2026-10-11-source-values-text--revisits",
          "knowledge-2026-10-12-drop-library-source-insecure-ssl",
          "knowledge-2026-10-13-embedding-index",
          "rights-2026-09-27-baseline",
          "rights-2026-10-02-asset-favorites",
          "rights-2026-10-02-drop-asset-listed",
          "rights-2026-10-03-capability-scope",
          "rights-2026-10-03-drop-grant-member-count",
          "rights-2026-10-04-asset-owner-only",
          "rights-2026-10-08-drop-succession-reviews",
          "rights-2026-10-11-group-deactivation",
          "workspace-2026-09-27-baseline",
          "workspace-2026-10-02-space-chat-auto-cleanup",
          "workspace-2026-10-03-drop-membership-member-count",
          "workspace-2026-10-03-drop-space-visibility",
          "workspace-2026-10-11-space-model-instruction");

  /**
   * The changeSets before the boundary that break the release running beside them, with the
   * assessment: none is a contract in the sense of the rule, because no release in between had
   * already stopped depending on what they remove or restrict.
   */
  static final Map<String, String> BREAKING_BEFORE =
      Map.ofEntries(
          entry(
              "connections-2026-10-04-log-owner",
              "owner_kind NOT NULL without a default and the owner check: an older release writes a person's entry without owner_kind"),
          entry(
              "connections-2026-10-05-mcp-server-profiles",
              "source_type becomes optional for MCP server profiles: an older release reads a profile without a connector type"),
          entry(
              "connections-2026-10-05-source-consent",
              "the owner check is rewritten for pending grants; it holds for every row an older release writes"),
          entry(
              "connections-2026-10-11-drop-profile-requests",
              "drops connection_profile_requests, which an older release still reads and writes"),
          entry(
              "connections-2026-10-11-drop-profile-source-proxy",
              "drops connection_profiles.source_proxy, which an older release still maps"),
          entry(
              "connections-2026-10-12-drop-profile-source-insecure-ssl",
              "drops connection_profiles.source_insecure_ssl, which an older release still maps"),
          entry(
              "identity-2026-10-15-incident-scope-retention",
              "subject and requester become optional and the person checks are rewritten: an older release maps both as present and counts the annual report from the grants"),
          entry(
              "knowledge-2026-10-02-drop-library-listed-cap",
              "drops knowledge_libraries.listed_cap with its check, which an older release still maps"),
          entry(
              "knowledge-2026-10-11-drop-library-source-proxy",
              "drops knowledge_libraries.source_proxy with its check, which an older release still maps"),
          entry(
              "knowledge-2026-10-11-llm-model-temperature-optional",
              "temperature becomes optional without a default: an older release reads a model without a temperature"),
          entry(
              "knowledge-2026-10-12-drop-library-source-insecure-ssl",
              "drops knowledge_libraries.source_insecure_ssl, which an older release still maps"),
          entry(
              "rights-2026-10-02-drop-asset-listed",
              "drops assets.listed and asset_visibility_history.listed and withdraws the cause VISIBILITY_CHANGED an older release still writes"),
          entry(
              "rights-2026-10-03-capability-scope",
              "CREATE_CONNECTOR_LIBRARY requires a scope: an older release grants it without one"),
          entry(
              "rights-2026-10-03-drop-grant-member-count",
              "drops asset_grants.member_count_at_grant, which an older release still maps"),
          entry(
              "rights-2026-10-04-asset-owner-only",
              "the owner-only check names owner_type; it holds for every row an older release writes, since owner_only defaults to false"),
          entry(
              "rights-2026-10-08-drop-succession-reviews",
              "drops succession_reviews, which an older release still reads and writes"),
          entry(
              "rights-2026-10-11-group-deactivation",
              "the deactivation check names kind; it holds for every row an older release writes, but an older release ignores deactivated, so a deactivated group keeps acting there"),
          entry(
              "workspace-2026-10-03-drop-membership-member-count",
              "drops member_count_at_grant of space memberships and their history, which an older release still maps"),
          entry(
              "workspace-2026-10-03-drop-space-visibility",
              "drops spaces.visibility, which an older release still maps"));

  private RollingUpdateBoundary() {}
}
