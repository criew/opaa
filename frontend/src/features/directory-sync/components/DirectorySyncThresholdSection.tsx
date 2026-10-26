import { useCallback, useEffect, useState } from 'react'
import Alert from '@mui/material/Alert'
import Button from '@mui/material/Button'
import Typography from '@mui/material/Typography'
import type { DirectorySyncSettingsResponse } from '@/shared/api/types'
import { getDirectorySyncSettings, updateDirectorySyncSettings } from '../api/directorySyncApi'
import FactoryDefaultNumberSetting from '@/shared/ui/FactoryDefaultNumberSetting'
import PageSection from '@/shared/ui/PageSection'

function percentLabel(percent: number): string {
  return `${percent} %`
}

const CHANGE_CONSEQUENCE =
  'Gilt ab dem nächsten Lauf jedes Anbieters. Ein Plan, der schon zur Bestätigung vorliegt, bleibt liegen, bis Sie über ihn entscheiden. Die Änderung steht im Revisionsprotokoll.'

/**
 * The plausibility threshold of the directory sync for the whole installation (ADR-0036,
 * Entscheidung 3): a run that would remove, freeze or lock more than this share writes nothing
 * and waits for a decision. Raising it lets larger withdrawals through without one.
 */
export default function DirectorySyncThresholdSection() {
  const [settings, setSettings] = useState<DirectorySyncSettingsResponse | null>(null)
  const [loadError, setLoadError] = useState<string | null>(null)
  const load = useCallback(
    () =>
      getDirectorySyncSettings()
        .then((loaded) => {
          setSettings(loaded)
          setLoadError(null)
        })
        .catch((err: unknown) =>
          setLoadError(
            err instanceof Error
              ? err.message
              : 'Die Plausibilitätsschwelle konnte nicht geladen werden.',
          ),
        ),
    [],
  )

  useEffect(() => {
    void load()
  }, [load])

  return (
    <PageSection
      title="Plausibilitätsschwelle"
      description="Würde ein Lauf mehr als diesen Anteil der betroffenen Mitgliedschaften, Gruppen oder Konten entfernen, einfrieren oder sperren, schreibt er nichts und legt seinen Plan zur Bestätigung vor. Die Schwelle gilt für alle Anbieter und schützt vor einem fehlkonfigurierten Verzeichnis, das scheinbar fast alles löscht."
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
      ) : settings === null ? (
        <Typography role="status" sx={{ fontSize: 12.5, color: 'text.secondary' }}>
          Plausibilitätsschwelle wird geladen …
        </Typography>
      ) : (
        <FactoryDefaultNumberSetting
          key={`${settings.changeThresholdPercent}-${settings.overridden}`}
          label="Bestätigung ab (Prozent)"
          field="changeThresholdPercent"
          value={settings.changeThresholdPercent}
          defaultValue={settings.defaultChangeThresholdPercent}
          overridden={settings.overridden}
          min={settings.minChangeThresholdPercent}
          max={settings.maxChangeThresholdPercent}
          format={percentLabel}
          confirmChange={(next) => ({
            question: `Plausibilitätsschwelle auf ${percentLabel(next)} ändern?`,
            consequence:
              next > settings.changeThresholdPercent
                ? `Ein Lauf darf dann bis zu ${percentLabel(next)} entfernen und sperren, ohne dass jemand bestätigt. ${CHANGE_CONSEQUENCE}`
                : `Läufe, die mehr als ${percentLabel(next)} entfernen oder sperren würden, warten dann auf Ihre Bestätigung. ${CHANGE_CONSEQUENCE}`,
            danger: true,
          })}
          confirmReset={(current, factory) => ({
            danger: true,
            consequence: CHANGE_CONSEQUENCE,
            warning:
              factory < current
                ? `Läufe, die mehr als ${percentLabel(factory)} statt mehr als ${percentLabel(current)} entfernen oder sperren würden, warten dann auf Bestätigung; ein Abgleich, der bisher durchlief, bleibt womöglich stehen.`
                : `Ein Lauf darf dann bis zu ${percentLabel(factory)} statt bis zu ${percentLabel(current)} entfernen und sperren, ohne dass jemand bestätigt.`,
          })}
          savedMessage={(percent) =>
            `Die Plausibilitätsschwelle des Verzeichnisabgleichs ist jetzt ${percentLabel(percent)}.`
          }
          failedMessage="Plausibilitätsschwelle nicht gespeichert"
          onSave={async (next) => {
            const saved = await updateDirectorySyncSettings(next)
            setSettings(saved)
            return saved.changeThresholdPercent
          }}
        />
      )}
    </PageSection>
  )
}
