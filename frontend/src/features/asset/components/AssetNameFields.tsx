import type { Ref } from 'react'
import Box from '@mui/material/Box'
import FormField from '@/shared/ui/form/FormField'

interface AssetNameFieldsProps {
  /** Prefix of the field ids; unique per page. */
  idPrefix: string
  name: string
  onNameChange: (name: string) => void
  /** A missing name, said at the field (guidelines 5.15). */
  nameError?: string | null
  /** Takes the focus when the name is missing. */
  nameInputRef?: Ref<HTMLInputElement>
  description: string
  onDescriptionChange: (description: string) => void
  namePlaceholder: string
}

/** Name and description of a new asset - the first step of every creation wizard. */
export default function AssetNameFields({
  idPrefix,
  name,
  onNameChange,
  nameError = null,
  nameInputRef,
  description,
  onDescriptionChange,
  namePlaceholder,
}: AssetNameFieldsProps) {
  return (
    <>
      <Box>
        <FormField
          label="Name"
          id={`${idPrefix}-name`}
          size="small"
          value={name}
          onChange={(e) => onNameChange(e.target.value)}
          placeholder={namePlaceholder}
          fullWidth
          inputRef={nameInputRef}
          error={nameError !== null}
          helperText={nameError ?? undefined}
          slotProps={{ htmlInput: { maxLength: 255 } }}
        />
      </Box>
      <Box>
        <FormField
          label="Beschreibung"
          optional
          id={`${idPrefix}-description`}
          size="small"
          value={description}
          onChange={(e) => onDescriptionChange(e.target.value)}
          multiline
          fullWidth
          slotProps={{ htmlInput: { maxLength: 2000 } }}
        />
      </Box>
    </>
  )
}
