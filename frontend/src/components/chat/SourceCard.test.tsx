import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import SourceCard from './SourceCard'

describe('SourceCard', () => {
  it('renders file name and relevance', () => {
    render(
      <SourceCard source={{ fileName: 'readme.md', relevanceScore: 0.92, excerpt: 'Some text' }} />,
    )
    expect(screen.getByText('readme.md')).toBeInTheDocument()
    expect(screen.getByText('92% relevant')).toBeInTheDocument()
  })

  it('shows Public badge for .md files', () => {
    render(<SourceCard source={{ fileName: 'readme.md', relevanceScore: 0.5, excerpt: '' }} />)
    expect(screen.getByText('Public')).toBeInTheDocument()
  })

  it('shows Confidential badge for .pdf files', () => {
    render(<SourceCard source={{ fileName: 'report.pdf', relevanceScore: 0.8, excerpt: '' }} />)
    expect(screen.getByText('Confidential')).toBeInTheDocument()
  })

  it('shows Internal badge for other files', () => {
    render(<SourceCard source={{ fileName: 'config.yaml', relevanceScore: 0.6, excerpt: '' }} />)
    expect(screen.getByText('Internal')).toBeInTheDocument()
  })

  it('shows tooltip on access level badge hover', async () => {
    const user = userEvent.setup()
    render(<SourceCard source={{ fileName: 'config.yaml', relevanceScore: 0.6, excerpt: '' }} />)
    await user.hover(screen.getByText('Internal'))
    expect(await screen.findByText('Access levels coming in a future release')).toBeInTheDocument()
  })
})
