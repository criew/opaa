import { useCallback, useEffect, useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import FormControlLabel from '@mui/material/FormControlLabel'
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
import { plural } from '@/shared/utils/format'
import TabSection from '@/shared/ui/TabSection'

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

  const local = settings?.chatModel?.local === true
  const stats = quality?.modelExtraction

  const qualityFields = quality?.fields ?? []
  return (
    <TabSection title="Modellgestützte Extraktion">
      {error && (
        <Alert severity="error" sx={{ mb: 2 }} onClose={() => setError(null)}>
          {error}
        </Alert>
      )}
      {canManage && settings && (
        <Stack spacing={0.5} sx={{ mb: 3 }}>
          <SwitchLine
            label="Modellgestützte Extraktion"
            checked={settings.modelExtractionEnabled}
            disabled={saving}
            onChange={(next) => void save(next, settings.keywordsEnabled)}
            hint={`Füllt leer gebliebene Auswahlfelder ab ${formatShare(settings.confidenceThreshold)} Konfidenz.`}
          />
          <SwitchLine
            label="Freie Schlagworte"
            checked={settings.keywordsEnabled}
            disabled={saving}
            onChange={(next) => void save(settings.modelExtractionEnabled, next)}
            hint="Höchstens fünf je Dokument, nicht filterbar."
          />
          {/* the one consequence of the switch that matters beyond this page: where content goes */}
          <Box
            aria-label="Datenschutzhinweis"
            role="note"
            sx={{ display: 'flex', gap: 1, alignItems: 'flex-start', pt: 0.5 }}
          >
            {!local && settings.chatModel && (
              <Box
                aria-hidden
                sx={{
                  width: 8,
                  height: 8,
                  mt: '6px',
                  borderRadius: '50%',
                  flex: 'none',
                  bgcolor: 'warning.main',
                }}
              />
            )}
            <Typography sx={{ fontSize: 12.5, color: 'text.secondary' }}>
              {settings.chatModel
                ? local
                  ? `Lokales Modell ${settings.chatModel.modelIdentifier}: Es verlassen keine Dokumentinhalte das Haus.`
                  : `Mit eingeschalteter Extraktion verlässt der Inhalt jedes aufgenommenen Dokuments dauerhaft das Haus – an ${settings.chatModel.baseUrl} (${settings.chatModel.modelIdentifier}).`
                : 'Kein Chat-Modell aktiv: Die Extraktion bleibt wirkungslos.'}
            </Typography>
          </Box>
        </Stack>
      )}

      <Typography component="h3" sx={{ fontSize: 13.5, fontWeight: 600, mb: 1 }}>
        Extraktionsgüte · {plural(quality?.totalDocuments ?? 0, 'Dokument', 'Dokumente')}
      </Typography>
      {qualityFields.length > 0 && (
        <Box sx={{ overflowX: 'auto' }}>
          <Box
            component="table"
            sx={{
              borderCollapse: 'collapse',
              width: '100%',
              fontSize: 13,
              '& th': {
                fontSize: 12,
                fontWeight: 500,
                color: 'text.secondary',
                textAlign: 'right',
                pb: 0.5,
                borderBottom: 1,
                borderColor: 'divider',
              },
              '& td': {
                textAlign: 'right',
                py: 0.75,
                borderBottom: 1,
                borderColor: 'divider',
                fontVariantNumeric: 'tabular-nums',
              },
              '& th:first-of-type, & td:first-of-type': { textAlign: 'left' },
            }}
          >
            <thead>
              <tr>
                <th scope="col">Feld</th>
                <th scope="col">regelbasiert</th>
                <th scope="col">Modell</th>
                <th scope="col">von Hand</th>
                <th scope="col">nicht ermittelbar</th>
                <th scope="col">leer</th>
              </tr>
            </thead>
            <tbody>
              {qualityFields.map((field) => (
                <tr key={field.fieldKey}>
                  <td>{field.label}</td>
                  <td>{field.deterministicDocuments}</td>
                  <td>{field.derivedDocuments}</td>
                  <td>{field.manualDocuments}</td>
                  <td>{field.notDeterminableDocuments}</td>
                  <td>
                    {field.emptyDocuments} ({formatShare(field.emptyShare)})
                  </td>
                </tr>
              ))}
            </tbody>
          </Box>
        </Box>
      )}
      {stats && (
        <Box component="details" sx={{ mt: 1.5, fontSize: 12.5, color: 'text.secondary' }}>
          <Box component="summary" sx={{ cursor: 'pointer', color: 'text.primary' }}>
            Technische Angaben
          </Box>
          <Typography sx={{ fontSize: 12.5, color: 'text.secondary', mt: 0.5 }}>
            Modellaufrufe: {stats.calls} · übernommen: {stats.acceptedValues} · verworfen
            (Konfidenz): {stats.rejectedBelowThreshold} · verworfen (Werteliste):{' '}
            {stats.rejectedOutsideVocabulary} · Fehler/Zeitüberschreitungen: {stats.failures} ·
            nicht angefragt (ausgelastet): {stats.rejectedPoolFull} · Schlagworte:{' '}
            {stats.keywordsAssigned}
          </Typography>
        </Box>
      )}
    </TabSection>
  )
}

/** A switch with its one-line consequence beside the label. */
function SwitchLine({
  label,
  checked,
  disabled,
  onChange,
  hint,
}: {
  label: string
  checked: boolean
  disabled: boolean
  onChange: (next: boolean) => void
  hint: string
}) {
  return (
    <Box sx={{ display: 'flex', alignItems: 'center', flexWrap: 'wrap', columnGap: 1 }}>
      <FormControlLabel
        control={
          <Switch
            checked={checked}
            disabled={disabled}
            onChange={(event) => onChange(event.target.checked)}
            slotProps={{ input: { 'aria-label': label } }}
          />
        }
        label={label}
        sx={{ mr: 0 }}
      />
      <Typography sx={{ fontSize: 12.5, color: 'text.secondary' }}>{hint}</Typography>
    </Box>
  )
}
