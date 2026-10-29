import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { renderWithProviders } from '../../../../../test/test-utils'
import { EMPTY_RSS_FEED_VALUES, type RssFeedSourceValues } from '../../../utils/rssFeedSource'
import { connectionFields } from '../../sources/sourceConnection'
import type { SourceFormContext } from '../../sources/types'
import { RssFeedScopeFields } from './RssFeedSetupFields'
import { rssFeedSetup } from './rssFeedSetup'

const context: SourceFormContext = {
  mode: 'create',
  sourceType: 'RSS_FEED',
  idPrefix: 'test',
  credentialsStored: false,
}

function renderScope(values: RssFeedSourceValues, onChange = vi.fn()) {
  renderWithProviders(
    <RssFeedScopeFields
      values={values}
      onChange={onChange}
      context={context}
      fields={connectionFields(context)}
      issueAt={() => null}
      fieldId={(field) => `test-${field}`}
    />,
  )
  return onChange
}

describe('RssFeedScopeFields', () => {
  it('offers the content selector and the attachment profile, both optional', async () => {
    const onChange = renderScope(EMPTY_RSS_FEED_VALUES)

    await userEvent.type(screen.getByLabelText('Inhaltsselektor (optional)'), '#')
    expect(onChange).toHaveBeenLastCalledWith({ mainContentSelector: '#' })

    await userEvent.click(screen.getByLabelText('Anlagenprofil'))
    await userEvent.click(await screen.findByRole('option', { name: /Government Site Builder/ }))
    expect(onChange).toHaveBeenLastCalledWith({ attachmentProfile: 'GSB' })
  })
})

describe('rssFeedSetup', () => {
  it('summarises the factory defaults and own settings in the row „Umfang“', () => {
    const scope = rssFeedSetup.sections.find((section) => section.id === 'scope')!

    expect(scope.summary(EMPTY_RSS_FEED_VALUES, context)).toBe(
      'Hauptinhalt und Anlagen nach Werkseinstellung',
    )
    expect(
      scope.summary(
        { ...EMPTY_RSS_FEED_VALUES, mainContentSelector: '#inhalt', attachmentProfile: 'GSB' },
        context,
      ),
    ).toBe('Inhaltsselektor #inhalt · Anlagenprofil GSB')
  })

  it('names a selector issue at its field in the row „Umfang“', () => {
    const issues = rssFeedSetup.issues(
      {
        ...EMPTY_RSS_FEED_VALUES,
        sourceUrl: 'https://presse.example/feed.rss',
        mainContentSelector: 'p:matches(a+)',
      },
      context,
    )

    expect(issues).toEqual([
      expect.objectContaining({ field: 'mainContentSelector' }),
    ])
    expect(rssFeedSetup.rowOf('mainContentSelector')).toBe('scope')
    expect(rssFeedSetup.rowOf('sourceUrl')).toBe('connection')
  })

  it('probes with the settings, so the test checks the detail page', () => {
    const probeRequest = vi.fn((request) => request)
    const request = rssFeedSetup.probe!(
      {
        ...EMPTY_RSS_FEED_VALUES,
        sourceUrl: 'https://presse.example/feed.rss',
        mainContentSelector: '#inhalt',
      },
      { ...connectionFields(context), probeRequest },
      context,
    )

    expect(request).toEqual(
      expect.objectContaining({
        sourceType: 'RSS_FEED',
        sourceUrl: 'https://presse.example/feed.rss',
        sourceSettings: { mainContentSelector: '#inhalt' },
      }),
    )
  })
})
