import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import type { SourceTypeKey } from '@/shared/api/types'
import { mockSourceTypes } from '../../library/mocks/libraryFixtures'
import { sourceTypeEntry } from './sourceTypeDirectory'
import SourceTypeIcon from './sourceTypeIcon'

describe('the source type directory', () => {
  it('names every type the mock backend lists', () => {
    for (const { type } of mockSourceTypes) {
      const entry = sourceTypeEntry(type as SourceTypeKey)
      expect(entry?.label, type).toBeTruthy()
      expect(entry?.shortLabel, type).toBeTruthy()
      expect(entry?.description, type).toBeTruthy()
    }
  })

  it('has no entry for a type this client does not know, nor for none', () => {
    expect(sourceTypeEntry('PROBE' as SourceTypeKey)).toBeUndefined()
    expect(sourceTypeEntry(null)).toBeUndefined()
    expect(sourceTypeEntry(undefined)).toBeUndefined()
  })
})

describe('SourceTypeIcon', () => {
  it('shows the symbol of the directory entry', () => {
    render(<SourceTypeIcon sourceType="CONFLUENCE" />)

    expect(screen.getByTestId('AccountTreeIcon')).toBeInTheDocument()
  })

  it('shows a neutral symbol for a type without an entry', () => {
    render(<SourceTypeIcon sourceType={'PROBE' as SourceTypeKey} />)

    expect(screen.getByTestId('ExtensionIcon')).toBeInTheDocument()
  })

  it('shows the upload symbol for a library without a source type', () => {
    render(<SourceTypeIcon sourceType={undefined} />)

    expect(screen.getByTestId('UploadFileIcon')).toBeInTheDocument()
  })
})
