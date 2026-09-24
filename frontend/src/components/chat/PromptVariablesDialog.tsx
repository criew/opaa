import type { FormEvent } from 'react'
import { useId, useState } from 'react'
import Button from '@mui/material/Button'
import Dialog from '@mui/material/Dialog'
import DialogActions from '@mui/material/DialogActions'
import DialogContent from '@mui/material/DialogContent'
import DialogContentText from '@mui/material/DialogContentText'
import DialogTitle from '@mui/material/DialogTitle'
import Stack from '@mui/material/Stack'
import type { PromptResponse } from '../../types/api'
import { initialValues, isComplete, resolvePromptText } from '../../utils/promptTemplate'
import PromptVariableInput from '../prompts/PromptVariableInput'

interface PromptVariablesDialogProps {
  prompt: PromptResponse
  /** The name `{{USER_NAME}}` resolves to. */
  userName: string
  onCancel: () => void
  /** Receives the resolved text; sending stays the person's own action. */
  onInsert: (text: string) => void
}

/**
 * Asks for the values of a prompt's variables before it is inserted: one field per variable by its
 * type, defaults prefilled, a date without default prefilled with today. Required fields block
 * "Einsetzen"; the result goes into the input, never straight to the model.
 */
export default function PromptVariablesDialog({
  prompt,
  userName,
  onCancel,
  onInsert,
}: PromptVariablesDialogProps) {
  const titleId = useId()
  const [values, setValues] = useState<Record<string, string>>(() =>
    initialValues(prompt.variables),
  )
  const complete = isComplete(prompt.variables, values)

  const handleSubmit = (event: FormEvent) => {
    event.preventDefault()
    if (!complete) return
    onInsert(resolvePromptText(prompt.text, prompt.variables, values, { userName }))
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
            {prompt.variables.map((variable) => (
              <PromptVariableInput
                key={variable.name}
                variable={variable}
                value={values[variable.name] ?? ''}
                onChange={(value) =>
                  setValues((current) => ({ ...current, [variable.name]: value }))
                }
                label={variable.label || variable.name}
                required={variable.required}
                allowEmpty={!variable.required}
              />
            ))}
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
