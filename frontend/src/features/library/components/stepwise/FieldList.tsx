import { useRef, useState } from 'react'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import FormControlLabel from '@mui/material/FormControlLabel'
import IconButton from '@mui/material/IconButton'
import InputAdornment from '@mui/material/InputAdornment'
import Radio from '@mui/material/Radio'
import RadioGroup from '@mui/material/RadioGroup'
import TextField from '@mui/material/TextField'
import Tooltip from '@mui/material/Tooltip'
import Typography from '@mui/material/Typography'
import VisibilityOutlined from '@mui/icons-material/VisibilityOutlined'
import VisibilityOffOutlined from '@mui/icons-material/VisibilityOffOutlined'
import FieldLabel from '@/shared/ui/wizard/FieldLabel'
import { fontFamily } from '@/shared/theme/tokens'
import type { FieldErrors, FieldSpec } from './types'

interface FieldListProps {
  specs: FieldSpec[]
  values: Record<string, unknown>
  onChange: (patch: Record<string, unknown>) => void
  errors: FieldErrors
  idPrefix: string
  mode: 'create' | 'edit'
  credentialsStored: boolean
  replacing: boolean
  onReplacingChange: (replacing: boolean) => void
  /** Called when a field is left; the page validates there, never while typing a first time. */
  onLeave?: (key: string) => void
}

/** The id of a field's input, also the target of the error summary's links. */
export function fieldId(idPrefix: string, key: string) {
  return `${idPrefix}-field-${key}`
}

const hintSx = { fontSize: 12.5, color: 'text.secondary', mb: 1, mt: -0.5 }

/**
 * The fields of the page „Verbindung“, one per row, label above (guidelines 5.2), the error under
 * the field. Stored credentials are never shown: in edit mode they collapse into one row
 * „Zugangsdaten · Gesetzt“ with „Ersetzen“, which opens the credential fields empty.
 */
export default function FieldList({
  specs,
  values,
  onChange,
  errors,
  idPrefix,
  mode,
  credentialsStored,
  replacing,
  onReplacingChange,
  onLeave,
}: FieldListProps) {
  const collapse = mode === 'edit' && credentialsStored && !replacing
  const credentialKeys = specs.filter((spec) => spec.credential).map((spec) => spec.key)
  const firstCredential = credentialKeys[0]
  const replaceRef = useRef<HTMLButtonElement>(null)

  return (
    <Box sx={{ display: 'flex', flexDirection: 'column', gap: 3, maxWidth: 560 }}>
      {specs.map((spec) => {
        if (spec.credential && collapse) {
          if (spec.key !== firstCredential) return null
          return (
            <Box key="credentials">
              <FieldLabel id={`${idPrefix}-credentials-label`}>Zugangsdaten</FieldLabel>
              <Box
                sx={{ display: 'flex', alignItems: 'center', gap: 2, flexWrap: 'wrap' }}
                aria-labelledby={`${idPrefix}-credentials-label`}
                role="group"
              >
                <Typography sx={{ fontSize: 14 }}>Gesetzt</Typography>
                <Button
                  ref={replaceRef}
                  variant="text"
                  onClick={() => onReplacingChange(true)}
                  sx={{ minHeight: 32 }}
                >
                  Ersetzen
                </Button>
              </Box>
            </Box>
          )
        }
        return (
          <Field
            key={spec.key}
            spec={spec}
            value={values[spec.key]}
            values={values}
            error={errors[spec.key]}
            idPrefix={idPrefix}
            onChange={onChange}
            onLeave={onLeave}
          />
        )
      })}
      {mode === 'edit' && credentialsStored && replacing && credentialKeys.length > 0 && (
        <Box>
          <Button
            variant="text"
            onClick={() => {
              onChange(Object.fromEntries(credentialKeys.map((key) => [key, ''])))
              onReplacingChange(false)
            }}
          >
            Gespeicherte Zugangsdaten behalten
          </Button>
        </Box>
      )}
    </Box>
  )
}

interface FieldProps {
  spec: FieldSpec
  value: unknown
  values: Record<string, unknown>
  error: string | undefined
  idPrefix: string
  onChange: (patch: Record<string, unknown>) => void
  onLeave?: (key: string) => void
}

