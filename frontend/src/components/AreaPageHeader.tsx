import type { ReactNode } from 'react'
import Box from '@mui/material/Box'
import Typography from '@mui/material/Typography'
import PageHeading from './a11y/PageHeading'

interface AreaPageHeaderProps {
  /** The page's single <h1>. */
  title: string
  /** Overrides the document title when it should be more specific than the heading. */
  documentTitle?: string
  /**
   * The sentence under the title: what this page governs and for whom. Every page of an area
   * carries one - a title alone leaves the reader to guess the scope.
   */
  description: ReactNode
  /** A short fact next to the title, typically a count („7 Konten"). */
  meta?: ReactNode
  /** The page's one primary action, pushed to the trailing edge of the title row. */
  action?: ReactNode
}

/**
 * Der Kopf einer Bereichsseite (#1604): Titel, daneben eine kurze Angabe und die eine primäre
 * Handlung, darunter der Satz, der den Geltungsbereich nennt.
 *
 * Ein eigener Baustein, weil dieselbe Anordnung vorher auf sieben Seiten viermal verschieden
 * geschrieben war — mit `gutterBottom` oder ohne, mit Flex-Zeile oder ohne, die Beschreibung mal
 * als `GlobalScopeNote` (12,5 px) und mal als `body2` (14 px). Sichtbar wurde das als Titel, die
 * beim Wechsel zwischen zwei Menüpunkten um ein paar Pixel springen.
 *
 * Die Abstände stehen hier und nur hier: die Titelzeile ohne eigenen Innenabstand, darunter ein
 * fester Abstand zur Beschreibung und von ihr zum Inhalt. Der negative obere Abstand, mit dem
 * `GlobalScopeNote` sich früher unter ein `gutterBottom` zog, entfällt damit.
 */
export default function AreaPageHeader({
  title,
  documentTitle,
  description,
  meta,
  action,
}: AreaPageHeaderProps) {
  return (
    <Box sx={{ mb: 3 }}>
      {/* Eine Flex-Box, kein Stack: MUIs Stack setzt seinen Abstand als `margin-left` auf die
          Geschwister und überschreibt damit das `ml: auto`, mit dem die Handlung an den rechten
          Rand rückt. Die Mindesthöhe ist die einer Schaltfläche - ohne sie stünde die
          Beschreibung auf Seiten mit Handlung vier Pixel tiefer als auf den übrigen, genau der
          Sprung, den dieser Baustein beseitigen soll. */}
      <Box
        sx={{
          display: 'flex',
          alignItems: 'baseline',
          gap: 2,
          rowGap: 1,
          flexWrap: 'wrap',
          minHeight: 36.5,
        }}
      >
        <PageHeading title={title} documentTitle={documentTitle} />
        {meta && (
          <Typography component="span" sx={{ fontSize: 13, color: 'text.secondary' }}>
            {meta}
          </Typography>
        )}
        {action && <Box sx={{ ml: 'auto', flex: 'none', alignSelf: 'center' }}>{action}</Box>}
      </Box>
      <Typography sx={{ fontSize: 12.5, color: 'text.secondary', mt: 0.5, maxWidth: '80ch' }}>
        {description}
      </Typography>
    </Box>
  )
}
