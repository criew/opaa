import { useCallback, useEffect, useState } from 'react'
import { useSearchParams } from 'react-router'
import DetailSection, { TextAction } from '@/shared/ui/DetailSection'
import { confirmAction } from '@/shared/stores/confirmStore'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Stack from '@mui/material/Stack'
import Switch from '@mui/material/Switch'
import Typography from '@mui/material/Typography'
import type {
  LibraryMetadataExtractionSettingsResponse,
  LibraryMetadataQualityResponse,
} from '@/shared/api/types'
import {
  getLibraryMetadataExtractionSettings,
  getLibraryMetadataQuality,
  updateLibraryMetadataExtractionSettings,
} from '../../api/libraryMetadataApi'

interface MetadataExtractionSettingsSectionProps {
  libraryId: string
  // Only a Verwaltungsberechtigter may move the switches; everyone who may read the library sees
  // the Extraktionsgüte.
  canManage: boolean
}

// metadata-schema.md, "Die modellgestützte Extraktion im Betrieb": the two switches of step
// 2, both off by default, with the Datenschutzhinweis that names the Abfluss, plus the
// Extraktionsgüte the Handauswertung is measured against.
export default function MetadataExtractionSettingsSection({
  libraryId,
  canManage,
}: MetadataExtractionSettingsSectionProps) {
  const [settings, setSettings] = useState<LibraryMetadataExtractionSettingsResponse | null>(null)
  const [quality, setQuality] = useState<LibraryMetadataQualityResponse | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)
  const [showStats, setShowStats] = useState(false)

  const loadQuality = useCallback(() => {
    getLibraryMetadataQuality(libraryId)
      .then(setQuality)
      .catch((err: unknown) =>
        setError(
          err instanceof Error ? err.message : 'Extraktionsgüte konnte nicht geladen werden',
        ),
      )
  }, [libraryId])

  useEffect(() => {
    if (canManage) {
      getLibraryMetadataExtractionSettings(libraryId)
        .then(setSettings)
        .catch((err: unknown) =>
          setError(
            err instanceof Error ? err.message : 'Einstellungen konnten nicht geladen werden',
          ),
        )
    }
    loadQuality()
  }, [libraryId, canManage, loadQuality])

  const save = async (modelExtractionEnabled: boolean, keywordsEnabled: boolean) => {
    setSaving(true)
    try {
      const updated = await updateLibraryMetadataExtractionSettings(libraryId, {
        modelExtractionEnabled,
        keywordsEnabled,
      })
      setSettings(updated)
      setError(null)
      loadQuality()
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : 'Einstellung konnte nicht gespeichert werden')
    } finally {
      setSaving(false)
    }
  }

  const stats = quality?.modelExtraction
  const model = settings?.chatModel ?? null

  /**
   * Switching on sends documents to the model: where they go is asked once, at the switch, not
   * stated on the page all the time (R1-59). A local model needs no question.
   */
  async function switchTo(modelExtractionEnabled: boolean, keywordsEnabled: boolean) {
    const turningOn =
      (modelExtractionEnabled && !settings?.modelExtractionEnabled) ||
      (keywordsEnabled && !settings?.keywordsEnabled)
    if (turningOn && model && !model.local) {
      const ok = await confirmAction({
        question: 'Extraktion einschalten?',
        consequence: `Der Inhalt jedes neuen Dokuments geht dann an ${providerOf(model.baseUrl)}.`,
        confirmLabel: 'Einschalten',
        tone: 'caution',
      })
      if (!ok) return
    }
    await save(modelExtractionEnabled, keywordsEnabled)
  }

  return (
    <DetailSection title="Extraktion">
      {error && (
        <Alert severity="error" sx={{ mb: 2 }} onClose={() => setError(null)}>
          {error}
        </Alert>
      )}
      {canManage && settings && (
        <Stack sx={{ borderTop: 1, borderColor: 'divider' }}>
          <SwitchRow
            label="Modellgestützte Extraktion"
            checked={settings.modelExtractionEnabled}
            disabled={saving}
            onChange={(next) => void switchTo(next, settings.keywordsEnabled)}
          />
          <SwitchRow
            label="Freie Schlagworte"
            checked={settings.keywordsEnabled}
            disabled={saving}
            onChange={(next) => void switchTo(settings.modelExtractionEnabled, next)}
          />
        </Stack>
      )}
      {canManage && settings && !model && (
        <Typography sx={{ mt: 1, fontSize: 13, color: 'text.secondary' }}>
          Kein Chat-Modell aktiv: Die Extraktion bleibt wirkungslos.
        </Typography>
      )}
      {stats && (
        <Box sx={{ mt: 1 }}>
          <TextAction onClick={() => setShowStats((v) => !v)} aria-expanded={showStats}>
            Technische Angaben
          </TextAction>
          {showStats && (
            <Typography sx={{ fontSize: 12.5, color: 'text.secondary', mt: 0.5 }}>
              {model && `Modell ${model.modelIdentifier} über ${model.baseUrl} · `}
              Modellaufrufe {stats.calls} · übernommen {stats.acceptedValues} · verworfen
              (Konfidenz) {stats.rejectedBelowThreshold} · verworfen (Werteliste){' '}
              {stats.rejectedOutsideVocabulary} · Fehler {stats.failures} · nicht angefragt{' '}
              {stats.rejectedPoolFull} · Schlagworte {stats.keywordsAssigned}
            </Typography>
          )}
        </Box>
      )}
    </DetailSection>
  )
}

