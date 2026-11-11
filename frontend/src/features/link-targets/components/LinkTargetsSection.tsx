import { useCallback, useEffect, useState, type FormEvent } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Stack from '@mui/material/Stack'
import FormField from '@/shared/ui/form/FormField'
import Typography from '@mui/material/Typography'
import { getLinkTargets } from '@/shared/linkTargets/linkTargetsApi'
import { useLinkTargetsStore } from '@/shared/linkTargets/linkTargetsStore'
import { notify } from '@/shared/stores/notificationStore'
import BusyButton from '@/shared/ui/a11y/BusyButton'
import PageSection from '@/shared/ui/PageSection'
import { updateLinkTargets } from '../api/linkTargetsApi'
import { formWidth } from '@/shared/theme/tokens'
import LoadError from '@/shared/ui/states/LoadError'
import LoadingState from '@/shared/ui/states/LoadingState'

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
      description="Links in Antworten und Dokumentvorschau sind nur zu OPAA selbst und zu diesen Zielen klickbar; andere erscheinen als Text, Bilder werden nie geladen."
    >
      {loadError ? (
        <LoadError
          message="Die erlaubten Linkziele konnten nicht geladen werden."
          detail={loadError}
          onRetry={() => void load()}
        />
      ) : origins === null ? (
        <LoadingState shape="lines" count={2} />
      ) : (
        <Stack spacing={2} sx={{ maxWidth: formWidth.column }}>
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
          <Box component="form" onSubmit={(event) => void handleAdd(event)} noValidate>
            <FormField
              label="Neues Linkziel"
              size="small"
              value={draft}
              placeholder="https://intranet.stadt.example"
              onChange={(event) => setDraft(event.target.value)}
              helperText="Schema, Host und optional Port, ohne Pfad. Einen Host mit Umlauten in der Form xn--… eintragen."
              action={
                <BusyButton
                  type="submit"
                  variant="outlined"
                  busy={busy}
                  busyAnnouncement="Erlaubte Linkziele werden gespeichert"
                  disabled={draft.trim() === '' && !busy}
                >
                  Hinzufügen
                </BusyButton>
              }
            />
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
