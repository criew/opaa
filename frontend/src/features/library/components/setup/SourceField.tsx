import type { ReactNode } from 'react'
import TextField from '@mui/material/TextField'
import Box from '@mui/material/Box'
import FieldLabel from '@/shared/ui/wizard/FieldLabel'
import { fontFamily } from '@/shared/theme/tokens'
import { useSourceIssue, withIssue } from '../sources/sourceIssueContext'

/** The attribute a field carries so an error summary link can find and focus it. */
export const FIELD_ATTRIBUTE = 'data-source-field'

interface SourceFieldProps {
  id: string
  /** The form value the field edits; issues naming it show here. */
  field: string
  label: string
  value: string
  onChange: (next: string) => void
  /** One line that says something the label does not. */
  hint?: ReactNode
  placeholder?: string
  optional?: boolean
  mono?: boolean
  multiline?: boolean
  type?: 'text' | 'password'
  readOnly?: boolean
  /** Width in characters for short values (a region, a port); full width otherwise. */
  width?: number
  autoComplete?: string
  onBlur?: () => void
}

/** One labelled field of a source form: label above, error and hint below (guidelines 5.2). */
export default function SourceField({
  id,
  field,
  label,
  value,
  onChange,
  hint,
  placeholder,
  optional = false,
  mono = false,
  multiline = false,
  type = 'text',
  readOnly = false,
  width,
  autoComplete = 'off',
  onBlur,
}: SourceFieldProps) {
  const issueFor = useSourceIssue()
  const issue = issueFor(field)
  return (
    <Box sx={{ maxWidth: width ? `${width + 4}ch` : undefined }}>
      <FieldLabel htmlFor={id}>{optional ? `${label} (optional)` : label}</FieldLabel>
      <TextField
        id={id}
        size="small"
        fullWidth
        type={type}
        value={value}
        onChange={(e) => onChange(e.target.value)}
        onBlur={onBlur}
        placeholder={placeholder}
        multiline={multiline}
        minRows={multiline ? 2 : undefined}
        error={issue !== null}
        helperText={withIssue(issue, hint)}
        autoComplete={autoComplete}
        slotProps={{
          htmlInput: {
            [FIELD_ATTRIBUTE]: field,
            readOnly,
            sx: mono ? { fontFamily: fontFamily.mono, fontSize: 13 } : undefined,
          },
        }}
      />
    </Box>
  )
}
