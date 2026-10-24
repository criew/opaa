import { useState, type ReactNode } from 'react'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import FieldLabel from '@/shared/ui/wizard/FieldLabel'
import { fontFamily } from '@/shared/theme/tokens'
import { fieldId, issueMessage, useSetup } from './setupContext'

interface SetupFieldProps {
  /** The form value the field shows; its issue and DOM id derive from it. */
  field: string
  label: string
  value: string
  onChange: (next: string) => void
  /** One line that says something the label does not. */
  hint?: ReactNode
  placeholder?: string
  /** Machine values (paths, addresses, keys) in the mono face. */
  mono?: boolean
  multiline?: boolean
  type?: 'text' | 'password' | 'email'
  /** Expected input length: `short` for a region or port, `full` for an address. */
  width?: 'short' | 'medium' | 'full'
  readOnly?: boolean
  autoComplete?: string
  onBlur?: () => void
}

const WIDTHS = { short: 200, medium: 360, full: '100%' } as const

/**
 * The one text field of the source forms: label above (5.2), the issue of the last submit in
 * danger below it and the hint underneath, both tied to the field as helper text.
 */
export default function SetupField({
  field,
  label,
  value,
  onChange,
  hint,
  placeholder,
  mono = false,
  multiline = false,
  type = 'text',
  width = 'full',
  readOnly = false,
  autoComplete,
  onBlur,
}: SetupFieldProps) {
  const { idPrefix, issues } = useSetup()
  const id = fieldId(idPrefix, field)
  const message = issueMessage(issues, field)
  return (
    <Box sx={{ maxWidth: WIDTHS[width], width: '100%' }}>
      <FieldLabel htmlFor={id}>{label}</FieldLabel>
      <TextField
        id={id}
        size="small"
        fullWidth
        type={type}
        multiline={multiline}
        minRows={multiline ? 2 : undefined}
        value={value}
        onChange={(event) => onChange(event.target.value)}
        onBlur={onBlur}
        placeholder={placeholder}
        error={message !== null}
        autoComplete={autoComplete ?? (type === 'password' ? 'new-password' : 'off')}
        helperText={
          message || hint ? (
            <>
              {message && (
                <Box component="span" sx={{ display: 'block' }}>
                  {message}
                </Box>
              )}
              {hint && (
                <Box component="span" sx={{ display: 'block', color: 'text.secondary' }}>
                  {hint}
                </Box>
              )}
            </>
          ) : undefined
        }
        slotProps={{
          formHelperText: { sx: { mx: 0 } },
          htmlInput: {
            readOnly,
            sx: mono ? { fontFamily: fontFamily.mono, fontSize: 13 } : undefined,
          },
        }}
      />
    </Box>
  )
}

interface SecretFieldProps extends Omit<SetupFieldProps, 'type'> {
  /** A secret is stored for the library: it shows as „gesetzt“ until the person replaces it. */
  stored: boolean
}

/**
 * A credential: a password field when nothing is stored, otherwise the line „gesetzt“ with
 * „Ersetzen“ - the stored value never comes back, and an empty field keeps it.
 */
export function SecretField({ stored, ...props }: SecretFieldProps) {
  const [replacing, setReplacing] = useState(false)
  const { idPrefix, issues } = useSetup()
  const message = issueMessage(issues, props.field)
  if (stored && !replacing && message === null) {
    return (
      <Box>
        <FieldLabel id={`${fieldId(idPrefix, props.field)}-label`}>{props.label}</FieldLabel>
        <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.5, minHeight: 40 }}>
          <Typography
            sx={{ fontSize: 13.5 }}
            aria-labelledby={`${fieldId(idPrefix, props.field)}-label`}
          >
            gesetzt
          </Typography>
          <Button
            size="small"
            variant="text"
            id={fieldId(idPrefix, props.field)}
            onClick={() => setReplacing(true)}
            aria-label={`${props.label} ersetzen`}
          >
            Ersetzen
          </Button>
        </Box>
      </Box>
    )
  }
  return (
    <Box>
      <SetupField
        {...props}
        type="password"
        hint={stored ? 'Leer lassen, um den gespeicherten Wert zu behalten.' : props.hint}
      />
      {stored && replacing && (
        <Button
          size="small"
          variant="text"
          sx={{ mt: 0.5, ml: -1 }}
          onClick={() => {
            props.onChange('')
            setReplacing(false)
          }}
        >
          Nicht ersetzen
        </Button>
      )}
    </Box>
  )
}

interface StoredCredentialsProps {
  /** Credentials are stored and still valid for the address: shown as „gesetzt“. */
  stored: boolean
  /** „Kennung und Kennwort“ */
  label: string
  /** Clears what was typed when the person keeps the stored credentials after all. */
  onKeep: () => void
  children: ReactNode
}

/**
 * Stored credentials in the edit mode: one line „gesetzt“ with „Ersetzen“; replacing shows the
 * fields, „Nicht ersetzen“ returns to the stored ones. Without stored ones the fields show.
 */
export function StoredCredentials({ stored, label, onKeep, children }: StoredCredentialsProps) {
  const [replacing, setReplacing] = useState(false)
  const { issues } = useSetup()
  const credentialIssue = issues.some(
    (issue) => issue.field && /user|account|password|token|secret|key|email/i.test(issue.field),
  )
  if (!stored || replacing || credentialIssue) {
    return (
      <>
        {children}
        {stored && (
          <Box>
            <Button
              size="small"
              variant="text"
              sx={{ ml: -1 }}
              onClick={() => {
                onKeep()
                setReplacing(false)
              }}
            >
              Nicht ersetzen
            </Button>
          </Box>
        )}
      </>
    )
  }
  return (
    <Box>
      <FieldLabel>{label}</FieldLabel>
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.5, minHeight: 40 }}>
        <Typography sx={{ fontSize: 13.5 }}>gesetzt</Typography>
        <Button
          size="small"
          variant="text"
          onClick={() => setReplacing(true)}
          aria-label={`${label} ersetzen`}
        >
          Ersetzen
        </Button>
      </Box>
    </Box>
  )
}

/**
 * The anchor of a choice (a list or tree) for its issue: the summary jumps here, and the message
 * stands above the choice in danger.
 */
export function ScopeField({ field, children }: { field: string; children: ReactNode }) {
  const { idPrefix, issues } = useSetup()
  const message = issueMessage(issues, field)
  return (
    <Box id={fieldId(idPrefix, field)} tabIndex={-1} sx={{ outline: 'none' }}>
      {message && (
        <Typography sx={{ fontSize: 12, color: 'error.main', mb: 1 }}>{message}</Typography>
      )}
      {children}
    </Box>
  )
}
