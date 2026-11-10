import { useCallback, useEffect, useState } from 'react'
import Alert from '@mui/material/Alert'
import Button from '@mui/material/Button'
import FormControlLabel from '@mui/material/FormControlLabel'
import Switch from '@mui/material/Switch'
import Typography from '@mui/material/Typography'
import type { DocumentTemplateSettingsResponse } from '@/shared/api/types'
import { notify } from '@/shared/stores/notificationStore'
import { useRuntimeSwitchStore } from '@/shared/session/runtimeSwitches'
import PageSection from '@/shared/ui/PageSection'
import {
  getDocumentTemplateSettings,
  updateDocumentTemplateSettings,
} from '../api/documentTemplateApi'

function changedLine(settings: DocumentTemplateSettingsResponse): string | null {
  if (!settings.updatedAt) return null
  const when = new Date(settings.updatedAt).toLocaleString('de-DE', {
    dateStyle: 'medium',
    timeStyle: 'short',
  })
  return settings.updatedBy
    ? `Zuletzt geändert am ${when} von ${settings.updatedBy}.`
    : `Zuletzt geändert am ${when}.`
}

/**
 * The installation switch „Vorlagen". A change is saved at once and takes effect with the next
 * request on every instance; switched off, the stored templates stay and come back unchanged.
 */
export default function DocumentTemplateSettingsSection() {
  const refreshSwitches = useRuntimeSwitchStore((s) => s.refresh)
  const [current, setCurrent] = useState<DocumentTemplateSettingsResponse | null>(null)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [saveError, setSaveError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  const load = useCallback(
    () =>
      getDocumentTemplateSettings()
        .then((loaded) => {
          setCurrent(loaded)
          setLoadError(null)
        })
        .catch(() => setLoadError('Die Einstellung „Vorlagen“ konnte nicht geladen werden.')),
    [],
  )

  useEffect(() => {
    void load()
  }, [load])

  async function handleChange(enabled: boolean) {
    if (busy) return
    setBusy(true)
    setSaveError(null)
    try {
      const saved = await updateDocumentTemplateSettings(enabled)
      setCurrent(saved)
      await refreshSwitches()
      notify(saved.enabled ? 'Vorlagen sind eingeschaltet.' : 'Vorlagen sind aus.', 'success')
    } catch {
      setSaveError('Die Einstellung „Vorlagen“ konnte nicht gespeichert werden.')
    } finally {
      setBusy(false)
    }
  }

  return (
    <PageSection
      title="Vorlagen"
      description="Word-Vorlagen mit Platzhaltern, die Agenten ausfüllen. Ab Werk aus."
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
      ) : current === null ? (
        <Typography role="status" sx={{ fontSize: 12.5, color: 'text.secondary' }}>
          Einstellung wird geladen …
        </Typography>
      ) : (
        <>
          <FormControlLabel
            control={
              <Switch
                checked={current.enabled}
                disabled={busy}
                onChange={(event) => void handleChange(event.target.checked)}
              />
            }
            label="Vorlagen anbieten"
          />
          <Typography sx={{ fontSize: 12.5, color: 'text.secondary', mt: 0.5 }}>
            {current.enabled
              ? 'Eingeschaltet: Wer das Anlegerecht „Vorlagen anlegen“ hat, legt Vorlagen an; der Katalog zeigt sie.'
              : 'Aus: Der Katalog zeigt keine Vorlagen, und niemand kann eine anlegen. Gespeicherte Vorlagen bleiben erhalten.'}
          </Typography>
          {changedLine(current) && (
            <Typography sx={{ fontSize: 12.5, color: 'text.secondary', mt: 1 }}>
              {changedLine(current)}
            </Typography>
          )}
          {saveError && (
            <Alert severity="error" role="alert" sx={{ mt: 2 }}>
              {saveError}
            </Alert>
          )}
        </>
      )}
    </PageSection>
  )
}
