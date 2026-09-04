import { useEffect, useState } from 'react'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import MenuItem from '@mui/material/MenuItem'
import Stack from '@mui/material/Stack'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'

import type {
  DocumentTypeVocabularyEntryResponse,
  MetadataFilter,
  SearchDiagnosisContextType,
  SearchDiagnosisRequest,
  SearchPermissionProfileResponse,
} from '../../types/api'
import { getDocumentTypeVocabulary } from '../../services/api'
import { plural, UUID_PATTERN } from './format'

const OWN_CONTEXT_VALUE = 'SELF'
const PERSON_CONTEXT_VALUE = 'USER'

/**
 * Why an installation without groups sees no profiles. A permission profile is a group with the
 * libraries it may read; none is derived from grant patterns, so the list stays empty until groups
 * exist (#1150).
 */
const NO_PROFILES_EXPLANATION =
  'Es gibt keine Rechteprofile, weil keine Gruppen angelegt sind: Ein Rechteprofil ist eine Gruppe zusammen mit den Bibliotheken, die sie lesen darf. Wo Lesbarkeit nur über einzelne Berechtigungen vergeben wird, entsteht keines. Bis dahin bleiben der eigene Rechtekontext und - mit Befugnis - der Rechtekontext einer Person.'

/**
 * The diagnosis form owns every keyboard input of the page, so a keystroke re-renders this subtree
 * alone and no other part of the page needs memoization to stay still.
 */
