import {
  EMPTY_GENERIC_SOURCE_VALUES,
  genericSourcePayload,
  storedGenericSourceValues,
  type GenericSourceValues,
  type LibrarySourceConfigPayload,
} from './librarySourceConfig'

/** The attachment profiles of a feed; '' leaves the factory default. */
export const RSS_ATTACHMENT_PROFILES = ['GENERIC', 'GSB'] as const
export type RssAttachmentProfile = (typeof RSS_ATTACHMENT_PROFILES)[number]

/** The form values of an RSS_FEED library: the address plus its two optional settings. */
export interface RssFeedSourceValues extends GenericSourceValues {
  mainContentSelector: string
  attachmentProfile: RssAttachmentProfile | ''
}

export const EMPTY_RSS_FEED_VALUES: RssFeedSourceValues = {
  ...EMPTY_GENERIC_SOURCE_VALUES,
  mainContentSelector: '',
  attachmentProfile: '',
}

/** Mirrors RssFeedSettings.MAX_SELECTOR_LENGTH. */
export const MAX_RSS_SELECTOR_LENGTH = 200

function isProfile(value: unknown): value is RssAttachmentProfile {
  return RSS_ATTACHMENT_PROFILES.includes(value as RssAttachmentProfile)
}

export function storedRssFeedValues(library: {
  sourcePath?: string | null
  sourceUrl?: string | null
  sourceSettings?: Record<string, unknown> | null
}): RssFeedSourceValues {
  const selector = library.sourceSettings?.mainContentSelector
  const profile = library.sourceSettings?.attachmentProfile
  return {
    ...storedGenericSourceValues(library),
    mainContentSelector: typeof selector === 'string' ? selector : '',
    attachmentProfile: isProfile(profile) ? profile : '',
  }
}

/** Only what is set; an empty object returns the library to the factory defaults. */
export function rssFeedSettingsOf(values: RssFeedSourceValues): Record<string, unknown> {
  const settings: Record<string, unknown> = {}
  const selector = values.mainContentSelector.trim()
  if (selector) settings.mainContentSelector = selector
  if (values.attachmentProfile) settings.attachmentProfile = values.attachmentProfile
  return settings
}

/** The settings are sent with every save, so clearing them is a change too. */
export function rssFeedPayload(values: RssFeedSourceValues): LibrarySourceConfigPayload {
  return { ...genericSourcePayload('url', values), sourceSettings: rssFeedSettingsOf(values) }
}

/**
 * The fast check before sending; the syntax is the server's to judge, it answers an invalid
 * selector with a German message of its own.
 */
export function rssFeedSelectorIssue(selector: string): string | null {
  const trimmed = selector.trim()
  if (trimmed.length > MAX_RSS_SELECTOR_LENGTH) {
    return `Inhaltsselektor: höchstens ${MAX_RSS_SELECTOR_LENGTH} Zeichen`
  }
  const lower = trimmed.toLowerCase()
  if (lower.includes(':matches') || lower.includes('~=')) {
    return 'Inhaltsselektor: reguläre Ausdrücke (:matches…, [attr~=…]) sind nicht erlaubt'
  }
  return null
}
