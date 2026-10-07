import { useEffect, useRef, useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import ButtonBase from '@mui/material/ButtonBase'
import Drawer from '@mui/material/Drawer'
import IconButton from '@mui/material/IconButton'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import ArrowBackIcon from '@mui/icons-material/ArrowBack'
import ChevronRightIcon from '@mui/icons-material/ChevronRight'
import CloseIcon from '@mui/icons-material/Close'
import type { CapabilityOverviewResponse } from '../../../types/api'
import { grantCapability, revokeCapability } from '../../../services/capabilityAdminApi'
import { confirmAction } from '../../../stores/confirmStore'
import { notify } from '../../../stores/notificationStore'
import { confirmGroupSubject } from '../../permissions/subjectSelection'
import AccessBadge from './AccessBadge'
import AccessEditor from './AccessEditor'
import {
  accessBadgeLabel,
  currentAccess,
  objectPhrase,
  planAccessChange,
  resultSentence,
  scopeKind,
  scopeShortLabel,
  type Access,
} from './capabilityAccess'
import { presentationOf } from './capabilityPresentation'

function ScopeList({
  title,
  entries,
  onChoose,
}: {
  title: string
  entries: CapabilityOverviewResponse[]
  onChoose: (scope: string) => void
}) {
  if (entries.length === 0) return null
  return (
    <Box component="section" aria-label={title}>
      <Typography
        component="h3"
        sx={{
          fontSize: 12,
          fontWeight: 600,
          color: 'text.secondary',
          letterSpacing: 0.4,
          mb: 0.75,
        }}
      >
        {title}
      </Typography>
      <Box
        component="ul"
        sx={{
          listStyle: 'none',
          p: 0,
          m: 0,
          borderTop: 1,
          borderBottom: 1,
          borderColor: 'divider',
        }}
      >
        {entries.map((entry) => {
          const access = currentAccess(entry)
          return (
            <Box
              component="li"
              key={entry.scope}
              sx={{ '& + &': { borderTop: 1, borderColor: 'divider' } }}
            >
              <ButtonBase
                onClick={() => onChoose(entry.scope ?? '')}
                sx={{
                  width: '100%',
                  display: 'flex',
                  alignItems: 'center',
                  gap: 1.5,
                  px: 1,
                  py: 1.25,
                  textAlign: 'left',
                  '&:hover': { bgcolor: 'action.hover' },
                  '&.Mui-focusVisible': { bgcolor: 'action.focus' },
                }}
              >
                <Typography sx={{ fontSize: 14, flex: 1, minWidth: 0 }}>
                  {scopeShortLabel(entry)}
                </Typography>
                <AccessBadge tone={access.level} label={accessBadgeLabel(access)} />
                <ChevronRightIcon
                  aria-hidden="true"
                  sx={{ fontSize: 20, color: 'text.secondary' }}
                />
              </ButtonBase>
            </Box>
          )
        })}
      </Box>
    </Box>
  )
}

/**
 * The questions before a change is sent: the confirmation before all accounts lose the right and
 * the one before a group of an external provider gets it.
 */
async function confirmAccess(
  entry: CapabilityOverviewResponse,
  target: Access,
  phrase: string,
): Promise<boolean> {
  const plan = planAccessChange(entry, target)
  if (plan.withdrawsAllAccounts) {
    const confirmed = await confirmAction({
      question: 'Nicht mehr für alle Konten?',
      consequence: `${resultSentence(target, phrase)} Für alle anderen Konten gilt das sofort, auch für Personen, die gerade angemeldet sind. Die Änderung wird protokolliert.`,
      confirmLabel: 'Einschränken',
      tone: 'danger',
    })
    if (!confirmed) return false
  }
  for (const subject of target.subjects) {
    const added = plan.steps.some((step) => step.kind === 'GRANT' && step.subjectId === subject.id)
    if (added && subject.group && !(await confirmGroupSubject(subject.group))) return false
  }
  return true
}

/** Grants before revocations; a failure midway names how far it got. */
async function applyAccess(entry: CapabilityOverviewResponse, target: Access): Promise<void> {
  const plan = planAccessChange(entry, target)
  let done = 0
  try {
    for (const step of plan.steps) {
      if (step.kind === 'GRANT') {
        await grantCapability(entry.capability, {
          subjectType: step.subjectType,
          ...(step.subjectId ? { subjectId: step.subjectId } : {}),
          ...(entry.scope ? { scope: entry.scope } : {}),
        })
      } else {
        await revokeCapability(entry.capability, step.grantId)
      }
      done += 1
    }
  } catch (err) {
    const reason =
      err instanceof Error ? err.message : 'Die Änderung konnte nicht gespeichert werden.'
    throw new Error(
      done === 0
        ? reason
        : `${reason} Ausgeführt wurden ${done} von ${plan.steps.length} Schritten; die Übersicht zeigt den Stand danach.`,
      { cause: err },
    )
  }
}

interface CapabilityAccessPanelProps {
  /** The right to change, all its scopes; null keeps the panel closed. */
  entries: CapabilityOverviewResponse[] | null
  onClose: () => void
  /** Reloads the overview; the panel reads the new state from `entries` afterwards. */
  onSaved: () => Promise<void>
}

/** The side panel with the one question per right: who may create this? */
export default function CapabilityAccessPanel({
  entries,
  onClose,
  onSaved,
}: CapabilityAccessPanelProps) {
  const [scope, setScope] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const headingRef = useRef<HTMLHeadingElement>(null)
  const errorRef = useRef<HTMLDivElement>(null)

  const first = entries?.[0]
  const scoped = Boolean(first?.scope)
  const entry = scoped ? entries?.find((candidate) => candidate.scope === scope) : first
  const presentation = first ? presentationOf(first.capability, first.label) : null

  // A view change inside the open panel moves the focus to its new heading.
  const open = Boolean(entries)
  useEffect(() => {
    if (open) headingRef.current?.focus()
  }, [scope, open])

  // After a failed save the focus goes to the message (docs/design/accessibility.md, 2.7).
  useEffect(() => {
    if (error) errorRef.current?.focus()
  }, [error])

  function choose(next: string | null) {
    setError(null)
    setScope(next)
  }

  const heading = entry ? `Wer darf ${objectPhrase(entry)}?` : (presentation?.title ?? '')

  async function save(target: Access) {
    if (!entry) return
    setError(null)
    const phrase = objectPhrase(entry)
    if (!(await confirmAccess(entry, target, phrase))) return
    setBusy(true)
    try {
      await applyAccess(entry, target)
      notify(`Gespeichert: ${resultSentence(target, phrase)}`, 'success')
      await onSaved()
      if (scoped) setScope(null)
      else onClose()
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Die Änderung konnte nicht gespeichert werden.')
      await onSaved()
    } finally {
      setBusy(false)
    }
  }

  return (
    <Drawer
      anchor="right"
      open={Boolean(entries)}
      onClose={busy ? undefined : onClose}
      slotProps={{
        paper: {
          role: 'dialog',
          'aria-labelledby': 'capability-panel-heading',
          sx: { width: { xs: '100%', sm: 480 }, display: 'flex', flexDirection: 'column' },
        },
      }}
    >
      <Box
        sx={{
          display: 'flex',
          alignItems: 'flex-start',
          gap: 1,
          px: 3,
          pt: 2.5,
          pb: 2,
          borderBottom: 1,
          borderColor: 'divider',
        }}
      >
        <Box sx={{ flex: 1, minWidth: 0 }}>
          {scoped && entry && (
            <Button
              size="small"
              startIcon={<ArrowBackIcon />}
              onClick={() => choose(null)}
              disabled={busy}
              sx={{ ml: -1, mb: 0.5 }}
            >
              Alle Anbindungen
            </Button>
          )}
          <Typography
            id="capability-panel-heading"
            ref={headingRef}
            tabIndex={-1}
            component="h2"
            sx={{ fontSize: 18, fontWeight: 600, outline: 'none' }}
          >
            {heading}
          </Typography>
          {scoped && !entry && (
            <Typography sx={{ fontSize: 13.5, color: 'text.secondary', mt: 0.5 }}>
              Für jede Quelle und jeden Zugang legen Sie getrennt fest, wer solche Bibliotheken
              anlegen darf. Die Freigabe eines Zugangs öffnet keinen anderen.
            </Typography>
          )}
        </Box>
        <IconButton aria-label="Schließen" onClick={onClose} disabled={busy} sx={{ mt: -0.5 }}>
          <CloseIcon />
        </IconButton>
      </Box>

      <Box sx={{ flex: 1, overflowY: 'auto', px: 3, py: 2.5 }}>
        {error && (
          <Alert
            ref={errorRef}
            tabIndex={-1}
            severity="error"
            sx={{ mb: 2 }}
            onClose={() => setError(null)}
          >
            {error}
          </Alert>
        )}
        {entry && (
          <AccessEditor
            // Keyed by the right and scope only: after a failed save the choice stays and is
            // planned against the reloaded state when saved again.
            key={entry.scope ?? entry.capability}
            entry={entry}
            phrase={objectPhrase(entry)}
            busy={busy}
            onCancel={scoped ? () => choose(null) : onClose}
            onSave={save}
          />
        )}
        {scoped && !entry && entries && (
          <Stack spacing={2.5}>
            <ScopeList
              title="Quellen"
              entries={entries.filter((candidate) => scopeKind(candidate) === 'TYPE')}
              onChoose={choose}
            />
            <ScopeList
              title="Zugänge"
              entries={entries.filter((candidate) => scopeKind(candidate) === 'PROFILE')}
              onChoose={choose}
            />
          </Stack>
        )}
      </Box>
    </Drawer>
  )
}