/** „120 von 214 gefüllt (56 %)“ - said positively, the share only where it is partial. */
function fillLabel(filled: number, total: number): string {
  const count = (value: number) => value.toLocaleString('de-DE')
  if (filled === total) return `alle ${count(total)} gefüllt`
  if (filled === 0) return `0 von ${count(total)} gefüllt`
  return `${count(filled)} von ${count(total)} gefüllt (${Math.round((filled / total) * 100)} %)`
}

/** The host of a model address, the way a person recognises the provider. */
function providerOf(baseUrl: string): string {
  try {
    return new URL(baseUrl).host
  } catch {
    return baseUrl
  }
}

/** One switch as a list row: its name, the switch on the shared right edge. */
function SwitchRow({
  label,
  checked,
  disabled,
  onChange,
}: {
  label: string
  checked: boolean
  disabled: boolean
  onChange: (next: boolean) => void
}) {
  return (
    <Box
      sx={{
        display: 'flex',
        alignItems: 'center',
        gap: 2,
        py: 1,
        borderBottom: 1,
        borderColor: 'divider',
      }}
    >
      <Typography sx={{ flexGrow: 1, minWidth: 0, fontSize: 14, fontWeight: 600 }}>
        {label}
      </Typography>
      <Switch
        edge="end"
        checked={checked}
        disabled={disabled}
        onChange={(event) => onChange(event.target.checked)}
        slotProps={{ input: { 'aria-label': label } }}
      />
    </Box>
  )
}

/**
 * How full the fields of the library are - the one place for it (D22): per field the empty
 * documents and a jump into the documents filtered to them; where the values come from stands in
 * the row's tooltip.
 */
export function MetadataFillSection({ libraryId }: { libraryId: string }) {
  const [quality, setQuality] = useState<LibraryMetadataQualityResponse | null>(null)
  const [, setSearchParams] = useSearchParams()
  useEffect(() => {
    let cancelled = false
    getLibraryMetadataQuality(libraryId)
      .then((response) => {
        if (!cancelled) setQuality(response)
      })
      .catch(() => {})
    return () => {
      cancelled = true
    }
  }, [libraryId])
  if (!quality || quality.totalDocuments === 0) return null
  return (
    <DetailSection title="Füllgrad">
      <Box
        component="ul"
        sx={{ m: 0, p: 0, listStyle: 'none', borderTop: 1, borderColor: 'divider' }}
      >
        {quality.fields.map((field) => (
          <Box
            component="li"
            key={field.fieldKey}
            title={`regelbasiert ${field.deterministicDocuments} · modellbefüllt ${field.derivedDocuments} · von Hand ${field.manualDocuments} · nicht ermittelbar ${field.notDeterminableDocuments}`}
            // the grid of the rows of the Reiter „Quelle“: title | content | action
            sx={{
              display: 'grid',
              gridTemplateColumns: { xs: 'minmax(0, 1fr) auto', sm: '128px minmax(0, 1fr) auto' },
              columnGap: 2,
              alignItems: 'baseline',
              py: 1,
              borderBottom: 1,
              borderColor: 'divider',
              fontSize: 13.5,
            }}
          >
            <Box
              component="span"
              sx={{
                fontWeight: 600,
                fontSize: 15,
                minWidth: 0,
                hyphens: 'auto',
                overflowWrap: 'break-word',
              }}
            >
              {field.label}
            </Box>
            <Box component="span" sx={{ gridRow: { xs: 2, sm: 1 }, gridColumn: { xs: 1, sm: 2 } }}>
              {fillLabel(field.totalDocuments - field.emptyDocuments, field.totalDocuments)}
            </Box>
            {field.emptyDocuments > 0 ? (
              <TextAction
                onClick={() => setSearchParams({ missingField: field.fieldKey }, { replace: true })}
                context={`Dokumente ohne ${field.label}`}
              >
                Anzeigen
              </TextAction>
            ) : (
              <span />
            )}
          </Box>
        ))}
      </Box>
    </DetailSection>
  )
}
