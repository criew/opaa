import type { FormEvent } from 'react'
import { useId, useState } from 'react'
import Button from '@mui/material/Button'
import Dialog from '@mui/material/Dialog'
import DialogActions from '@mui/material/DialogActions'
import DialogContent from '@mui/material/DialogContent'
import DialogContentText from '@mui/material/DialogContentText'
import DialogTitle from '@mui/material/DialogTitle'
import MenuItem from '@mui/material/MenuItem'
import Stack from '@mui/material/Stack'
import TextField from '@mui/material/TextField'
import type { PromptForInsertion, PromptVariableDefinition } from '../../types/api'
import { initialValues, isComplete, resolvePromptText } from './promptTemplate'

interface PromptVariablesDialogProps {
  prompt: PromptForInsertion
  /** The name `{{USER_NAME}}` resolves to. */
  userName: string
  onCancel: () => void
  /** Receives the resolved text; sending stays the person's own action. */
  onInsert: (text: string) => void
}

/**
 * Asks for the values of a prompt's variables before it is inserted (#1903): one field per
 * variable by its type, defaults prefilled, a date without default prefilled with today. Required
 * fields block "Einsetzen"; the result goes into the input, never straight to the model.
 */
export default function PromptVariablesDialog({
  prompt,
  userName,
  onCancel,
  onInsert,
}: PromptVariablesDialogProps) {
  const baseId = useId()
  const titleId = `${baseId}-title`
  const [values, setValues] = useState<Record<string, string>>(() =>
    initialValues(prompt.variables),
  )
  const complete = isComplete(prompt.variables, values)

  const handleSubmit = (event: FormEvent) => {
    event.preventDefault()
    if (!complete) return
    onInsert(resolvePromptText(prompt.text, prompt.variables, values, { userName }))
  }

  const fieldFor = (variable: PromptVariableDefinition) => {
    const id = `${baseId}-${variable.name}`
    const common = {
      id,
      label: variable.label,
      required: variable.required,
      fullWidth: true,
      size: 'small' as const,
      value: values[variable.name] ?? '',
      onChange: (event: { target: { value: string } }) =>
        setValues((current) => ({ ...current, [variable.name]: event.target.value })),
    }
    switch (variable.type) {
      case 'TEXTAREA':
        return <TextField key={variable.name} {...common} multiline minRows={3} />
      case 'DATE':
        return (
          <TextField
            key={variable.name}
            {...common}
            type="date"
            slotProps={{ inputLabel: { shrink: true } }}
          />
        )
      case 'SELECT':
        return (
          <TextField
            key={variable.name}
            {...common}
            select
            slotProps={{
              inputLabel: { id: `${id}-label` },
              select: { SelectDisplayProps: { 'aria-labelledby': `${id}-label` } },
            }}
          >
            {!variable.required && (
              <MenuItem value="">
                <em>keine Angabe</em>
              </MenuItem>
            )}
            {(variable.options ?? []).map((option) => (
              <MenuItem key={option} value={option}>
                {option}
              </MenuItem>
            ))}
          </TextField>
        )
      default:
        return <TextField key={variable.name} {...common} />
    }
  }

  return (
    <Dialog open fullWidth maxWidth="sm" onClose={onCancel} aria-labelledby={titleId}>
      <form onSubmit={handleSubmit} noValidate>
        <DialogTitle id={titleId}>Prompt einsetzen: {prompt.title}</DialogTitle>
        <DialogContent>
          {prompt.description && (
            <DialogContentText sx={{ mb: 2 }}>{prompt.description}</DialogContentText>
          )}
          <Stack spacing={2} sx={{ pt: 1 }}>
            {prompt.variables.map(fieldFor)}
          </Stack>
        </DialogContent>
        <DialogActions>
          <Button onClick={onCancel}>Abbrechen</Button>
          <Button type="submit" variant="contained" disabled={!complete}>
            Einsetzen
          </Button>
        </DialogActions>
      </form>
    </Dialog>
  )
}
