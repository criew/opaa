import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { renderWithProviders } from '../../../test/test-utils'
import AssetDetailHeader, { type AssetDetailHeaderProps } from './AssetDetailHeader'

function renderHeader(overrides: Partial<AssetDetailHeaderProps> = {}) {
  const props: AssetDetailHeaderProps = {
    assetType: 'KNOWLEDGE_LIBRARY',
    assetId: 'library-1',
    name: 'Bauakten',
    isPublic: false,
    headline: {
      idPrefix: 'library-detail',
      nameLabel: 'Name der Bibliothek',
      editLabel: 'Name und Beschreibung bearbeiten',
      canEdit: false,
      onSave: vi.fn(async () => {}),
    },
    extent: '87 Dokumente',
    spaceCount: 2,
    spaceHref: '?tab=zuordnungen',
    responsible: { label: 'Bauamt', group: true },
    mayUseInSpace: false,
    status: <span>Abgeschlossen</span>,
    ...overrides,
  }
  return renderWithProviders(<AssetDetailHeader {...props} />, { withRouter: true })
}

/** Rough selector specificity, enough to rank Emotion's class selectors: ids, then classes. */
function specificity(selector: string): number {
  const ids = (selector.match(/#/g) ?? []).length
  const classes = (selector.match(/\.|\[|:(?!:|not\()/g) ?? []).length
  return ids * 100 + classes
}

/** The `display` the page's style rules give an element at a viewport width, as the cascade ranks them. */
function displayAt(element: Element, width: number): string | null {
  let display: string | null = null
  let rank = -1
  // duck-typed: the rules come from jsdom's own CSSOM classes, not the test's globals
  const visit = (rules: CSSRuleList) => {
    for (const rule of Array.from(rules) as Array<CSSMediaRule & CSSStyleRule>) {
      if (rule.media && rule.cssRules) {
        const min = /min-width:\s*(\d+(?:\.\d+)?)px/.exec(rule.media.mediaText)
        const max = /max-width:\s*(\d+(?:\.\d+)?)px/.exec(rule.media.mediaText)
        if ((!min || width >= Number(min[1])) && (!max || width <= Number(max[1])))
          visit(rule.cssRules)
      } else if (rule.selectorText && rule.style.display) {
        const matching = rule.selectorText
          .split(',')
          .filter((selector) => element.matches(selector))
          .map(specificity)
        if (matching.length > 0 && Math.max(...matching) >= rank) {
          rank = Math.max(...matching)
          display = rule.style.display
        }
      }
    }
  }
  for (const sheet of Array.from(document.styleSheets)) visit(sheet.cssRules)
  return display
}

/** jsdom applies no `@media (min-width)` rule; this resolves them for one width, ancestors included. */
function shownAt(element: Element, width: number): boolean {
  for (let node: Element | null = element; node; node = node.parentElement) {
    if (displayAt(node, width) === 'none') return false
  }
  return true
}

describe('AssetDetailHeader - Kennzahlen unter 600 px', () => {
  it('shows only the state and reveals the figures through the toggle', async () => {
    const user = userEvent.setup()
    renderHeader()

    expect(shownAt(screen.getByText('Abgeschlossen'), 599)).toBe(true)
    expect(shownAt(screen.getByText('87 Dokumente'), 599)).toBe(false)
    expect(shownAt(screen.getByText('in 2 Spaces'), 599)).toBe(false)
    expect(shownAt(screen.getByText('Bauamt'), 599)).toBe(false)

    const toggle = screen.getByRole('button', { name: 'Kennzahlen anzeigen' })
    expect(toggle).toHaveAttribute('aria-expanded', 'false')
    expect(toggle).toHaveAttribute('aria-controls', 'head-figures-library-1')
    expect(document.getElementById('head-figures-library-1')).toContainElement(
      screen.getByText('87 Dokumente'),
    )

    await user.click(toggle)

    expect(toggle).toHaveAttribute('aria-expanded', 'true')
    expect(toggle).toHaveAccessibleName('Kennzahlen ausblenden')
    expect(shownAt(screen.getByText('87 Dokumente'), 599)).toBe(true)
    expect(shownAt(screen.getByRole('link', { name: /in 2 Spaces/ }), 599)).toBe(true)
    expect(shownAt(screen.getByText('Bauamt'), 599)).toBe(true)
    expect(shownAt(screen.getByText('Abgeschlossen'), 599)).toBe(true)

    await user.click(toggle)

    expect(toggle).toHaveAttribute('aria-expanded', 'false')
    expect(shownAt(screen.getByText('87 Dokumente'), 599)).toBe(false)
  })

  it('shows the figures at every width in a head without state, without toggle', () => {
    renderHeader({ status: undefined, updatedAt: '2026-10-03T10:00:00Z' })

    for (const width of [599, 600]) {
      expect(shownAt(screen.getByText('87 Dokumente'), width)).toBe(true)
      expect(shownAt(screen.getByText('in 2 Spaces'), width)).toBe(true)
      expect(shownAt(screen.getByText('Bauamt'), width)).toBe(true)
      expect(shownAt(screen.getByText('Aktualisiert am 03.10.2026'), width)).toBe(true)
    }
    expect(screen.queryByRole('button', { name: /Kennzahlen/ })).not.toBeInTheDocument()
  })

  it('shows the figures from 600 px on beside the state, without toggle', () => {
    renderHeader()

    expect(shownAt(screen.getByText('87 Dokumente'), 600)).toBe(true)
    expect(shownAt(screen.getByText('in 2 Spaces'), 600)).toBe(true)
    expect(shownAt(screen.getByText('Bauamt'), 600)).toBe(true)
    expect(shownAt(screen.getByText('Abgeschlossen'), 600)).toBe(true)
    expect(shownAt(screen.getByRole('button', { name: 'Kennzahlen anzeigen' }), 600)).toBe(false)
    expect(shownAt(screen.getByRole('button', { name: 'Kennzahlen anzeigen' }), 599)).toBe(true)
  })
})
