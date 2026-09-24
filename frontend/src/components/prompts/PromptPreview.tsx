import { useState } from 'react'
import Box from '@mui/material/Box'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import type { PromptVariable } from '../../types/api'
import { useAuthStore } from '../../stores/authStore'
import { resolvePromptPreview } from '../../utils/promptTemplate'
import PromptVariableInput from './PromptVariableInput'
import FieldLabel from '../wizard/FieldLabel'

/**
 * The prompt as someone inserting it would get it: example values for the variables, the system
 * variables resolved for the current person and day. Nothing here is saved - the values exist only
 * for the look at the result.
 */
export default function PromptPreview({
  text,
  variables,
}: {
  text: string
  variables: PromptVariable[]
}) {
  const userName = useAuthStore((s) => s.user?.displayName ?? s.user?.email ?? '')
  const [values, setValues] = useState<Record<string, string>>({})
  const resolved = resolvePromptPreview(text, variables, values, {
    CURRENT_DATE: new Date().toLocaleDateString('de-DE'),
    USER_NAME: userName,
  })

  return (
    <Stack spacing={1.5}>
      {variables.length > 0 && (
        <Box
          sx={{
            display: 'grid',
            gridTemplateColumns: { xs: '1fr', sm: '1fr 1fr' },
            gap: 1.5,
          }}
        >
          {variables.map((variable) => (
            <Box key={variable.name}>
              <FieldLabel>
                {variable.label || variable.name}
                {variable.required ? ' *' : ''}
              </FieldLabel>
              <PromptVariableInput
                variable={variable}
                value={values[variable.name] ?? ''}
                onChange={(value) => setValues((prev) => ({ ...prev, [variable.name]: value }))}
                ariaLabel={`Beispielwert für ${variable.label || variable.name}`}
              />
            </Box>
          ))}
        </Box>
      )}
      <Box>
        <FieldLabel>Ergebnis</FieldLabel>
        <Typography
          component="div"
          role="region"
          aria-label="Vorschau des eingesetzten Textes"
          sx={{
            fontSize: 13.5,
            whiteSpace: 'pre-wrap',
            wordBreak: 'break-word',
            border: 1,
            borderColor: 'divider',
            borderRadius: '8px',
            px: 1.5,
            py: 1,
          }}
        >
          {resolved}
        </Typography>
      </Box>
    </Stack>
  )
}
