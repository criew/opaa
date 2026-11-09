import { useId, useState, type ReactNode } from 'react'
import { useNavigate } from 'react-router'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import ButtonBase from '@mui/material/ButtonBase'
import Collapse from '@mui/material/Collapse'
import Typography from '@mui/material/Typography'
import ChevronRightIcon from '@mui/icons-material/ChevronRight'
import PageHeading from '@/shared/ui/a11y/PageHeading'
import { confirmAction } from '@/shared/stores/confirmStore'
import { CATALOG_ROUTE } from '@/shared/routes'
import type { AgentSetup } from '../hooks/useAgentSetup'
import { ContradictionsField, LimitsField } from './AgentFields'
import { limitsSummary } from '../agentTexts'

export const MISSING_RIGHT_ID = 'agent-missing-right'

/** Head, notices and footer shared by every variant of the setup. */
export function AgentPageFrame({
  setup,
  eyebrow,
  title,
  maxWidth = 720,
  children,
  footer,
  aside,
}: {
  setup: AgentSetup
  eyebrow: string
  title: string
  maxWidth?: number
  children: ReactNode
  footer: ReactNode
  aside?: ReactNode
}) {
  if (setup.loaded && setup.requestedId && !setup.agent) return <AgentNotFound />
  return (
    <Box sx={{ flexGrow: 1, overflowY: 'auto', p: { xs: 2.5, md: 5 } }}>
      <Box
        sx={{
          display: 'grid',
          gridTemplateColumns: aside
            ? { xs: 'minmax(0, 1fr)', lg: `minmax(0, ${maxWidth}px) 360px` }
            : 'minmax(0, 1fr)',
          gap: 5,
          alignItems: 'start',
        }}
      >
        <Box sx={{ maxWidth, minWidth: 0 }}>
          <Typography sx={{ fontSize: 12.5, color: 'text.secondary', mb: 0.5 }}>
            {eyebrow}
          </Typography>
          <PageHeading
            title={title}
            sx={{ fontSize: 26, fontWeight: 600, mb: 3, overflowWrap: 'anywhere' }}
          />
          {setup.missingRight && (
            <Alert severity="info" sx={{ mb: 3 }} id={MISSING_RIGHT_ID}>
              {setup.missingRight}
            </Alert>
          )}
          {setup.saveError && (
            <Alert severity="error" sx={{ mb: 3 }}>
              {setup.saveError}
            </Alert>
          )}
          {children}
          <Box
            sx={{
              display: 'flex',
              alignItems: 'center',
              flexWrap: 'wrap',
              gap: 1.5,
              mt: 4,
              pt: 2,
              borderTop: 1,
              borderColor: 'divider',
            }}
          >
            {footer}
          </Box>
        </Box>
        {aside && (
          <Box sx={{ minWidth: 0, position: { lg: 'sticky' }, top: { lg: 0 } }}>{aside}</Box>
        )}
      </Box>
    </Box>
  )
}

/** „Abbrechen“ as a quiet text button that asks before discarding changes. */
export function CancelButton({ setup, to }: { setup: AgentSetup; to?: string }) {
  const navigate = useNavigate()
  return (
    <Button
      variant="text"
      disabled={setup.saving}
      onClick={() =>
        void (async () => {
          if (setup.isDirty) {
            const confirmed = await confirmAction({
              question: 'Eingaben verwerfen?',
              confirmLabel: 'Verwerfen',
              tone: 'neutral',
            })
            if (!confirmed) return
          }
          navigate(to ?? CATALOG_ROUTE)
        })()
      }
    >
      Abbrechen
    </Button>
  )
}

/** The primary save button; without the creation right it stays focusable and names why. */
export function SaveButton({
  setup,
  label,
  onSaved,
}: {
  setup: AgentSetup
  label: string
  onSaved: (agentId: string) => void
}) {
  return (
    <Button
      variant="contained"
      disabled={setup.saving || setup.missingRight !== null}
      aria-describedby={setup.missingRight ? MISSING_RIGHT_ID : undefined}
      onClick={() =>
        void (async () => {
          const saved = await setup.save()
          if (saved) onSaved(saved.id)
        })()
      }
    >
      {setup.saving ? 'Wird gespeichert …' : label}
    </Button>
  )
}

/**
 * The advanced settings - limits and contradictions - folded by default behind one
 * disclosure; folded, one line says what holds.
 */
export function AdvancedSettings({
  setup,
  idPrefix,
  defaultOpen = false,
}: {
  setup: AgentSetup
  idPrefix: string
  defaultOpen?: boolean
}) {
  const hasError = Boolean(setup.errors.calls || setup.errors.minutes || setup.errors.pages)
  const [open, setOpen] = useState(defaultOpen)
  const regionId = useId()
  const expanded = open || hasError
  return (
    <Box sx={{ borderTop: 1, borderColor: 'divider', pt: 1.5 }}>
      <Typography component="h2" sx={{ m: 0 }}>
        <ButtonBase
          aria-expanded={expanded}
          aria-controls={regionId}
          onClick={() => setOpen(!open)}
          sx={{ gap: 0.5, py: 0.5, borderRadius: 1, fontSize: 15, fontWeight: 600 }}
        >
          <ChevronRightIcon
            aria-hidden
            sx={{ fontSize: 20, transform: expanded ? 'rotate(90deg)' : 'none' }}
          />
          Erweitert
        </ButtonBase>
      </Typography>
      {!expanded && (
        <Typography sx={{ fontSize: 13, color: 'text.secondary', pl: 3.5 }}>
          {limitsSummary(setup.config.limits)}
          {setup.config.namesContradictions ? ' · Widersprüche benennen' : ''}
        </Typography>
      )}
      <Collapse in={expanded}>
        <Box
          id={regionId}
          sx={{ display: 'flex', flexDirection: 'column', gap: 2.5, pt: 2, pl: 3.5 }}
        >
          <LimitsField setup={setup} idPrefix={idPrefix} />
          <ContradictionsField setup={setup} idPrefix={idPrefix} />
        </Box>
      </Collapse>
    </Box>
  )
}

export function SectionTitle({ children }: { children: ReactNode }) {
  return (
    <Typography component="h2" sx={{ fontSize: 15, fontWeight: 600 }}>
      {children}
    </Typography>
  )
}

/** An agent that does not exist or is not readable: heading, one sentence, the way back. */
export function AgentNotFound() {
  return (
    <Box sx={{ flexGrow: 1, p: { xs: 2.5, md: 5 } }}>
      <PageHeading title="Agent nicht gefunden" sx={{ fontSize: 26, fontWeight: 600, mb: 2 }} />
      <Typography sx={{ color: 'text.secondary' }}>
        Den Agenten gibt es nicht, oder er ist für Sie nicht freigegeben.
      </Typography>
    </Box>
  )
}
