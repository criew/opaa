import { useCallback, useEffect, useState } from 'react'
import { useSearchParams } from 'react-router'
import DetailSection, { TextAction } from '@/shared/ui/DetailSection'
import { CORE_METADATA_FIELDS } from './metadataValues'
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
import { formatShare } from '@/shared/utils/labels'

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
            detail={`ergänzt leere Auswahlfelder ab ${formatShare(settings.confidenceThreshold)} Konfidenz`}
            checked={settings.modelExtractionEnabled}
            disabled={saving}
            onChange={(next) => void save(next, settings.keywordsEnabled)}
          />
          <SwitchRow
            label="Freie Schlagworte"
            detail="höchstens fünf je Dokument, nur für den Volltext"
            checked={settings.keywordsEnabled}
            disabled={saving}
            onChange={(next) => void save(settings.modelExtractionEnabled, next)}
          />
        </Stack>
      )}
      {canManage && settings && model && !model.local && (
        // the one sentence that matters at these switches: where the documents go
        <Typography
          aria-label="Datenschutzhinweis"
          sx={{ mt: 1.5, pl: 1.5, borderLeft: 3, borderColor: 'warning.main', fontSize: 13 }}
        >
          Eingeschaltet gehen die Inhalte jedes neuen Dokuments an {model.baseUrl} (
          {model.modelIdentifier}).
        </Typography>
      )}
      {canManage && settings && !model && (
        <Typography sx={{ mt: 1.5, fontSize: 13, color: 'text.secondary' }}>
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

/** One switch as a list row: name and a short line on what it does, the switch at the edge. */
function SwitchRow({
  label,
  detail,
  checked,
  disabled,
  onChange,
}: {
  label: string
  detail: string
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
      <Box sx={{ flexGrow: 1, minWidth: 0 }}>
        <Typography sx={{ fontSize: 14, fontWeight: 600 }}>{label}</Typography>
        <Typography sx={{ fontSize: 12.5, color: 'text.secondary' }}>{detail}</Typography>
      </Box>
      <Switch
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
  const coreKeys = new Set(CORE_METADATA_FIELDS.map((field) => field.key))
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
            sx={{
              display: 'grid',
              gridTemplateColumns: { xs: '1fr auto', sm: '160px 1fr auto' },
              columnGap: 2,
              alignItems: 'baseline',
              py: 0.75,
              borderBottom: 1,
              borderColor: 'divider',
              fontSize: 13.5,
            }}
          >
            <Box component="span" sx={{ fontWeight: 500 }}>
              {field.label}
            </Box>
            <Box
              component="span"
              sx={{
                color: 'text.secondary',
                gridRow: { xs: 2, sm: 1 },
                gridColumn: { xs: 1, sm: 2 },
              }}
            >
              {field.emptyDocuments === 0
                ? 'bei allen Dokumenten gesetzt'
                : `${field.emptyDocuments.toLocaleString('de-DE')} ohne Wert (${formatShare(field.emptyShare)})`}
            </Box>
            {field.emptyDocuments > 0 && coreKeys.has(field.fieldKey) ? (
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
