import Box from '@mui/material/Box'
import TextField, { type OutlinedTextFieldProps } from '@mui/material/TextField'
import { useId, type ReactNode } from 'react'
import { formWidth } from '../../theme/tokens'
import FormFieldLabel from './FormFieldLabel'

export interface FormFieldProps extends Omit<
  OutlinedTextFieldProps,
  'label' | 'variant' | 'rows' | 'hiddenLabel'
> {
  /** The field's name: shown above the field, or its accessible name with `hideLabel`. */
  label: ReactNode
  /** Appends „(optional)“ to the label. Required fields carry no mark. */
  optional?: boolean
  /**
   * Filter and search fields above a list: no visible label, the label becomes the `aria-label`
   * and the default value or placeholder names the content. Needs a string `label`.
   */
  hideLabel?: boolean
  /**
   * A field holding only a number: its box is 160 px wide, label and helper text keep the width
   * of the column; with `fullWidth={false}` (beside its pair in a row) the whole field is 160 px
   * wide. Implied by `type="number"`.
   */
  numeric?: boolean
}

/** Marks a field with a visible label, so that `FormFieldRow` can align what stands beside it. */
export const LABELLED_FIELD_CLASS = 'OpaaFormField-labelled'

type SlotObject = Record<string, unknown>

/** Slot props given as a function are not merged; no field of the application uses that form. */
function slotObject(slot: unknown): SlotObject {
  return typeof slot === 'object' && slot !== null ? (slot as SlotObject) : {}
}

/**
 * The one form field of the application (guidelines 5.2): text, select (`select`), date
 * (`type="date"`) and, as `renderInput` of an Autocomplete, search-and-pick. The label stands
 * above the field and is tied to the control (`htmlFor`, for a select `aria-labelledby`); helper
 * text and error start at the field's left edge; `required` never draws an asterisk; a multiline
 * field starts with three rows. Fills its container unless it is a filter field.
 */
export default function FormField({
  label,
  optional = false,
  hideLabel = false,
  numeric: numericProp,
  id: idProp,
  select,
  multiline,
  minRows,
  fullWidth: fullWidthProp,
  slotProps,
  sx,
  className,
  type,
  ...rest
}: FormFieldProps) {
  const generatedId = useId()
  const id = idProp ?? generatedId
  const labelId = `${id}-label`
  const numeric = numericProp ?? type === 'number'
  const fullWidth = fullWidthProp ?? !hideLabel
  const htmlInput = slotObject(slotProps?.htmlInput)
  const selectSlot = slotObject(slotProps?.select)
  const native = selectSlot.native === true

  return (
    <Box
      className={[hideLabel ? '' : LABELLED_FIELD_CLASS, className].filter(Boolean).join(' ')}
      sx={[
        {
          display: fullWidth ? 'flex' : 'inline-flex',
          flexDirection: 'column',
          minWidth: 0,
          verticalAlign: 'top',
        },
        fullWidth && { width: '100%' },
        numeric &&
          (fullWidth
            ? { '& .MuiInputBase-root': { width: formWidth.number, maxWidth: '100%' } }
            : { width: formWidth.number, flexShrink: 0 }),
        ...(Array.isArray(sx) ? sx : [sx]),
      ]}
    >
      {!hideLabel && (
        <FormFieldLabel
          htmlFor={select && !native ? undefined : id}
          id={labelId}
          optional={optional}
        >
          {label}
        </FormFieldLabel>
      )}
      <TextField
        {...rest}
        id={id}
        type={type}
        select={select}
        multiline={multiline}
        minRows={multiline ? (minRows ?? 3) : undefined}
        fullWidth
        slotProps={{
          ...slotProps,
          htmlInput: hideLabel ? { 'aria-label': label, ...htmlInput } : slotProps?.htmlInput,
          select: select && !hideLabel ? { labelId, ...selectSlot } : slotProps?.select,
        }}
      />
    </Box>
  )
}
