import TextField from '@mui/material/TextField'
import {
  isVocabularyTooLong,
  TRANSCRIPTION_TEXTS,
  VOCABULARY_MAX_LENGTH,
} from './transcriptionSettings'

interface VocabularyFieldProps {
  id: string
  value: string
  onChange: (value: string) => void
  /** A field error of the server; replaces the hint. */
  error?: string
  /** A sentence after the general hint, for where the list applies. */
  hint?: string
  disabled?: boolean
}

/**
 * The vocabulary list of speech input, as the administration and every person edit it: a
 * multi-line field with a character counter against {@link VOCABULARY_MAX_LENGTH}.
 */
export default function VocabularyField({
  id,
  value,
  onChange,
  error,
  hint,
  disabled,
}: VocabularyFieldProps) {
  const over = isVocabularyTooLong(value)
  const counter = `${value.trim().length} / ${VOCABULARY_MAX_LENGTH} Zeichen`
  const helper =
    error ??
    (over
      ? `Höchstens ${VOCABULARY_MAX_LENGTH} Zeichen. ${counter}`
      : `${TRANSCRIPTION_TEXTS.vocabularyHint}${hint ? ` ${hint}` : ''} ${counter}`)
  return (
    <TextField
      id={id}
      label={TRANSCRIPTION_TEXTS.vocabularyLabel}
      value={value}
      onChange={(e) => onChange(e.target.value)}
      error={Boolean(error) || over}
      helperText={helper}
      placeholder="Einspruchsfrist, Grunderwerbsteuer, AO, BZSt"
      multiline
      minRows={2}
      maxRows={6}
      size="small"
      fullWidth
      disabled={disabled}
    />
  )
}
