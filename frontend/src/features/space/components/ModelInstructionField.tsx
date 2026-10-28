import Box from '@mui/material/Box'
import TextField from '@mui/material/TextField'
import FieldLabel from '@/shared/ui/wizard/FieldLabel'

/** Same limit as the specification and the database. */
export const MODEL_INSTRUCTION_MAX_LENGTH = 1000

interface ModelInstructionFieldProps {
  id: string
  value: string
  onChange: (value: string) => void
  /** Only the space role ADMIN changes the instruction; everyone else reads it. */
  canChange: boolean
}

/**
 * The instruction of a space to the model, part of every answer in the space. Every member sees it;
 * the helper text says so and asks for no personal data.
 */
export default function ModelInstructionField({
  id,
  value,
  onChange,
  canChange,
}: ModelInstructionFieldProps) {
  const hint =
    'Gilt für jede Antwort in diesem Space und ist für alle Mitglieder sichtbar. Bitte keine ' +
    'personenbezogenen Angaben.'
  return (
    <Box>
      <FieldLabel htmlFor={id}>Anweisung an das Modell</FieldLabel>
      <TextField
        id={id}
        size="small"
        fullWidth
        multiline
        minRows={3}
        value={value}
        onChange={(event) => onChange(event.target.value)}
        placeholder={
          canChange
            ? 'z. B. „Nenne bei Fristen immer die Rechtsgrundlage.“'
            : 'Keine Anweisung hinterlegt'
        }
        helperText={
          canChange
            ? `${hint} ${value.length} von ${MODEL_INSTRUCTION_MAX_LENGTH} Zeichen.`
            : `${hint} Nur Mitglieder mit der Rolle ADMIN ändern die Anweisung.`
        }
        slotProps={{
          htmlInput: { readOnly: !canChange, maxLength: MODEL_INSTRUCTION_MAX_LENGTH },
          formHelperText: { sx: { mx: 0 } },
        }}
      />
    </Box>
  )
}