export default function DiagnosisForm({
  profiles,
  personContextAvailable,
  personContextHint,
  running,
  onRunDiagnosis,
}: {
  profiles: SearchPermissionProfileResponse[]
  personContextAvailable: boolean
  personContextHint: string
  running: boolean
  onRunDiagnosis: (request: SearchDiagnosisRequest) => Promise<void>
}) {
  const [question, setQuestion] = useState('')
  const [contextChoice, setContextChoice] = useState<string | null>(null)
  const [targetUserId, setTargetUserId] = useState('')
  const [justification, setJustification] = useState('')
  const [trackedDocumentId, setTrackedDocumentId] = useState('')
  // #1070: the core-field filter the diagnosis runs with - applied exactly as a chat query applies
  // it, so "Sicht als" can check what a person's filtered question sees. The Dokumentart choices
  // come from the vocabulary (schema, not an aggregate), since the diagnosis may run in a foreign
  // rights context whose occurring values this page must not aggregate.
  const [filterDocumentTypes, setFilterDocumentTypes] = useState<string[]>([])
  const [filterDateFrom, setFilterDateFrom] = useState('')
  const [filterDateTo, setFilterDateTo] = useState('')
  const [vocabulary, setVocabulary] = useState<DocumentTypeVocabularyEntryResponse[]>([])

  useEffect(() => {
    let cancelled = false
    getDocumentTypeVocabulary()
      .then((response) => {
        if (!cancelled) setVocabulary(response.items)
      })
      .catch(() => {
        // The filter field then offers no Dokumentart; the diagnosis itself is unaffected.
      })
    return () => {
      cancelled = true
    }
  }, [])

  const filterDateInvalid =
    filterDateFrom !== '' && filterDateTo !== '' && filterDateTo < filterDateFrom

  // Derived rather than set from an effect once the profiles arrive: the preselected context is a
  // permission profile wherever one exists, the caller's own context otherwise - never a person
  // (Berechtigungs-Leitplanke (d)).
  const contextValue = contextChoice ?? (profiles.length > 0 ? profiles[0].id : OWN_CONTEXT_VALUE)
  const isPersonContext = contextValue === PERSON_CONTEXT_VALUE
  const targetUserIdInvalid = targetUserId.trim() !== '' && !UUID_PATTERN.test(targetUserId.trim())
  const personContextIncomplete =
    isPersonContext &&
    (targetUserId.trim() === '' || targetUserIdInvalid || justification.trim() === '')

  async function handleDiagnosis() {
    const contextType: SearchDiagnosisContextType =
      contextValue === OWN_CONTEXT_VALUE ? 'SELF' : isPersonContext ? 'USER' : 'PERMISSION_PROFILE'
    const metadataFilter: MetadataFilter = {
      ...(filterDocumentTypes.length > 0 ? { documentTypes: filterDocumentTypes } : {}),
      ...(filterDateFrom !== '' ? { documentDateFrom: filterDateFrom } : {}),
      ...(filterDateTo !== '' ? { documentDateTo: filterDateTo } : {}),
    }
    await onRunDiagnosis({
      question: question.trim(),
      contextType,
      permissionProfileId: contextType === 'PERMISSION_PROFILE' ? contextValue : undefined,
      targetUserId: contextType === 'USER' ? targetUserId.trim() : undefined,
      justification: contextType === 'USER' ? justification.trim() : undefined,
      trackedDocumentId: trackedDocumentId.trim() === '' ? undefined : trackedDocumentId.trim(),
      ...(Object.keys(metadataFilter).length > 0 ? { metadataFilter } : {}),
    })
  }

  return (
    <Stack spacing={2} sx={{ maxWidth: 720 }}>
      <TextField
        label="Testfrage"
        required
        value={question}
        onChange={(e) => setQuestion(e.target.value)}
        size="small"
        fullWidth
      />
      <TextField
        select
        label="Sicht als"
        value={contextValue}
        onChange={(e) => setContextChoice(e.target.value)}
        helperText={
          profiles.length === 0
            ? NO_PROFILES_EXPLANATION
            : 'Voreingestellt ist ein Rechteprofil. Der Rechtekontext einer Person ist die Ausnahme: Er verlangt eine eigene Befugnis und eine Begründung und wird protokolliert.'
        }
        size="small"
        fullWidth
      >
        <MenuItem value={OWN_CONTEXT_VALUE}>Eigener Rechtekontext</MenuItem>
        {profiles.map((profile) => (
          <MenuItem key={profile.id} value={profile.id}>
            {`Rechteprofil „${profile.name}“ (${plural(profile.libraryCount, 'Bibliothek', 'Bibliotheken')})`}
          </MenuItem>
        ))}
        <MenuItem value={PERSON_CONTEXT_VALUE} disabled={!personContextAvailable}>
          Rechtekontext einer Person
        </MenuItem>
      </TextField>
      {personContextHint !== '' && (
        <Typography variant="body2" color="text.secondary">
          {personContextHint}
        </Typography>
      )}
      {isPersonContext && (
        <>
          <TextField
            label="Nutzer-UUID der Person"
            required
            value={targetUserId}
            onChange={(e) => setTargetUserId(e.target.value)}
            error={targetUserIdInvalid}
            helperText={
              targetUserIdInvalid
                ? 'Erwartet wird die UUID der Person, nicht ihre Anmeldekennung - etwa 3f2b1c8e-0a4d-4c7b-9f61-2d8e5a7c4b10.'
                : 'UUID der Person, deren Rechtekontext eingenommen wird. Die Diagnose liest keine Gespräche dieser Person - sie nutzt allein ihre Leserechte.'
            }
            size="small"
            fullWidth
          />
          <TextField
            label="Begründung"
            required
            multiline
            minRows={2}
            value={justification}
            onChange={(e) => setJustification(e.target.value)}
            helperText="Pflichtangabe. Sie wird im Protokolleintrag dieses Laufs mitgeführt; ohne sie wird nicht ausgeführt."
            size="small"
            fullWidth
          />
        </>
      )}
      <TextField
        label="Dokument verfolgen (optional)"
        value={trackedDocumentId}
        onChange={(e) => setTrackedDocumentId(e.target.value)}
        helperText="Kennung eines Dokuments aus der Dokumentliste einer Bibliothek. Die Diagnose sagt dann, ob es gar nicht gefunden oder in einer bestimmten Stufe verdrängt wurde."
        size="small"
        fullWidth
      />
      <TextField
        select
        label="Metadatenfilter: Dokumentart (optional)"
        value={filterDocumentTypes}
        onChange={(e) => {
          const value = e.target.value as unknown
          setFilterDocumentTypes(Array.isArray(value) ? (value as string[]) : [])
        }}
        helperText="Der Filter wirkt wie im Chat: in beiden Suchpfaden, unter dem Rechtefilter; ein Dokument ohne Angabe bleibt enthalten."
        size="small"
        fullWidth
        slotProps={{
          select: {
            multiple: true,
            renderValue: (selected) =>
              (selected as string[])
                .map((code) => vocabulary.find((entry) => entry.code === code)?.label ?? code)
                .join(', '),
          },
        }}
      >
        {vocabulary.map((entry) => (
          <MenuItem key={entry.code} value={entry.code}>
            {entry.label}
          </MenuItem>
        ))}
      </TextField>
      <Stack direction="row" spacing={2}>
        <TextField
          label="Metadatenfilter: Datum von"
          type="date"
          value={filterDateFrom}
          onChange={(e) => setFilterDateFrom(e.target.value)}
          size="small"
          fullWidth
          slotProps={{ inputLabel: { shrink: true } }}
        />
        <TextField
          label="Metadatenfilter: Datum bis"
          type="date"
          value={filterDateTo}
          onChange={(e) => setFilterDateTo(e.target.value)}
          error={filterDateInvalid}
          helperText={filterDateInvalid ? 'Das Fenster endet vor seinem Beginn.' : undefined}
          size="small"
          fullWidth
          slotProps={{ inputLabel: { shrink: true } }}
        />
      </Stack>
      <Box>
        <Button
          variant="contained"
          size="small"
          onClick={() => void handleDiagnosis()}
          disabled={
            running || question.trim() === '' || personContextIncomplete || filterDateInvalid
          }
        >
          {running ? 'Diagnose läuft …' : 'Diagnose ausführen'}
        </Button>
      </Box>
    </Stack>
  )
}
