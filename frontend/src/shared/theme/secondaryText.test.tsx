import { render, screen } from '@testing-library/react'
import { ThemeProvider } from '@mui/material/styles'
import Typography from '@mui/material/Typography'
import { describe, expect, it } from 'vitest'
import { createAppTheme } from './theme'

/**
 * The application's source text, read in by Vite (`?raw`) — no file system access, so that the
 * test gets by without Node types.
 */
const QUELLEN = import.meta.glob('../../**/*.tsx', {
  query: '?raw',
  import: 'default',
  eager: true,
}) as Record<string, string>

const PRODUKTIVCODE = Object.keys(QUELLEN).filter((pfad) => !pfad.includes('.test.'))

/** A palette path as the value of the `color` prop, e.g. `color="text.secondary"`. */
const PALETTENPFAD_ALS_PROP = /color="[a-z]+\.[a-z]+"/

function farbeVon(element: HTMLElement): string {
  return getComputedStyle(element).color
}

/** `#3B4958` → `rgb(59, 73, 88)`: jsdom always returns the computed colour in this form. */
function alsRgb(hex: string): string {
  const wert = hex.replace('#', '')
  const zahl = Number.parseInt(wert, 16)
  return `rgb(${(zahl >> 16) & 255}, ${(zahl >> 8) & 255}, ${zahl & 255})`
}

describe('Sekundärtext', () => {
  /**
   * In MUI 9.4 `Typography` resolves only the **short forms** of the `color` prop
   * (`primary`, `error`). A **palette path** like `text.secondary` silently drops out as an
   * invalid CSS colour, and the text renders in the inherited default colour — the typographic
   * hierarchy is flat at that spot without it being noticed while writing.
   *
   * The test checks both: that the notation returns nowhere and that the correct one
   * actually carries it.
   */
  it.each(PRODUKTIVCODE.filter((pfad) => PALETTENPFAD_ALS_PROP.test(QUELLEN[pfad])))(
    '%s sets palette colours through sx, not through the color prop',
    (pfad) => {
      // This line is reached only if a file carries the notation - then the
      // test name names it and this expectation fails.
      expect(QUELLEN[pfad].match(PALETTENPFAD_ALS_PROP)?.[0]).toBeUndefined()
    },
  )

  it('renders no palette path as a color prop anywhere in the source', () => {
    const betroffen = PRODUKTIVCODE.filter((pfad) => PALETTENPFAD_ALS_PROP.test(QUELLEN[pfad]))
    expect(betroffen).toEqual([])
  })

  it.each(['light', 'dark'] as const)(
    'gives secondary text its own colour in the %s scheme',
    (schema) => {
      render(
        <ThemeProvider theme={createAppTheme(schema)}>
          <Typography data-testid="sekundaer" sx={{ color: 'text.secondary' }}>
            sekundär
          </Typography>
          <Typography data-testid="primaer">primär</Typography>
        </ThemeProvider>,
      )

      const sekundaer = farbeVon(screen.getByTestId('sekundaer'))
      const primaer = farbeVon(screen.getByTestId('primaer'))
      expect(sekundaer).not.toBe('')
      // The point of the whole thing: secondary text must differ from running text.
      expect(sekundaer).not.toBe(primaer)
      expect(sekundaer).toBe(alsRgb(createAppTheme(schema).palette.text.secondary))
    },
  )
})
