import { render, screen } from '@testing-library/react'
import { ThemeProvider } from '@mui/material/styles'
import Typography from '@mui/material/Typography'
import { describe, expect, it } from 'vitest'
import { createAppTheme } from './theme'

/**
 * Der Quelltext der Anwendung, von Vite eingelesen (`?raw`) — kein Dateisystemzugriff, damit der
 * Test ohne Node-Typen auskommt.
 */
const QUELLEN = import.meta.glob('../**/*.tsx', {
  query: '?raw',
  import: 'default',
  eager: true,
}) as Record<string, string>

const PRODUKTIVCODE = Object.keys(QUELLEN).filter((pfad) => !pfad.includes('.test.'))

/** Ein Palettenpfad als Wert des `color`-Props, z. B. `color="text.secondary"`. */
const PALETTENPFAD_ALS_PROP = /color="[a-z]+\.[a-z]+"/

function farbeVon(element: HTMLElement): string {
  return getComputedStyle(element).color
}

/** `#3B4958` → `rgb(59, 73, 88)`: jsdom liefert die berechnete Farbe immer in dieser Form. */
function alsRgb(hex: string): string {
  const wert = hex.replace('#', '')
  const zahl = Number.parseInt(wert, 16)
  return `rgb(${(zahl >> 16) & 255}, ${(zahl >> 8) & 255}, ${zahl & 255})`
}

describe('Sekundärtext', () => {
  /**
   * #1617: `Typography` löst in MUI 9.4 nur noch die **Kurzformen** des `color`-Props auf
   * (`primary`, `error`). Ein **Palettenpfad** wie `text.secondary` fällt als ungültige CSS-Farbe
   * still weg, und der Text rendert in der geerbten Vorgabefarbe — die typografische Hierarchie
   * ist an der Stelle flach, ohne dass es beim Schreiben auffällt.
   *
   * Der Test prüft beides: dass die Schreibweise nirgends zurückkehrt und dass die richtige sie
   * tatsächlich trägt.
   */
  it.each(PRODUKTIVCODE.filter((pfad) => PALETTENPFAD_ALS_PROP.test(QUELLEN[pfad])))(
    '%s sets palette colours through sx, not through the color prop',
    (pfad) => {
      // Diese Zeile wird nur erreicht, wenn eine Datei die Schreibweise trägt - dann nennt der
      // Testname sie und diese Erwartung schlägt fehl.
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
      // Der Punkt der ganzen Sache: Sekundärtext muss sich vom Fließtext unterscheiden.
      expect(sekundaer).not.toBe(primaer)
      expect(sekundaer).toBe(alsRgb(createAppTheme(schema).palette.text.secondary))
    },
  )
})
