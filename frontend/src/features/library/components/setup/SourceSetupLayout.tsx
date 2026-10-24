import type { ReactNode } from 'react'
import Box from '@mui/material/Box'
import Typography from '@mui/material/Typography'
import { SETUP_SECTION_TITLES, useSetup, type SetupSectionKey } from './setupContext'

interface SourceSetupLayoutProps {
  /** Address, edition, provider - how the source is reached. */
  connection?: ReactNode
  /** Sign-in of the source; absent for a source without one. */
  credentials?: ReactNode
  /** What of the source is read. */
  scope?: ReactNode
  /** The one connection check of the form, placed after the sections it checks. */
  check?: ReactNode
  /** A sentence above the sections, e.g. which Zugang signs in. */
  intro?: ReactNode
}

/**
 * The frame of every source form: Verbindung → Zugangsdaten → Umfang → Zeitplan, each a section
 * with an h3 in the same size. A section a source does not have is left out; the order never
 * changes. The connection check sits under the last section it depends on (Zugangsdaten, or
 * Verbindung without one), the schedule comes from the surrounding step or dialog.
 */
export default function SourceSetupLayout({
  connection,
  credentials,
  scope,
  check,
  intro,
}: SourceSetupLayoutProps) {
  const { schedule, idPrefix, connectionLead } = useSetup()
  const checkAfter: SetupSectionKey | null = credentials
    ? 'credentials'
    : connection || connectionLead
      ? 'connection'
      : null
  const sections: [SetupSectionKey, ReactNode][] = [
    [
      'connection',
      connectionLead || connection ? (
        <>
          {connectionLead}
          {connection}
        </>
      ) : null,
    ],
    ['credentials', credentials],
    ['scope', scope],
    ['schedule', schedule],
  ]
  return (
    <Box sx={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
      {intro && <Box sx={{ fontSize: 13.5, color: 'text.secondary' }}>{intro}</Box>}
      {sections.map(([key, content]) =>
        content ? (
          <SetupSection
            key={key}
            id={`${idPrefix}-section-${key}`}
            title={SETUP_SECTION_TITLES[key]}
          >
            {content}
            {key === checkAfter && check}
          </SetupSection>
        ) : null,
      )}
      {!checkAfter && check}
    </Box>
  )
}

export function SetupSection({
  id,
  title,
  children,
}: {
  id: string
  title: string
  children: ReactNode
}) {
  return (
    <Box component="section" aria-labelledby={`${id}-title`}>
      <Typography id={`${id}-title`} component="h3" sx={{ fontSize: 16, fontWeight: 600, mb: 2 }}>
        {title}
      </Typography>
      <Box sx={{ display: 'flex', flexDirection: 'column', gap: 2.5 }}>{children}</Box>
    </Box>
  )
}
