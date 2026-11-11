import { useCallback, useEffect, useState } from 'react'
import type { SuccessionSettingsRequest, SuccessionSettingsResponse } from '@/shared/api/types'
import { getSuccessionSettings, updateSuccessionSettings } from '../api/successionApi'
import FactoryDefaultNumberSetting from '@/shared/ui/FactoryDefaultNumberSetting'
import PageSection from '@/shared/ui/PageSection'
import LoadError from '@/shared/ui/states/LoadError'
import LoadingState from '@/shared/ui/states/LoadingState'

/** Days in words: „1 Tag“, „30 Tage“. */
function daysLabel(days: number): string {
  return days === 1 ? '1 Tag' : `${days} Tage`
}

/** The request that keeps every value but the one changed. */
function requestOf(
  settings: SuccessionSettingsResponse,
  change: Partial<SuccessionSettingsRequest>,
): SuccessionSettingsRequest {
  return {
    agingThresholdMonths: settings.overridden ? settings.agingThresholdMonths : null,
    responsibleLockDays: settings.responsibleLockDaysOverridden
      ? settings.responsibleLockDays
      : null,
    ...change,
  }
}

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
    <>
      <PageSection
        title="Hervorhebung lange offener Einträge"
        description="Ein Eintrag, der länger offen ist als diese Schwelle, wird in der Liste als „lange offen“ hervorgehoben. Die Schwelle hebt nur hervor: Sie setzt keine Frist, erinnert nicht und verschickt nichts."
      >
        {loadError ? (
          <LoadError
            message="Die Schwelle konnte nicht geladen werden."
            detail={loadError}
            onRetry={() => void load()}
          />
        ) : settings === null ? (
          <LoadingState shape="lines" count={2} />
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
              question: `Schwelle für lange offene Einträge auf ${monthsLabel(next)} ändern?`,
              consequence:
                'Gilt ab dem nächsten Laden der Liste. Die Änderung steht im Revisionsprotokoll.',
              danger: next < settings.agingThresholdMonths,
            })}
            confirmReset={(current, factory) => ({
              danger: factory < current,
              consequence: `Einträge werden dann ${factory < current ? 'schon' : 'erst'} nach ${monthsLabel(factory)} statt nach ${monthsLabel(current)} hervorgehoben. Gilt ab dem nächsten Laden der Liste. Die Änderung steht im Revisionsprotokoll.`,
              warning: null,
            })}
            savedMessage={(months) =>
              `Einträge werden jetzt ab ${monthsLabel(months)} als lange offen hervorgehoben.`
            }
            failedMessage="Schwelle nicht gespeichert"
            onSave={async (next) => {
              const saved = await updateSuccessionSettings(
                requestOf(settings, { agingThresholdMonths: next }),
              )
              setSettings(saved)
              onSaved()
              return saved.agingThresholdMonths
            }}
          />
        )}
      </PageSection>
      {settings !== null && (
        <PageSection
          title="Sperre ohne verantwortliche Stelle"
          description="Fehlt einem Agenten oder einer Dokumentvorlage die verantwortliche Stelle länger als diese Frist, wird er oder sie gesperrt, nicht gelöscht. Die Frist beginnt, wenn die Gruppe der Stelle aufgelöst wird oder das Eigentum in „Nachfolge offen“ geht."
        >
          <FactoryDefaultNumberSetting
            key={`${settings.responsibleLockDays}-${settings.responsibleLockDaysOverridden}`}
            label="Gesperrt nach (Tage)"
            field="responsibleLockDays"
            value={settings.responsibleLockDays}
            defaultValue={settings.defaultResponsibleLockDays}
            overridden={settings.responsibleLockDaysOverridden}
            min={settings.minResponsibleLockDays}
            max={settings.maxResponsibleLockDays}
            format={daysLabel}
            confirmChange={(next) => ({
              question: `Frist bis zur Sperre auf ${daysLabel(next)} ändern?`,
              consequence:
                'Gilt ab der nächsten stündlichen Prüfung, auch für laufende Fristen. Die Änderung steht im Revisionsprotokoll.',
              danger: next < settings.responsibleLockDays,
            })}
            confirmReset={(current, factory) => ({
              danger: factory < current,
              consequence: `Agenten und Vorlagen ohne verantwortliche Stelle werden dann nach ${daysLabel(factory)} statt nach ${daysLabel(current)} gesperrt. Die Änderung steht im Revisionsprotokoll.`,
              warning: null,
            })}
            savedMessage={(days) =>
              `Agenten und Vorlagen ohne verantwortliche Stelle werden jetzt nach ${daysLabel(days)} gesperrt.`
            }
            failedMessage="Frist nicht gespeichert"
            onSave={async (next) => {
              const saved = await updateSuccessionSettings(
                requestOf(settings, { responsibleLockDays: next }),
              )
              setSettings(saved)
              return saved.responsibleLockDays
            }}
          />
        </PageSection>
      )}
    </>
  )
}
