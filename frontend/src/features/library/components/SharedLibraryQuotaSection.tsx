import { useCallback, useEffect, useState, type FormEvent } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Stack from '@mui/material/Stack'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import type { SharedLibraryQuotaResponse } from '@/shared/api/types'
import { getSharedLibraryQuota, updateSharedLibraryQuota } from '../api/sharedLibraryQuotaApi'
import { confirmAction } from '@/shared/stores/confirmStore'
import { notify } from '@/shared/stores/notificationStore'
import { formatFileSize } from '@/shared/utils/labels'
import BusyButton from '@/shared/ui/a11y/BusyButton'
import PageSection from '@/shared/ui/PageSection'
import { DRAFT_PRECISION_BYTES, limitDraft, parseLimit } from '../utils/storageLimit'

/** The limit in force in words; 0 is none. */
function currentText(quota: SharedLibraryQuotaResponse): string {
  const own = quota.ownQuotaBytes ?? null
  if (own === null) {
    return quota.capBytes === 0
      ? 'Es gilt keine Grenze je Bibliothek.'
      : `Es gilt die Obergrenze des Betriebs: ${formatFileSize(quota.capBytes)} je Bibliothek.`
  }
  if (quota.capBytes > 0 && own > quota.capBytes) {
    return `Die eigene Grenze von ${formatFileSize(own)} liegt über der Obergrenze des Betriebs; es gilt ${formatFileSize(quota.quotaBytes)} je Bibliothek.`
  }
  return quota.capBytes === 0
    ? `Es gilt eine eigene Grenze: ${formatFileSize(own)} je Bibliothek.`
    : `Es gilt eine eigene Grenze: ${formatFileSize(own)} je Bibliothek (Obergrenze des Betriebs: ${formatFileSize(quota.capBytes)}).`
}

/**
 * The typed limit, held to the upper limit of the operation; a limit above it by less than the
 * field can show is the upper limit itself.
 */
function validated(
  draft: string,
  current: SharedLibraryQuotaResponse,
): { bytes: number; error: string | null } {
  const capBytes = current.capBytes
  const parsed = parseLimit(draft, [current.ownQuotaBytes ?? 0, current.quotaBytes, capBytes])
  if (parsed.error === null && capBytes > 0 && parsed.bytes > capBytes) {
    if (parsed.bytes - capBytes <= DRAFT_PRECISION_BYTES) return { bytes: capBytes, error: null }
    return {
      bytes: 0,
      error: `Die Grenze darf die Obergrenze des Betriebs von ${formatFileSize(capBytes)} nicht überschreiten.`,
    }
  }
  return parsed
}

