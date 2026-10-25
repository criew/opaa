import { useCallback, useEffect, useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Link from '@mui/material/Link'
import PageSection from '@/shared/ui/PageSection'
import StateDot from '@/shared/ui/StateDot'
import SummaryRow from '../setup/SummaryRow'
import { TEXT_LINK } from '../setup/rowStyles'
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
  const [open, setOpen] = useState(false)
  const [technicalOpen, setTechnicalOpen] = useState(false)
  const stats = quality?.modelExtraction

  const model = settings?.chatModel
  const summary = !settings
    ? null
    : settings.modelExtractionEnabled
      ? [
          'an',
          settings.keywordsEnabled ? 'mit Schlagworten' : null,
          model ? `Modell ${model.modelIdentifier}` : 'kein Modell aktiv',
        ]
          .filter(Boolean)
          .join(' · ')
      : 'aus'

  return (
    <Box>
      {error && (
        <Alert severity="error" sx={{ mb: 2 }} onClose={() => setError(null)}>
          {error}
        </Alert>
      )}
      {canManage && settings && (
        <Box sx={{ mb: 4 }}>
          <SummaryRow
            id="metadata-row-extraction"
            title="Extraktion"
            summary={summary}
            open={open}
            onOpen={() => setOpen(true)}
            headingLevel="h2"
          >
            <Stack spacing={1}>
              <FormControlLabel
                control={
                  <Switch
                    checked={settings.modelExtractionEnabled}
                    disabled={saving}
                    onChange={(event) => void save(event.target.checked, settings.keywordsEnabled)}
                  />
                }
                label="Leere Felder vom Sprachmodell ergänzen"
              />
              <FormControlLabel
                control={
                  <Switch
                    checked={settings.keywordsEnabled}
                    disabled={saving}
                    onChange={(event) =>
                      void save(settings.modelExtractionEnabled, event.target.checked)
                    }
                  />
                }
                label="Bis zu fünf freie Schlagworte je Dokument"
              />
              {/* the one sentence that decides the switch: whether contents leave the house */}
              <Box sx={{ pt: 0.5 }}>
                <StateDot
                  tone={!model || local ? 'neutral' : 'warning'}
                  label={
                    !model
                      ? 'Kein Chat-Modell aktiv: die Extraktion bleibt wirkungslos.'
                      : local
                        ? `Lokales Modell ${model.modelIdentifier}: nichts verlässt das Haus.`
                        : `Eingeschaltet geht der Inhalt jedes Dokuments an ${model.baseUrl}.`
                  }
                />
              </Box>
              <Box sx={{ textAlign: 'right' }}>
                <Link
                  component="button"
                  type="button"
                  underline="hover"
                  onClick={() => setOpen(false)}
                  sx={TEXT_LINK}
                >
                  Schließen
                </Link>
              </Box>
            </Stack>
          </SummaryRow>
        </Box>
      )}

      {(quality?.totalDocuments ?? 0) > 0 && (
        <PageSection title="Füllgrad">
          <Box
            component="table"
            sx={{
              width: '100%',
              borderCollapse: 'collapse',
              tableLayout: 'fixed',
              '& th:not(:first-of-type)': { width: 88 },
              fontSize: 13,
              '& th': {
                fontWeight: 400,
                color: 'text.secondary',
                textAlign: 'right',
                fontSize: 12,
                pb: 0.5,
                borderBottom: 1,
                borderColor: 'divider',
              },
              '& th:first-of-type, & td:first-of-type': { textAlign: 'left' },
              '& td': {
                textAlign: 'right',
                py: 0.75,
                borderBottom: 1,
                borderColor: 'divider',
                fontVariantNumeric: 'tabular-nums',
              },
            }}
          >
            <thead>
              <tr>
                <th scope="col">Feld</th>
                <th scope="col">Regel</th>
                <th scope="col">Modell</th>
                <th scope="col">Hand</th>
                <th scope="col">Leer</th>
              </tr>
            </thead>
            <tbody>
              {(quality?.fields ?? []).map((field) => (
                <tr key={field.fieldKey}>
                  <td>{field.label}</td>
                  <td>{field.deterministicDocuments}</td>
                  <td>{field.derivedDocuments}</td>
                  <td>{field.manualDocuments}</td>
                  <td>
                    {field.emptyDocuments + field.notDeterminableDocuments} (
                    {formatShare(field.emptyShare)})
                  </td>
                </tr>
              ))}
            </tbody>
          </Box>
          {stats && (
            <>
              <Link
                component="button"
                type="button"
                underline="hover"
                aria-expanded={technicalOpen}
                onClick={() => setTechnicalOpen((value) => !value)}
                sx={{ ...TEXT_LINK, mt: 1 }}
              >
                Technische Angaben
              </Link>
              {technicalOpen && (
                <Typography sx={{ fontSize: 12.5, color: 'text.secondary', mt: 0.5 }}>
                  Modellaufrufe {stats.calls} · übernommen {stats.acceptedValues} · verworfen
                  (Konfidenz) {stats.rejectedBelowThreshold} · verworfen (Werteliste){' '}
                  {stats.rejectedOutsideVocabulary} · Fehler {stats.failures} · nicht angefragt{' '}
                  {stats.rejectedPoolFull} · Schlagworte {stats.keywordsAssigned}
                </Typography>
              )}
            </>
          )}
        </PageSection>
      )}
    </Box>
  )
}
