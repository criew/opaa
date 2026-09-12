import { useId, type ReactNode } from 'react'
import Box from '@mui/material/Box'
import Typography from '@mui/material/Typography'
import SectionHead from './SectionHead'

interface PageSectionProps {
  /** The section's head - monospace, spaced, on a hairline. Names the section for assistive tech. */
  title: string
  /** One sentence under the head, where the section needs an explanation. */
  description?: ReactNode
  /** The section's own action, on the baseline of the head. */
  action?: ReactNode
  /** `h3` where the section sits under another head. */
  headingLevel?: 'h2' | 'h3'
  children: ReactNode
}

/**
 * Ein Abschnitt einer Verwaltungsseite (#1608): Überschrift auf einer Haarlinie, darunter der
 * Inhalt — **kein Kasten**.
 *
 * Das ist die Sprache, die die Kontenliste bereits spricht und die hier für den ganzen Bereich
 * gilt: Struktur entsteht aus Typografie, Weißraum und waagerechten Linien. Ein Rahmen ringsum
 * trägt keine Information; drei davon ineinander (Abschnitt, Eintrag, Eingabefeld) machen eine
 * Seite nur langsamer lesbar. Rahmen bleiben dort, wo sie Bedienbarkeit tragen: an Eingabefeldern,
 * Schaltflächen, Dialogen und am gestrichelten Leerzustand.
 *
 * Der Abschnitt ist auch für Screenreader einer: `section` mit `aria-labelledby` auf die
 * Überschrift — die Gliederung geht also nicht verloren, nur ihre Umrandung.
 */
export default function PageSection({
  title,
  description,
  action,
  headingLevel = 'h2',
  children,
}: PageSectionProps) {
  // `useId` statt eines Modulzählers: stabil über Rendervorgänge und eindeutig auch dann, wenn
  // zwei Abschnitte denselben Titel tragen.
  const headId = useId()
  return (
    <Box component="section" aria-labelledby={headId} sx={{ mb: 4 }}>
      {action ? (
        <Box
          sx={{
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
            gap: 2,
            flexWrap: 'wrap',
            borderBottom: 1,
            borderColor: 'divider',
            pb: 1,
            mb: description ? 1 : 2,
          }}
        >
          <SectionHead id={headId} component={headingLevel} underline={false}>
            {title}
          </SectionHead>
          <Box sx={{ flex: 'none' }}>{action}</Box>
        </Box>
      ) : (
        <Box sx={{ mb: description ? -1 : 0 }}>
          <SectionHead id={headId} component={headingLevel}>
            {title}
          </SectionHead>
        </Box>
      )}
      {description && (
        <Typography sx={{ fontSize: 12.5, color: 'text.secondary', mb: 2, maxWidth: '80ch' }}>
          {description}
        </Typography>
      )}
      {children}
    </Box>
  )
}
