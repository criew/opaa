import { useCallback, useEffect, useState } from 'react'
import Alert from '@mui/material/Alert'
import Button from '@mui/material/Button'
import Typography from '@mui/material/Typography'
import type { SuccessionSettingsResponse } from '@/shared/api/types'
import { getSuccessionSettings, updateSuccessionSettings } from '../api/successionApi'
import FactoryDefaultNumberSetting from '@/shared/ui/FactoryDefaultNumberSetting'
import PageSection from '@/shared/ui/PageSection'

/** Months in words: „1 Monat“, „12 Monate“. */
function monthsLabel(months: number): string {
  return months === 1 ? '1 Monat' : `${months} Monate`
}

interface SuccessionSettingsSectionProps {
  /** Called after a save, so the list can mark its entries anew. */
  onSaved: () => void
}

/**
 * From which age an entry of the list counts as „lange offen“ (ADR-0036, Entscheidung 6). The
 * threshold only highlights: no deadline, no reminder, no e-mail.
 */
export default function SuccessionSettingsSection({ onSaved }: SuccessionSettingsSectionProps) {
  const [settings, setSettings] = useState<SuccessionSettingsResponse | null>(null)
  const [loadError, setLoadError] = useState<string | null>(null)
  const load = useCallback(
    () =>
      getSuccessionSettings()
        .then((loaded) => {
          setSettings(loaded)
          setLoadError(null)
        })
        .catch((err: unknown) =>
          setLoadError(
            err instanceof Error ? err.message : 'Die Schwelle konnte nicht geladen werden.',
          ),
        ),
    [],
  )

  useEffect(() => {
    void load()
  }, [load])

  return (
    <PageSection
      title="Hervorhebung lange offener Einträge"
      description="Ein Eintrag, der länger offen ist als diese Schwelle, wird in der Liste als „lange offen“ hervorgehoben. Die Schwelle hebt nur hervor: Sie setzt keine Frist, erinnert nicht und verschickt nichts."
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
          Schwelle wird geladen …
        </Typography>
      ) : (
        <FactoryDefaultNumberSetting
          key={`${settings.agingThresholdMonths}-${settings.overridden}`}
          label="Lange offen ab (Monate)"
          field="agingThresholdMonths"
          value={settings.agingThresholdMonths}
          defaultValue={settings.defaultAgingThresholdMonths}
          overridden={settings.overridden}
          min={settings.minAgingThresholdMonths}
          max={settings.maxAgingThresholdMonths}
          format={monthsLabel}
          confirmChange={(next) => ({
            question: `Einträge ab ${monthsLabel(next)} als lange offen hervorheben?`,
            consequence:
              'Gilt ab dem nächsten Laden der Liste. Die Änderung steht im Revisionsprotokoll.',
          })}
          confirmReset={(current, factory) => ({
            consequence:
              'Gilt ab dem nächsten Laden der Liste. Die Änderung steht im Revisionsprotokoll.',
            warning:
              factory > current
                ? `Einträge werden dann erst nach ${monthsLabel(factory)} statt nach ${monthsLabel(current)} hervorgehoben.`
                : null,
          })}
          savedMessage={(months) =>
            `Einträge werden jetzt ab ${monthsLabel(months)} als lange offen hervorgehoben.`
          }
          failedMessage="Schwelle nicht gespeichert"
          onSave={async (next) => {
            const saved = await updateSuccessionSettings(next)
            setSettings(saved)
            onSaved()
            return saved.agingThresholdMonths
          }}
        />
      )}
    </PageSection>
  )
}
