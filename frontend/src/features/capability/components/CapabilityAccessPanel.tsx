import { useEffect, useRef, useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import ButtonBase from '@mui/material/ButtonBase'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import ArrowBackIcon from '@mui/icons-material/ArrowBack'
import ChevronRightIcon from '@mui/icons-material/ChevronRight'
import type { CapabilityOverviewResponse } from '@/shared/api/types'
import { connectionProfileRoute } from '@/shared/routes'
import { notify } from '@/shared/stores/notificationStore'
import { TextLink } from '@/shared/ui/DetailSection'
import AccessBadge from './AccessBadge'
import AccessEditor from './AccessEditor'
import { applyAccess, confirmAccess } from './capabilityAccessChange'
import {
  accessBadgeLabel,
  currentAccess,
  objectPhrase,
  resultSentence,
  scopeKind,
  scopeShortLabel,
  type Access,
} from './capabilityAccess'
import { presentationOf } from './capabilityPresentation'
import { fontSize, iconSize } from '@/shared/theme/tokens'
import SidePanel from '@/shared/ui/dialog/SidePanel'

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
          fontSize: fontSize.meta,
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
                <Typography sx={{ fontSize: fontSize.text, flex: 1, minWidth: 0 }}>
                  {scopeShortLabel(entry)}
                </Typography>
                <AccessBadge tone={access.level} label={accessBadgeLabel(access)} />
                <ChevronRightIcon
                  aria-hidden="true"
                  sx={{ fontSize: iconSize.md, color: 'text.secondary' }}
                />
              </ButtonBase>
            </Box>
          )
        })}
      </Box>
    </Box>
  )
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
    <SidePanel
      open={Boolean(entries)}
      onClose={onClose}
      busy={busy}
      // the editor brings „Abbrechen“; only the list of scopes is a view with a ✕
      form={Boolean(entry)}
      titleId="capability-panel-heading"
      titleRef={headingRef}
      title={heading}
      before={
        scoped &&
        entry && (
          <Button
            size="small"
            startIcon={<ArrowBackIcon />}
            onClick={() => choose(null)}
            disabled={busy}
            sx={{ ml: -1, mb: 0.5 }}
          >
            Alle Anbindungen
          </Button>
        )
      }
      below={
        <>
          {entry && scopeKind(entry) === 'PROFILE' && entry.scope && (
            <TextLink
              to={connectionProfileRoute(entry.scope.slice('PROFILE:'.length))}
              sx={{ mt: 0.5 }}
            >
              Zugang ansehen
            </TextLink>
          )}
          {scoped && !entry && (
            <Typography sx={{ fontSize: fontSize.text, color: 'text.secondary', mt: 0.5 }}>
              Für jede Quelle und jeden Zugang legen Sie getrennt fest, wer solche Bibliotheken
              anlegen darf. Die Freigabe eines Zugangs öffnet keinen anderen.
            </Typography>
          )}
        </>
      }
    >
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
    </SidePanel>
  )
}
