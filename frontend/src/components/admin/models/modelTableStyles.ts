import type { SxProps, Theme } from '@mui/material/styles'
import { fontFamily } from '../../../theme/tokens'

/**
 * Die Tabellensprache des Modellbereichs (#1623): Spaltenköpfe als gesperrte Versalien in der
 * Maschinenschrift, Zellen oben ausgerichtet und beschnitten statt umbrechend.
 *
 * An einer Stelle, damit die beiden Tabellen des Bereichs nicht auseinanderlaufen — wer eine
 * Zeilenhöhe ändert, ändert sie für beide.
 */
export const MODEL_TABLE_SX: SxProps<Theme> = {
  tableLayout: 'fixed',
  '& th': { fontFamily: fontFamily.mono, fontSize: 10, letterSpacing: '0.08em' },
  '& td': { fontSize: 13, py: 1.25, verticalAlign: 'top', overflow: 'hidden' },
}

/** Maschinenwerte - Kennungen, Adressen, Zahlen - stehen in der Maschinenschrift. */
export const MACHINE_VALUE_SX: SxProps<Theme> = {
  fontFamily: fontFamily.mono,
  fontSize: 12,
  overflowWrap: 'anywhere',
}
