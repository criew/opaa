import { describe, expect, it } from 'vitest'
import {
  EMPTY_RSS_FEED_VALUES,
  MAX_RSS_SELECTOR_LENGTH,
  rssFeedPayload,
  rssFeedSelectorIssue,
  rssFeedSettingsOf,
  storedRssFeedValues,
} from './rssFeedSource'

describe('rssFeedSource', () => {
  it('reads the stored selector and profile, empty for the factory defaults', () => {
    expect(
      storedRssFeedValues({
        sourceUrl: 'https://presse.example/feed.rss',
        sourceSettings: { mainContentSelector: '#inhalt', attachmentProfile: 'GSB' },
      }),
    ).toEqual({
      ...EMPTY_RSS_FEED_VALUES,
      sourceUrl: 'https://presse.example/feed.rss',
      mainContentSelector: '#inhalt',
      attachmentProfile: 'GSB',
    })
    expect(storedRssFeedValues({ sourceUrl: 'https://presse.example/feed.rss' })).toEqual({
      ...EMPTY_RSS_FEED_VALUES,
      sourceUrl: 'https://presse.example/feed.rss',
    })
  })

  it('ignores a stored profile it does not know', () => {
    expect(
      storedRssFeedValues({ sourceSettings: { attachmentProfile: 'WORDPRESS' } })
        .attachmentProfile,
    ).toBe('')
  })

  it('sends only what is set, so emptying both returns to the factory defaults', () => {
    expect(
      rssFeedSettingsOf({
        ...EMPTY_RSS_FEED_VALUES,
        mainContentSelector: '  .meldung  ',
        attachmentProfile: '',
      }),
    ).toEqual({ mainContentSelector: '.meldung' })
    expect(rssFeedSettingsOf(EMPTY_RSS_FEED_VALUES)).toEqual({})
  })

  it('always carries the settings object with the address', () => {
    expect(
      rssFeedPayload({
        ...EMPTY_RSS_FEED_VALUES,
        sourceUrl: ' https://presse.example/feed.rss ',
        attachmentProfile: 'GSB',
      }),
    ).toEqual({
      sourceUrl: 'https://presse.example/feed.rss',
      sourceCredentials: undefined,
      sourceSettings: { attachmentProfile: 'GSB' },
    })
  })

  it('refuses a selector beyond the length limit and one with a regular expression', () => {
    expect(rssFeedSelectorIssue('#inhalt')).toBeNull()
    expect(rssFeedSelectorIssue('a'.repeat(MAX_RSS_SELECTOR_LENGTH))).toBeNull()
    expect(rssFeedSelectorIssue('a'.repeat(MAX_RSS_SELECTOR_LENGTH + 1))).toContain(
      `${MAX_RSS_SELECTOR_LENGTH} Zeichen`,
    )
    expect(rssFeedSelectorIssue('p:matches(a+)')).toContain('reguläre Ausdrücke')
    expect(rssFeedSelectorIssue('a[href~=pdf]')).toContain('reguläre Ausdrücke')
  })
})
