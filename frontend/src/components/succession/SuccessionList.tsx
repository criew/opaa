import { useCallback, useEffect, useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Link from '@mui/material/Link'
import Pagination from '@mui/material/Pagination'
import Stack from '@mui/material/Stack'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import { Link as RouterLink } from 'react-router'
import type {
  SuccessionEntryResponse,
  SuccessionKind,
  SuccessionListResponse,
} from '../../types/api'
import { getSuccessionEntries, reviewSuccessionCase } from '../../services/successionApi'
import { notify } from '../../stores/notificationStore'
import MetaBadge from '../MetaBadge'
import PermissionTransferDialog, {
  type TransferSubject,
} from '../permissions/PermissionTransferDialog'
import { ageLabel } from '../groups/groupOriginLabels'

const PAGE_SIZE = 50

function objectHref(entry: SuccessionEntryResponse): string | null {
  switch (entry.objectType) {
    case 'KNOWLEDGE_LIBRARY':
      return `/libraries/${entry.objectId}`
    case 'SPACE':
      return `/spaces/${entry.objectId}`
    default:
      return null
  }
}

function objectTypeLabel(entry: SuccessionEntryResponse): string {
  switch (entry.objectType) {
    case 'KNOWLEDGE_LIBRARY':
      return 'Bibliothek'
    case 'SPACE':
      return 'Space'
    default:
      return 'Gruppe'
  }
}

/** Die Übernahme: Ein Objekt einer Gruppe geht an eine Gruppe, alles andere an eine Person. */
function transferSourceOf(entry: SuccessionEntryResponse): TransferSubject | null {
  return entry.objectType === 'GROUP'
    ? { type: 'GROUP', id: entry.objectId, name: entry.objectName }
    : null
}

function SuccessionRow({
  entry,
  onReviewed,
}: {
  entry: SuccessionEntryResponse
  onReviewed: () => void
}) {
  const [reason, setReason] = useState('')
  const [open, setOpen] = useState(false)
  const [transferOpen, setTransferOpen] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const source = transferSourceOf(entry)
  const href = objectHref(entry)

  return (
    <Box sx={{ borderBottom: 1, borderColor: 'divider', py: 1.5 }}>
      <Stack direction="row" spacing={1} sx={{ alignItems: 'center', flexWrap: 'wrap' }}>
        <Typography sx={{ fontSize: 14, fontWeight: 600 }}>
          {/* Einstieg über das Objekt (ADR-0036/6, Personalrat E1) - nie über eine Person. */}
          {href ? (
            <Link component={RouterLink} to={href}>
              {entry.objectName}
            </Link>
          ) : (
            entry.objectName
          )}
        </Typography>
        <MetaBadge>{objectTypeLabel(entry)}</MetaBadge>
        {entry.highlighted && <MetaBadge accent>gealtert</MetaBadge>}
        <Typography sx={{ fontSize: 13, color: 'text.secondary', ml: 'auto' }}>
          Alter:{' '}
          {entry.firstSeenAt
            ? ageLabel(entry.firstSeenAt)
            : 'noch nicht vom Feststellungslauf erfasst'}
        </Typography>
      </Stack>

      <Typography sx={{ fontSize: 13, color: 'text.secondary' }}>
        Zuständig: {entry.addresseeLabel}
        {entry.ownerHint ? ` · derzeit: ${entry.ownerHint}` : ''}
        {entry.affectedObjects > 0 ? ` · betroffene Objekte: ${entry.affectedObjects}` : ''}
      </Typography>

      {(entry.membershipHints ?? []).length > 0 && (
        <Typography sx={{ fontSize: 12.5, color: 'text.secondary' }}>
          {/* Bestandsinformation als Hinweis, wo zu suchen ist - keine Aktivitätsauswertung
              (Personalrat E4). */}
          Hinweis: {(entry.membershipHints ?? []).join(', ')}
        </Typography>
      )}

      {entry.lastReviewedAt && (
        <Typography sx={{ fontSize: 12.5, color: 'text.secondary' }}>
          Sichtungsvermerk vom{' '}
          {new Date(entry.lastReviewedAt).toLocaleDateString('de-DE', { dateStyle: 'medium' })}
          {entry.lastReviewReason ? `: ${entry.lastReviewReason}` : ''}
        </Typography>
      )}

      {error && (
        <Alert severity="error" sx={{ mt: 1 }} onClose={() => setError(null)}>
          {error}
        </Alert>
      )}

      <Stack direction="row" spacing={1} sx={{ mt: 1 }}>
        {source && (
          <Button size="small" variant="outlined" onClick={() => setTransferOpen(true)}>
            Übernahme vorbereiten
          </Button>
        )}
        {entry.caseId && (
          <Button size="small" onClick={() => setOpen((current) => !current)}>
            {open ? 'Sichtungsvermerk abbrechen' : 'Sichtungsvermerk setzen'}
          </Button>
        )}
      </Stack>

      {open && entry.caseId && (
        <Stack direction={{ xs: 'column', md: 'row' }} spacing={1} sx={{ mt: 1 }}>
          <TextField
            size="small"
            fullWidth
            label="Geprüft, weiterhin offen — Grund"
            value={reason}
            onChange={(e) => setReason(e.target.value)}
          />
          <Button
            size="small"
            variant="contained"
            disabled={reason.trim() === ''}
            onClick={async () => {
              setError(null)
              try {
                await reviewSuccessionCase(entry.caseId!, reason.trim())
                setReason('')
                setOpen(false)
                notify('Der Sichtungsvermerk wurde festgehalten.', 'success')
                onReviewed()
              } catch (err) {
                setError(
                  err instanceof Error
                    ? err.message
                    : 'Der Sichtungsvermerk konnte nicht gesetzt werden.',
                )
              }
            }}
          >
            Vermerk setzen
          </Button>
        </Stack>
      )}

      {transferOpen && source && (
        <PermissionTransferDialog
          open
          onClose={() => setTransferOpen(false)}
          source={source}
          targetKinds={['GROUP']}
          scopes={['OWNERSHIP', 'ASSET_GRANTS', 'SPACE_MEMBERSHIPS', 'CAPABILITIES']}
          intro={`Eigentum und Wirkungen von „${entry.objectName}" gehen an die gewählte Gruppe. Endet der Zustand damit, schließt der nächste Feststellungslauf den Vorgang.`}
          onTransferred={onReviewed}
        />
      )}
    </Box>
  )
}

/**
 * Ein Reiter der Betriebsliste (#1819, ADR-0036 Entscheidung 6): vollständig ab dem ersten Tag,
 * älteste Zeile zuerst, Einstieg über das Objekt. **Keine Sortierung, keine Filterung und keine
 * Zählung nach früherem Eigentümer oder handelnder Person** (Personalrat E1/Z7) — die API bietet
 * das bewusst nicht, und die Oberfläche baut es auch nicht clientseitig nach.
 */
export default function SuccessionList({
  kind,
  emptyText,
}: {
  kind: SuccessionKind
  emptyText: string
}) {
  const [page, setPage] = useState(0)
  const [data, setData] = useState<SuccessionListResponse | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [isLoading, setIsLoading] = useState(true)

  const load = useCallback(
    () =>
      getSuccessionEntries(kind, page, PAGE_SIZE)
        .then((loaded) => {
          setData(loaded)
          setError(null)
        })
        .catch((err: unknown) =>
          setError(err instanceof Error ? err.message : 'Die Liste konnte nicht geladen werden.'),
        )
        .finally(() => setIsLoading(false)),
    [kind, page],
  )

  useEffect(() => {
    void load()
  }, [load])

  if (error) {
    return <Alert severity="error">{error}</Alert>
  }
  if (isLoading && !data) {
    return <Typography sx={{ color: 'text.secondary' }}>Die Liste wird geladen …</Typography>
  }
  if (!data || data.entries.length === 0) {
    return <Typography sx={{ color: 'text.secondary' }}>{emptyText}</Typography>
  }

  return (
    <Box>
      <Typography sx={{ fontSize: 13, color: 'text.secondary', mb: 1 }}>
        {data.totalElements === 1 ? '1 Eintrag' : `${data.totalElements} Einträge`}, älteste zuerst.
        Hervorgehobene Zeilen sind gealtert; ein Sichtungsvermerk nimmt die Hervorhebung für eine
        weitere Periode zurück. Es gibt keine Frist und keine Erinnerung.
      </Typography>

      {data.entries.map((entry) => (
        <SuccessionRow
          key={`${entry.objectType}-${entry.objectId}`}
          entry={entry}
          onReviewed={() => void load()}
        />
      ))}

      {data.totalPages > 1 && (
        <Pagination
          sx={{ mt: 2 }}
          count={data.totalPages}
          page={data.page + 1}
          onChange={(_event, next) => setPage(next - 1)}
          aria-label="Seiten der Betriebsliste"
        />
      )}
    </Box>
  )
}
