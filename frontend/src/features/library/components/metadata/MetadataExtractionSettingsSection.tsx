import { useCallback, useEffect, useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import FormControlLabel from '@mui/material/FormControlLabel'
import Link from '@mui/material/Link'
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
  const enabled = Boolean(settings?.modelExtractionEnabled || settings?.keywordsEnabled)
  const total = quality?.totalDocuments ?? 0
  const qualityFields = quality?.fields ?? []
  return (
    <TabSection title="Extraktion">
      {error && (
        <Alert severity="error" sx={{ mb: 2 }} onClose={() => setError(null)}>
          {error}
        </Alert>
      )}
      {canManage && settings && (
        <Stack spacing={0.5} sx={{ mb: 3 }}>
          <Stack direction="row" spacing={3} useFlexGap sx={{ flexWrap: 'wrap' }}>
            <SwitchLine
              label="Leere Felder ergänzen"
              checked={settings.modelExtractionEnabled}
              disabled={saving}
              onChange={(next) => void save(next, settings.keywordsEnabled)}
            />
            <SwitchLine
              label="Freie Schlagworte"
              checked={settings.keywordsEnabled}
              disabled={saving}
              onChange={(next) => void save(settings.modelExtractionEnabled, next)}
            />
          </Stack>
          {/* the one sentence of the tab: where content goes; the signal only while it does */}
          <Box
            aria-label="Datenschutzhinweis"
            role="note"
            sx={{ display: 'flex', gap: 1, alignItems: 'flex-start' }}
          >
            {!local && settings.chatModel && enabled && (
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
                  ? `Lokales Modell ${settings.chatModel.modelIdentifier}: Inhalte bleiben im Haus.`
                  : `${enabled ? 'Eingeschaltet: Inhalte gehen' : 'Eingeschaltet gehen Inhalte'} an ${providerName(settings.chatModel.baseUrl)} (${settings.chatModel.modelIdentifier}).`
                : 'Kein Chat-Modell aktiv: Die Extraktion bleibt wirkungslos.'}
            </Typography>
          </Box>
        </Stack>
      )}

      {/* the fill level of the fields, the one place of it; nothing to say without documents */}
      {total > 0 && qualityFields.length > 0 && (
        <>
          <Typography component="h3" sx={{ fontSize: 13.5, fontWeight: 600, mb: 0.5 }}>
            Füllgrad · {plural(total, 'Dokument', 'Dokumente').replace(/^\d+/, formatCount(total))}
          </Typography>
          <Box component="ul" sx={{ m: 0, p: 0, listStyle: 'none' }}>
            {qualityFields.map((field) => (
              <Box
                component="li"
                key={field.fieldKey}
                sx={{ py: 0.75, borderBottom: 1, borderColor: 'divider' }}
              >
                <Typography sx={{ fontSize: 13.5 }}>
                  {field.label}
                  <Box component="span" sx={{ color: 'text.secondary' }}>
                    {` · ${formatCount(field.emptyDocuments)} leer (${formatShare(field.emptyShare)})`}
                  </Box>
                </Typography>
                <Typography sx={{ fontSize: 12, color: 'text.secondary' }}>
                  {`regelbasiert ${formatCount(field.deterministicDocuments)} · Modell ${formatCount(field.derivedDocuments)} · von Hand ${formatCount(field.manualDocuments)} · nicht ermittelbar ${formatCount(field.notDeterminableDocuments)}`}
                </Typography>
              </Box>
            ))}
          </Box>
        </>
      )}
      {stats && total > 0 && <TechnicalFigures stats={stats} />}
    </TabSection>
  )
}

function formatCount(n: number): string {
  return n.toLocaleString('de-DE')
}

/** The provider a hosted model belongs to, by its address; the host where none is known. */
function providerName(baseUrl: string): string {
  let host = baseUrl
  try {
    host = new URL(baseUrl).hostname
  } catch {
    // not a URL: shown as given
  }
  if (/(^|\.)openai\.com$/.test(host)) return 'OpenAI'
  if (/(^|\.)anthropic\.com$/.test(host)) return 'Anthropic'
  if (/(^|\.)azure\.com$/.test(host)) return 'Azure OpenAI'
  if (/(^|\.)mistral\.ai$/.test(host)) return 'Mistral'
  return host
}

/** The counters of the model calls, folded away like the technical figures of a run. */
function TechnicalFigures({
  stats,
}: {
  stats: NonNullable<LibraryMetadataQualityResponse['modelExtraction']>
}) {
  const [open, setOpen] = useState(false)
  return (
    <Box sx={{ mt: 1.5 }}>
      <Link
        component="button"
        type="button"
        underline="hover"
        aria-expanded={open}
        onClick={() => setOpen((value) => !value)}
        sx={{ fontSize: 13 }}
      >
        Technische Angaben
      </Link>
      {open && (
        <Typography sx={{ fontSize: 12.5, color: 'text.secondary', mt: 0.5 }}>
          Modellaufrufe: {stats.calls} · übernommen: {stats.acceptedValues} · verworfen (Konfidenz):{' '}
          {stats.rejectedBelowThreshold} · verworfen (Werteliste): {stats.rejectedOutsideVocabulary}{' '}
          · Fehler/Zeitüberschreitungen: {stats.failures} · nicht angefragt (ausgelastet):{' '}
          {stats.rejectedPoolFull} · Schlagworte: {stats.keywordsAssigned}
        </Typography>
      )}
    </Box>
  )
}

/** A switch with its label; what it means is said once, in the sentence below. */
function SwitchLine({
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
  )
}