function Field({ spec, value, values, error, idPrefix, onChange, onLeave }: FieldProps) {
  const id = fieldId(idPrefix, spec.key)
  const hintId = `${id}-hint`
  const label = spec.optional ? `${spec.label} (optional)` : spec.label
  const [shown, setShown] = useState(false)
  const fileRef = useRef<HTMLInputElement>(null)
  const [fileError, setFileError] = useState<string | null>(null)
  const describedBy = spec.hint ? hintId : undefined
  const monoSx = spec.mono
    ? { '& input, & textarea': { fontFamily: fontFamily.mono, fontSize: 13 } }
    : undefined

  if (spec.kind === 'choice') {
    return (
      <Box>
        <FieldLabel id={`${id}-label`}>{label}</FieldLabel>
        {spec.hint && (
          <Typography id={hintId} sx={hintSx}>
            {spec.hint}
          </Typography>
        )}
        <RadioGroup
          aria-labelledby={`${id}-label`}
          aria-describedby={describedBy}
          row
          value={String(value ?? '')}
          onChange={(event) => onChange({ [spec.key]: event.target.value })}
          sx={{ columnGap: 2 }}
        >
          {spec.options?.map((option, index) => (
            <FormControlLabel
              key={option.value}
              value={option.value}
              control={<Radio id={index === 0 ? id : undefined} size="small" />}
              label={option.label}
              sx={{ '& .MuiFormControlLabel-label': { fontSize: 14 } }}
            />
          ))}
        </RadioGroup>
      </Box>
    )
  }

  if (spec.kind === 'file') {
    const chosen = spec.fileName?.(values) ?? null
    const shownError = error ?? fileError
    return (
      <Box>
        <FieldLabel htmlFor={id}>{label}</FieldLabel>
        {spec.hint && (
          <Typography id={hintId} sx={hintSx}>
            {spec.hint}
          </Typography>
        )}
        <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.5, flexWrap: 'wrap' }}>
          <Button
            id={id}
            variant="outlined"
            onClick={() => fileRef.current?.click()}
            aria-describedby={[describedBy, shownError ? `${id}-error` : undefined]
              .filter(Boolean)
              .join(' ') || undefined}
            aria-invalid={shownError ? true : undefined}
            sx={shownError ? { borderColor: 'error.main' } : undefined}
          >
            {chosen ? 'Andere Datei wählen' : 'Datei wählen'}
          </Button>
          <Typography
            sx={{ fontSize: 13.5, color: chosen ? 'text.primary' : 'text.secondary', minWidth: 0, overflowWrap: 'anywhere' }}
          >
            {chosen ?? 'Keine Datei gewählt'}
          </Typography>
          <input
            ref={fileRef}
            type="file"
            hidden
            accept={spec.accept}
            aria-label={spec.label}
            onChange={async (event) => {
              const file = event.target.files?.[0]
              event.target.value = ''
              if (!file || !spec.readFile) return
              const read = spec.readFile(await file.text())
              if ('error' in read) {
                setFileError(read.error)
              } else {
                setFileError(null)
                onChange(read.patch)
              }
            }}
          />
        </Box>
        {shownError && (
          <Typography id={`${id}-error`} sx={{ fontSize: 12.5, color: 'error.main', mt: 0.5 }}>
            {shownError}
          </Typography>
        )}
      </Box>
    )
  }

  const secret = spec.kind === 'secret'
  return (
    <Box>
      <FieldLabel htmlFor={id}>{label}</FieldLabel>
      {spec.hint && (
        <Typography id={hintId} sx={hintSx}>
          {spec.hint}
        </Typography>
      )}
      <TextField
        id={id}
        fullWidth
        size="small"
        value={String(value ?? '')}
        onChange={(event) => onChange({ [spec.key]: event.target.value })}
        onBlur={() => onLeave?.(spec.key)}
        placeholder={spec.placeholder}
        multiline={spec.kind === 'multiline'}
        minRows={spec.kind === 'multiline' ? 3 : undefined}
        type={secret && !shown ? 'password' : spec.inputType === 'email' ? 'email' : 'text'}
        error={Boolean(error)}
        helperText={error}
        autoComplete={spec.autoComplete ?? (secret ? 'new-password' : 'off')}
        sx={monoSx}
        slotProps={{
          htmlInput: {
            'aria-describedby': describedBy,
            readOnly: spec.fixed || undefined,
            spellCheck: false,
          },
          formHelperText: { sx: { mx: 0, fontSize: 12.5 } },
          input: secret
            ? {
                endAdornment: (
                  <InputAdornment position="end">
                    <Tooltip title={shown ? 'Verbergen' : 'Anzeigen'}>
                      <IconButton
                        size="small"
                        edge="end"
                        aria-label={shown ? `${spec.label} verbergen` : `${spec.label} anzeigen`}
                        onClick={() => setShown((s) => !s)}
                      >
                        {shown ? (
                          <VisibilityOffOutlined fontSize="small" />
                        ) : (
                          <VisibilityOutlined fontSize="small" />
                        )}
                      </IconButton>
                    </Tooltip>
                  </InputAdornment>
                ),
              }
            : undefined,
        }}
      />
    </Box>
  )
}
