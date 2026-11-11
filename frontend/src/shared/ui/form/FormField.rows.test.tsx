import { render } from '@testing-library/react'
import type { TextFieldProps } from '@mui/material/TextField'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import FormField from './FormField'

const passed = vi.hoisted(() => ({ props: [] as TextFieldProps[] }))

// jsdom lays nothing out and MUI drops the `rows` attribute of its autosizing textarea, so the
// number of rows is read where FormField hands it to MUI.
vi.mock('@mui/material/TextField', () => ({
  default: (props: TextFieldProps) => {
    passed.props.push(props)
    return null
  },
}))

describe('rows of a multiline FormField (guidelines 5.2)', () => {
  beforeEach(() => {
    passed.props = []
  })

  it('starts with three rows', () => {
    render(<FormField label="Ausnahmeliste" multiline />)
    expect(passed.props[0].minRows).toBe(3)
    expect(passed.props[0].rows).toBeUndefined()
  })

  it('grows from there up to the maxRows a form sets', () => {
    render(<FormField label="Textfassung" multiline maxRows={20} />)
    expect(passed.props[0].minRows).toBe(3)
    expect(passed.props[0].maxRows).toBe(20)
  })

  it('sets no rows on a single-line field', () => {
    render(<FormField label="Adresse" />)
    expect(passed.props[0].minRows).toBeUndefined()
  })
})
