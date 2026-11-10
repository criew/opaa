import { useEffect, useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Typography from '@mui/material/Typography'
import { apiFieldErrors } from '@/shared/api/apiErrorDetails'
import { notify } from '@/shared/stores/notificationStore'
import BusyButton from '@/shared/ui/a11y/BusyButton'
import SectionHead from '@/shared/ui/SectionHead'
import { useFeatureEnabled } from '@/shared/session/useFeatureEnabled'
import { isVocabularyTooLong } from '@/shared/ui/transcription/transcriptionSettings'
import VocabularyField from '@/shared/ui/transcription/VocabularyField'
import {
  getMyTranscriptionVocabulary,
  updateMyTranscriptionVocabulary,
} from '../api/transcriptionApi'
import { fontSize } from '@/shared/theme/tokens'

/**
 * The person's own vocabulary list for dictation, in „Ihre Einstellungen“. Shown whenever the
 * operation offers speech input, also while the endpoint is down, so the person can always see and
 * clear the list; it is hers alone and goes with every dictation after the installation's list.
 */
export default function TranscriptionVocabularySection() {
  const featureEnabled = useFeatureEnabled('speechInput')
  if (!featureEnabled) return null
  return <VocabularyForm />
}

function VocabularyForm() {
  const [stored, setStored] = useState<string | null>(null)
  const [value, setValue] = useState('')
  const [loadError, setLoadError] = useState<string | null>(null)
  const [fieldError, setFieldError] = useState<string | undefined>()
  const [saving, setSaving] = useState(false)

  useEffect(() => {
    let current = true
    getMyTranscriptionVocabulary()
      .then((vocabulary) => {
        if (!current) return
        setStored(vocabulary)
        setValue(vocabulary)
      })
      .catch((err: unknown) => {
        if (current) setLoadError(err instanceof Error ? err.message : 'Laden fehlgeschlagen')
      })
    return () => {
      current = false
    }
  }, [])

  async function handleSave() {
    setSaving(true)
    setFieldError(undefined)
    try {
      const saved = await updateMyTranscriptionVocabulary(value.trim())
      setStored(saved)
      setValue(saved)
      notify('Ihre Begriffsliste wurde gespeichert.', 'success')
    } catch (err) {
      const message = apiFieldErrors(err)[0]?.message
      setFieldError(message ?? (err instanceof Error ? err.message : 'Speichern fehlgeschlagen'))
    } finally {
      setSaving(false)
    }
  }

  return (
    <Box component="section" aria-labelledby="settings-transcription-head" sx={{ mt: 5 }}>
      <SectionHead id="settings-transcription-head">Spracheingabe</SectionHead>
      <Typography sx={{ fontSize: fontSize.text, color: 'text.secondary', mb: 2 }}>
        Begriffe aus Ihrer Arbeit, etwa Namen, Aktenzeichen oder Abkürzungen, erkennt die
        Spracheingabe mit dieser Liste besser. Nur Sie sehen sie.
      </Typography>
      {loadError ? (
        <Alert severity="error">{loadError}</Alert>
      ) : (
        <>
          <VocabularyField
            id="settings-transcription-vocabulary"
            value={value}
            onChange={setValue}
            error={fieldError}
            disabled={stored === null}
          />
          <Box sx={{ mt: 1.5 }}>
            <BusyButton
              variant="outlined"
              busy={saving}
              busyAnnouncement="Wird gespeichert"
              disabled={stored === null || value.trim() === stored || isVocabularyTooLong(value)}
              onClick={() => void handleSave()}
            >
              Speichern
            </BusyButton>
          </Box>
        </>
      )}
    </Box>
  )
}