function QuotaForm({
  current,
  onSaved,
}: {
  current: SharedLibraryQuotaResponse
  onSaved: (saved: SharedLibraryQuotaResponse) => void
}) {
  const own = current.ownQuotaBytes ?? null
  const [draft, setDraft] = useState(current.quotaBytes === 0 ? '' : limitDraft(current.quotaBytes))
  const [busy, setBusy] = useState(false)
  const [saveError, setSaveError] = useState<string | null>(null)

  const parsed = validated(draft, current)
  const valid = parsed.error === null
  const changed = valid && parsed.bytes !== (own ?? current.quotaBytes)

  async function save(next: number | null) {
    setBusy(true)
    setSaveError(null)
    try {
      const saved = await updateSharedLibraryQuota(next)
      onSaved(saved)
      notify(
        saved.ownQuotaBytes == null
          ? 'Für geteilte Bibliotheken gilt jetzt die Obergrenze des Betriebs.'
          : `Das Speicherkontingent geteilter Bibliotheken ist jetzt ${formatFileSize(saved.quotaBytes)} je Bibliothek.`,
        'success',
      )
    } catch (err: unknown) {
      setSaveError(
        err instanceof Error ? err.message : 'Die Grenze konnte nicht gespeichert werden.',
      )
    } finally {
      setBusy(false)
    }
  }

  async function handleSubmit(event: FormEvent) {
    event.preventDefault()
    if (busy || !changed) return
    const confirmed = await confirmAction({
      question: `Speicherkontingent geteilter Bibliotheken auf ${formatFileSize(parsed.bytes)} je Bibliothek ändern?`,
      consequence:
        'Die Grenze gilt für jede geteilte Bibliothek ab dem nächsten aufgenommenen Dokument. Gelöscht wird nichts: Eine Bibliothek, die schon mehr belegt, behält alle Dokumente und nimmt nichts mehr auf, bis sie darunter liegt. Die Änderung steht im Revisionsprotokoll.',
      confirmLabel: 'Grenze ändern',
      tone: 'caution',
    })
    if (!confirmed) return
    await save(parsed.bytes)
  }

  async function handleRemove() {
    if (busy) return
    const confirmed = await confirmAction({
      question: 'Eigene Grenze für geteilte Bibliotheken entfernen?',
      consequence:
        current.capBytes === 0
          ? 'Geteilte Bibliotheken nehmen dann ohne Grenze auf. Die Änderung steht im Revisionsprotokoll.'
          : `Es gilt dann die Obergrenze des Betriebs von ${formatFileSize(current.capBytes)}. Die Änderung steht im Revisionsprotokoll.`,
      confirmLabel: 'Grenze entfernen',
      tone: 'caution',
    })
    if (!confirmed) return
    await save(null)
  }

  return (
    <Box component="form" onSubmit={(event) => void handleSubmit(event)} noValidate>
      <Stack
        direction={{ xs: 'column', sm: 'row' }}
        spacing={2}
        sx={{ alignItems: { sm: 'flex-start' } }}
      >
        <TextField
          label="Grenze je Bibliothek (GB)"
          size="small"
          value={draft}
          onChange={(event) => {
            setDraft(event.target.value)
            setSaveError(null)
          }}
          error={!valid && draft.trim() !== ''}
          helperText={
            draft.trim() !== '' && parsed.error
              ? parsed.error
              : current.capBytes > 0
                ? `Höchstens ${formatFileSize(current.capBytes)}, die Obergrenze des Betriebs.`
                : 'Der Betrieb hat keine Obergrenze gesetzt.'
          }
          slotProps={{ htmlInput: { inputMode: 'decimal' } }}
          sx={{ maxWidth: 260 }}
        />
        <BusyButton
          type="submit"
          variant="outlined"
          busy={busy}
          busyAnnouncement="Grenze wird gespeichert"
          disabled={!changed && !busy}
          sx={{ mt: { sm: 0.25 } }}
        >
          Grenze speichern
        </BusyButton>
        {own !== null && (
          <Button
            variant="text"
            disabled={busy}
            onClick={() => void handleRemove()}
            sx={{ mt: { sm: 0.25 } }}
          >
            Eigene Grenze entfernen
          </Button>
        )}
      </Stack>
      {saveError && (
        <Alert severity="error" role="alert" sx={{ mt: 2 }}>
          {saveError}
        </Alert>
      )}
    </Box>
  )
}

/**
 * The house-wide storage quota of every shared library, below the upper limit the operation sets,
 * and how many shared libraries already occupy more. Private libraries have their own section.
 */
export default function SharedLibraryQuotaSection() {
  const [quota, setQuota] = useState<SharedLibraryQuotaResponse | null>(null)
  const [loadError, setLoadError] = useState<string | null>(null)

  const load = useCallback(
    () =>
      getSharedLibraryQuota()
        .then((loaded) => {
          setQuota(loaded)
          setLoadError(null)
        })
        .catch((err: unknown) => {
          setLoadError(
            err instanceof Error
              ? err.message
              : 'Das Speicherkontingent geteilter Bibliotheken konnte nicht geladen werden.',
          )
        }),
    [],
  )

  useEffect(() => {
    void load()
  }, [load])

  return (
    <PageSection
      title="Speicher geteilter Bibliotheken"
      description="Die hausweite Grenze je geteilter Bibliothek. Der Betrieb legt eine Obergrenze fest, darunter entscheidet die Systemverwaltung."
    >
      {loadError ? (
        <Alert
          severity="error"
          action={
            <Button color="inherit" size="small" onClick={() => void load()}>
              Erneut laden
            </Button>
          }
        >
          {loadError}
        </Alert>
      ) : quota === null ? (
        <Typography role="status" sx={{ fontSize: 12.5, color: 'text.secondary' }}>
          Speicherkontingent wird geladen …
        </Typography>
      ) : (
        <>
          <Typography variant="body2" sx={{ mb: 2 }} data-testid="shared-library-quota-current">
            {currentText(quota)}
          </Typography>
          <QuotaForm
            key={`${quota.quotaBytes}-${quota.ownQuotaBytes ?? 'none'}`}
            current={quota}
            onSaved={setQuota}
          />
          {quota.librariesOverQuota > 0 && (
            <Alert severity="warning" sx={{ mt: 2 }} data-testid="shared-library-quota-over">
              {quota.librariesOverQuota === 1
                ? `1 geteilte Bibliothek belegt schon mehr als ${formatFileSize(quota.quotaBytes)}. Sie behält alle Dokumente und nimmt erst wieder etwas auf, wenn sie darunter liegt.`
                : `${quota.librariesOverQuota.toLocaleString('de-DE')} geteilte Bibliotheken belegen schon mehr als ${formatFileSize(quota.quotaBytes)}. Sie behalten alle Dokumente und nehmen erst wieder etwas auf, wenn sie darunter liegen.`}
            </Alert>
          )}
        </>
      )}
    </PageSection>
  )
}
