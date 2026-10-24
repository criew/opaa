import { useEffect, useRef, useState, type ReactNode } from 'react'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import FieldLabel from '@/shared/ui/wizard/FieldLabel'
import { fontFamily } from '@/shared/theme/tokens'
import { fieldDomId, useSourceIssue } from '../sources/sourceIssueContext'

/**
 * One group of a source form - Verbindung, Anmeldung, Umfang, Erweitert -, the same heading and
 * spacing for every connector.
 */
export function FieldGroup({ title, children }: { title: string; children: ReactNode }) {
  return (
    <Box component="section" sx={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
      <Typography component="h3" sx={{ fontSize: 16, fontWeight: 600 }}>
        {title}
      </Typography>
      {children}
    </Box>
  )
}

interface SourceTextFieldProps {
  idPrefix: string
  /** The form value; also the key its issue is reported under. */
  field: string
  label: string
  value: string
  onChange: (value: string) => void
  onBlur?: () => void
  placeholder?: string
  hint?: ReactNode
  optional?: boolean
  mono?: boolean
  multiline?: boolean
  readOnly?: boolean
  maxLength?: number
  autoComplete?: string
  type?: string
}

/** A labelled text field of a source form, showing the issue reported for its value. */
export function SourceTextField({
  idPrefix,
  field,
  label,
  value,
  onChange,
  onBlur,
  placeholder,
  hint,
  optional,
  mono,
  multiline,
  readOnly,
  maxLength,
  autoComplete = 'off',
  type,
}: SourceTextFieldProps) {
  const issue = useSourceIssue()(field)
  const id = fieldDomId(idPrefix, field)
  return (
    <Box>
      <FieldLabel htmlFor={id}>{optional ? `${label} (optional)` : label}</FieldLabel>
      <TextField
        id={id}
        size="small"
        fullWidth
        value={value}
        type={type}
        multiline={multiline}
        minRows={multiline ? 2 : undefined}
        onChange={(e) => onChange(e.target.value)}
        onBlur={onBlur}
        placeholder={placeholder}
        error={issue !== null}
        helperText={issue ?? hint}
        autoComplete={autoComplete}
        slotProps={{
          input: { readOnly },
          htmlInput: {
            maxLength,
            sx: mono ? { fontFamily: fontFamily.mono } : undefined,
          },
        }}
      />
    </Box>
  )
}

interface SecretFieldProps {
  idPrefix: string
  field: string
  label: string
  value: string
  onChange: (value: string) => void
  /** A secret is stored and still applies: shown as „Gesetzt“ until replaced. */
  stored: boolean
  hint?: ReactNode
  maxLength?: number
}

/**
 * A secret of a source (Prüfliste #34): new ones are typed into a password field; a stored one
 * is never echoed but shown as „Gesetzt“ with „Ersetzen“, which opens the field. „Beibehalten“
 * drops what was typed and keeps the stored secret.
 */
export function SecretField({
  idPrefix,
  field,
  label,
  value,
  onChange,
  stored,
  hint,
  maxLength,
}: SecretFieldProps) {
  const [replacing, setReplacing] = useState(false)
  const input = useRef<HTMLInputElement>(null)
  // „Ersetzen“ hands the focus to the field it opens
  useEffect(() => {
    if (replacing) input.current?.focus()
  }, [replacing])
  const issue = useSourceIssue()(field)
  const id = fieldDomId(idPrefix, field)
  if (stored && !replacing && value === '') {
    return (
      <Box>
        <FieldLabel id={`${id}-label`}>{label}</FieldLabel>
        <Box
          sx={{ display: 'flex', alignItems: 'center', gap: 1.5, minHeight: 40 }}
          role="group"
          aria-labelledby={`${id}-label`}
        >
          <Typography sx={{ fontSize: 14 }}>Gesetzt</Typography>
          <Button
            id={id}
            size="small"
            variant="outlined"
            onClick={() => setReplacing(true)}
            aria-label={`${label} ersetzen`}
          >
            Ersetzen
          </Button>
        </Box>
      </Box>
    )
  }
  return (
    <Box>
      <FieldLabel htmlFor={id}>{label}</FieldLabel>
      <Box sx={{ display: 'flex', gap: 1, alignItems: 'flex-start' }}>
        <TextField
          id={id}
          size="small"
          type="password"
          fullWidth
          inputRef={input}
          value={value}
          onChange={(e) => onChange(e.target.value)}
          error={issue !== null}
          helperText={issue ?? hint}
          autoComplete="new-password"
          slotProps={{ htmlInput: { maxLength } }}
        />
        {stored && (
          <Button
            onClick={() => {
              onChange('')
              setReplacing(false)
            }}
            sx={{ flexShrink: 0, mt: '2px' }}
          >
            Beibehalten
          </Button>
        )}
      </Box>
    </Box>
  )
}
