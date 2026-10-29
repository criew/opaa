import { render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import LibraryUploadInputs from './LibraryUploadInputs'
import type { DocumentUpload } from '../hooks/useDocumentUpload'

function upload(uploadWaitSeconds: number | null): DocumentUpload {
  return {
    isUploading: uploadWaitSeconds !== null,
    uploadWaitSeconds,
    isDragActive: false,
    setIsDragActive: vi.fn(),
    fileInputRef: { current: null },
    folderInputRef: { current: null },
    clearUploadErrors: vi.fn(),
    handleFiles: vi.fn(),
    reportSkippedFiles: vi.fn(),
    handleDrop: vi.fn(),
  }
}

describe('LibraryUploadInputs', () => {
  it('announces in a live region how long an upload waits out a refusal', () => {
    render(<LibraryUploadInputs upload={upload(5)} />)

    const status = screen.getByRole('status')
    expect(status).toHaveAttribute('aria-live', 'polite')
    expect(status).toHaveTextContent(
      'Der Server nimmt gerade keine weiteren Uploads an. Der Upload geht in 5 Sekunden automatisch weiter.',
    )
  })

  it('keeps the live region empty while no upload waits', () => {
    render(<LibraryUploadInputs upload={upload(null)} />)

    expect(screen.getByRole('status')).toBeEmptyDOMElement()
  })
})
