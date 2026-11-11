import { useId, useState } from 'react'
import Typography from '@mui/material/Typography'
import Stack from '@mui/material/Stack'
import type { PromptResponse } from '@/shared/api/types'
import { initialValues, isComplete, resolvePromptText } from '@/features/prompt'
import { PromptVariableInput } from '@/features/prompt'
import FormDialog from '@/shared/ui/dialog/FormDialog'

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
  // Set by a submit with a required value missing; the fields then say so.
  const [attempted, setAttempted] = useState(false)

  const handleSubmit = () => {
    if (!isComplete(prompt.variables, values)) {
      setAttempted(true)
      return
    }
    onInsert(resolvePromptText(prompt.text, prompt.variables, values, { userName }))
  }

  return (
    <FormDialog
      titleId={titleId}
      title={`Prompt einsetzen: ${prompt.title}`}
      submitLabel="Einsetzen"
      onSubmit={handleSubmit}
      onClose={onCancel}
    >
      {prompt.description && (
        <Typography sx={{ color: 'text.secondary', mb: 2 }}>{prompt.description}</Typography>
      )}
      <Stack spacing={2} sx={{ pt: 1 }}>
        {prompt.variables.map((variable) => (
          <PromptVariableInput
            key={variable.name}
            variable={variable}
            value={values[variable.name] ?? ''}
            onChange={(value) => setValues((current) => ({ ...current, [variable.name]: value }))}
            label={variable.label || variable.name}
            required={variable.required}
            allowEmpty={!variable.required}
            error={
              attempted && variable.required && !(values[variable.name] ?? '').trim()
                ? 'Bitte ausfüllen.'
                : undefined
            }
          />
        ))}
      </Stack>
    </FormDialog>
  )
}
