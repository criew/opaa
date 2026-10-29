import {
  rssFeedSelectorIssue,
  rssFeedSettingsOf,
  type RssFeedSourceValues,
} from '../../../utils/rssFeedSource'
import type { SourceSetupSpec } from '../setupTypes'
import { urlSetup } from './genericSetups'
import { joinSummary } from './common'
import { RssFeedScopeFields } from './RssFeedSetupFields'

const SCOPE_FIELDS = ['mainContentSelector', 'attachmentProfile']

/**
 * The feed: „Verbindung“ as every web source, „Umfang“ how its detail pages are read. The
 * connection test probes with the settings, so it checks them on the first entry's detail page.
 */
export const rssFeedSetup: SourceSetupSpec<RssFeedSourceValues> = {
  sections: [
    ...urlSetup.sections,
    {
      id: 'scope',
      summary: (values) =>
        joinSummary(
          values.mainContentSelector.trim() &&
            `Inhaltsselektor ${values.mainContentSelector.trim()}`,
          values.attachmentProfile && `Anlagenprofil ${values.attachmentProfile}`,
        ) ?? 'Hauptinhalt und Anlagen nach Werkseinstellung',
      Fields: RssFeedScopeFields,
    },
  ],
  issues: (values, context) => {
    const issues = urlSetup.issues(values, context)
    const selector = rssFeedSelectorIssue(values.mainContentSelector)
    return selector ? [...issues, { field: 'mainContentSelector', message: selector }] : issues
  },
  labels: {
    ...urlSetup.labels,
    mainContentSelector: 'Inhaltsselektor',
    attachmentProfile: 'Anlagenprofil',
  },
  rowOf: (field) => (SCOPE_FIELDS.includes(field) ? 'scope' : 'connection'),
  probe: (values, fields, context) =>
    fields.probeRequest({
      sourceType: context.sourceType,
      sourceUrl: values.sourceUrl.trim(),
      sourceCredentials: values.sourceCredentials.trim() || undefined,
      sourceSettings: rssFeedSettingsOf(values),
    }),
  signsIn: false,
}
