import { useCallback, useEffect, useState, type FormEvent } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Stack from '@mui/material/Stack'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import { getLinkTargets } from '@/shared/linkTargets/linkTargetsApi'
import { useLinkTargetsStore } from '@/shared/linkTargets/linkTargetsStore'
import { notify } from '@/shared/stores/notificationStore'
import BusyButton from '@/shared/ui/a11y/BusyButton'
import PageSection from '@/shared/ui/PageSection'
import { updateLinkTargets } from '../api/linkTargetsApi'

/**
 * The allowed link targets of answers: links to the application's own address and to these origins
 * are clickable, every other link stays text. Each change saves the whole list at once.
 */
export default function LinkTargetsSection() {
  const [origins, setOrigins] = useState<string[] | null>(null)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [draft, setDraft] = useState('')
  const [saveError, setSaveError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const setStoreOrigins = useLinkTargetsStore((s) => s.setAllowedOrigins)

  const load = useCallback(
    () =>
      getLinkTargets()
        .then((loaded) => {
          setOrigins(loaded.allowedOrigins)
          setLoadError(null)
        })
        .catch((err: unknown) => {
          setLoadError(
            err instanceof Error
              ? err.message
              : 'Die erlaubten Linkziele konnten nicht geladen werden.',
          )
        }),
    [],
  )

  useEffect(() => {
    void load()
  }, [load])

  async function save(next: string[], success: string): Promise<boolean> {
    setBusy(true)
    setSaveError(null)
    try {
      const saved = await updateLinkTargets(next)
      setOrigins(saved.allowedOrigins)
      setStoreOrigins(saved.allowedOrigins)
      notify(success, 'success')
      return true
    } catch (err: unknown) {
      setSaveError(
        err instanceof Error
          ? err.message
          : 'Die erlaubten Linkziele konnten nicht gespeichert werden.',
      )
      return false
    } finally {
      setBusy(false)
    }
  }

  async function handleAdd(event: FormEvent) {
    event.preventDefault()
    const entry = draft.trim()
    if (busy || origins === null || entry === '') return
    if (await save([...origins, entry], `${entry} ist jetzt ein erlaubtes Linkziel.`)) {
      setDraft('')
    }
  }

  return (
    <PageSection
      title="Erlaubte Linkziele"
      description="Links in Antworten und in der Dokumentvorschau sind nur zur eigenen Adresse von OPAA und zu diesen Zielen klickbar. Jeder andere Link erscheint als Text mit vollständiger Adresse, Bilder werden nie geladen."
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
      ) : origins === null ? (
        <Typography role="status" sx={{ fontSize: 12.5, color: 'text.secondary' }}>
          Erlaubte Linkziele werden geladen …
        </Typography>
      ) : (
        <Stack spacing={2} sx={{ maxWidth: 560 }}>
          {origins.length === 0 ? (
            <Typography variant="body2" data-testid="link-targets-empty">
              Es ist kein Linkziel eingetragen; klickbar sind nur Links zur eigenen Adresse.
            </Typography>
          ) : (
            <Box
              component="ul"
              aria-label="Erlaubte Linkziele"
              sx={{ m: 0, p: 0, listStyle: 'none' }}
            >
              {origins.map((origin) => (
                <Box
                  component="li"
                  key={origin}
                  sx={{ display: 'flex', alignItems: 'center', gap: 1, py: 0.5 }}
                >
                  <Typography variant="body2" sx={{ flexGrow: 1, overflowWrap: 'anywhere' }}>
                    {origin}
                  </Typography>
                  <Button
                    size="small"
                    disabled={busy}
                    aria-label={`Linkziel ${origin} entfernen`}
                    onClick={() =>
                      void save(
                        origins.filter((entry) => entry !== origin),
                        `${origin} ist kein erlaubtes Linkziel mehr.`,
                      )
                    }
                  >
                    Entfernen
                  </Button>
                </Box>
              ))}
            </Box>
          )}
          <Box
            component="form"
            onSubmit={(event) => void handleAdd(event)}
            noValidate
            sx={{ display: 'flex', gap: 1, alignItems: 'flex-start' }}
          >
            <TextField
              label="Neues Linkziel"
              size="small"
              value={draft}
              placeholder="https://intranet.stadt.example"
              onChange={(event) => setDraft(event.target.value)}
              helperText="Schema, Host und optional Port, ohne Pfad. Einen Host mit Umlauten in der Form xn--… eintragen."
              sx={{ flexGrow: 1 }}
            />
            <BusyButton
              type="submit"
              variant="outlined"
              busy={busy}
              busyAnnouncement="Erlaubte Linkziele werden gespeichert"
              disabled={draft.trim() === '' && !busy}
            >
              Hinzufügen
            </BusyButton>
          </Box>
          {saveError && (
            <Alert severity="error" role="alert">
              {saveError}
            </Alert>
          )}
        </Stack>
      )}
    </PageSection>
  )
}
