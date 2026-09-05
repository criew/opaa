import { useCallback, useEffect, useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Divider from '@mui/material/Divider'
import FormControlLabel from '@mui/material/FormControlLabel'
import Stack from '@mui/material/Stack'
import Switch from '@mui/material/Switch'
import Typography from '@mui/material/Typography'
import type {
  LibraryMetadataExtractionSettingsResponse,
  LibraryMetadataQualityResponse,
} from '../../types/api'
import {
  getLibraryMetadataExtractionSettings,
  getLibraryMetadataQuality,
  updateLibraryMetadataExtractionSettings,
} from '../../services/api'
import { formatShare } from '../../utils/labels'

interface MetadataExtractionSettingsSectionProps {
  libraryId: string
  // Only a Verwaltungsberechtigter may move the switches; everyone who may read the library sees
  // the Extraktionsgüte.
  canManage: boolean
}

// #1073 (metadata-schema.md, "Die modellgestützte Extraktion im Betrieb"): the two switches of step
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
        setError(err instanceof Error ? err.message : 'Extraktionsgüte konnte nicht geladen werden'),
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

  return (
    <Box component="section" aria-label="Modellgestützte Extraktion">
      {error && (
        <Alert severity="error" sx={{ mb: 2 }} onClose={() => setError(null)}>
          {error}
        </Alert>
      )}
      {canManage && settings && (
        <Stack spacing={1.5} sx={{ mb: 2 }}>
          <FormControlLabel
            control={
              <Switch
                checked={settings.modelExtractionEnabled}
                disabled={saving}
                onChange={(event) => void save(event.target.checked, settings.keywordsEnabled)}
                slotProps={{ input: { 'aria-label': 'Modellgestützte Extraktion' } }}
              />
            }
            label="Modellgestützte Extraktion"
          />
          <Typography variant="body2" color="text.secondary">
            Ergänzt nur die unscharfen Felder, die die regelbasierte Extraktion leer gelassen hat
            (Dokumentart, Auswahlfelder dieser Bibliothek). Ein Wert unterhalb der Konfidenzschwelle
            von {formatShare(settings.confidenceThreshold)} oder außerhalb der Werteliste bleibt
            leer.
          </Typography>
          <FormControlLabel
            control={
              <Switch
                checked={settings.keywordsEnabled}
                disabled={saving}
                onChange={(event) =>
                  void save(settings.modelExtractionEnabled, event.target.checked)
                }
                slotProps={{ input: { 'aria-label': 'Freie Schlagworte' } }}
              />
            }
            label="Freie Schlagworte"
          />
          <Typography variant="body2" color="text.secondary">
            Höchstens fünf Schlagworte je Dokument. Sie verbessern die Auffindbarkeit im Volltext,
            sind aber nie filterbar und erscheinen nicht im Beleg.
          </Typography>
          <Alert severity={local ? 'info' : 'warning'} aria-label="Datenschutzhinweis">
            {settings.chatModel ? (
              <>
                {local ? (
                  <>
                    Das aktive Chat-Modell wird lokal betrieben ({settings.chatModel.baseUrl},
                    Modell {settings.chatModel.modelIdentifier}). Die Extraktion läuft ohne
                    ausgehende Verbindung; es verlassen keine Dokumentinhalte das Haus.
                  </>
                ) : (
                  <>
                    <strong>
                      Mit eingeschalteter Extraktion verlässt der Inhalt jedes aufgenommenen
                      Dokuments dauerhaft das Haus
                    </strong>{' '}
                    — anders als im Chat ohne dass eine Person den Vorgang auslöst. Übertragen wird
                    an das aktive Chat-Modell: {settings.chatModel.baseUrl}, Modell{' '}
                    {settings.chatModel.modelIdentifier}. Für Bibliotheken mit schutzbedürftigen
                    Unterlagen ist das die datenschutzrechtlich relevante Entscheidung an diesem
                    Schalter.
                  </>
                )}
              </>
            ) : (
              <>
                Zurzeit ist kein Chat-Modell aktiv. Ohne aktives Modell bleibt die Extraktion
                wirkungslos; die Felder bleiben leer.
              </>
            )}
          </Alert>
          <Divider />
        </Stack>
      )}

      <Typography variant="subtitle2" component="h4" sx={{ mb: 0.5 }}>
        Extraktionsgüte
      </Typography>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 1.5 }}>
        Herkunft der Werte über {quality?.totalDocuments ?? 0} indizierte Dokumente dieser
        Bibliothek.
      </Typography>
      <Stack spacing={0.75}>
        {(quality?.fields ?? []).map((field) => (
          <Stack
            key={field.fieldKey}
            direction="row"
            spacing={1.5}
            sx={{ alignItems: 'center', flexWrap: 'wrap' }}
          >
            <Typography variant="body2" sx={{ minWidth: 120, color: 'text.secondary' }}>
              {field.label}
            </Typography>
            <Typography variant="body2">
              regelbasiert {field.deterministicDocuments} · modellbefüllt {field.derivedDocuments} ·
              von Hand {field.manualDocuments} · kein Wert ermittelbar{' '}
              {field.notDeterminableDocuments} · leer {field.emptyDocuments} (
              {formatShare(field.emptyShare)})
            </Typography>
          </Stack>
        ))}
      </Stack>
      {stats && (
        <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 1.5 }}>
          Modellaufrufe: {stats.calls} · übernommen: {stats.acceptedValues} · verworfen (Konfidenz):{' '}
          {stats.rejectedBelowThreshold} · verworfen (Werteliste):{' '}
          {stats.rejectedOutsideVocabulary} · Fehler/Zeitüberschreitungen: {stats.failures} ·
          Schlagworte: {stats.keywordsAssigned}
        </Typography>
      )}
    </Box>
  )
}
