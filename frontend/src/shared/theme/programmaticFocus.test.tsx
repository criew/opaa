import { act, render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { createAppTheme } from './theme'

/*
 * Guidelines 4.4: a heading that only a script focuses (page title on arrival, wizard step after
 * „Weiter“) keeps the focus for the screen reader but shows no ring; every control keeps its ring.
 * jsdom neither resolves `outline` nor matches `:focus-visible` on a heading, so the test reads
 * the global rules of the theme and matches their selectors against focused elements.
 */
const baseline = createAppTheme('light').components?.MuiCssBaseline?.styleOverrides as Record<
  string,
  unknown
>
const RING = '*:focus-visible'
const NO_RING = Object.keys(baseline).find(
  (selector) =>
    selector.includes(':focus') && (baseline[selector] as { outline?: string }).outline === 'none',
)

function renderPage() {
  render(
    <>
      <h1 tabIndex={-1}>Benutzer</h1>
      <h2 tabIndex={-1}>Mitglieder</h2>
      <button type="button">Weiter</button>
      <div tabIndex={0} role="button">
        Zeile
      </div>
    </>,
  )
}

describe('focus on a heading the script moves to', () => {
  it('the theme draws the ring for every control and lifts it for such headings', () => {
    expect(baseline[RING]).toMatchObject({ outline: expect.stringContaining('solid') })
    expect(NO_RING).toBeDefined()
  })

  it.each(['Benutzer', 'Mitglieder'])('„%s“ takes the focus without a ring', (name) => {
    renderPage()
    const heading = screen.getByRole('heading', { name })
    act(() => heading.focus())

    expect(heading).toHaveFocus()
    expect(heading.matches(NO_RING!)).toBe(true)
  })

  it.each(['Weiter', 'Zeile'])('the control „%s“ keeps its ring', (name) => {
    renderPage()
    const control = screen.getByRole('button', { name })
    act(() => control.focus())

    expect(control).toHaveFocus()
    expect(control.matches(NO_RING!)).toBe(false)
  })
})
