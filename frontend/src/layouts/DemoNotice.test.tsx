import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it } from 'vitest'
import { renderWithProviders } from '../test/test-utils'
import DemoNotice from './DemoNotice'

afterEach(() => {
  delete window.__OPAA_DEMO_MODE__
})

describe('DemoNotice', () => {
  it('renders nothing when OPAA_DEMO_MODE is off (default OPAA installation)', () => {
    const { container } = renderWithProviders(<DemoNotice />)
    expect(container).toBeEmptyDOMElement()
  })

  it('shows the demo-character hint directly, without opening anything, when enabled', () => {
    window.__OPAA_DEMO_MODE__ = 'true'
    renderWithProviders(<DemoNotice />)

    expect(
      screen.getByText(/Demo-Instanz mit synthetischen Inhalten der fiktiven Stadt Rheinfurt/),
    ).toBeInTheDocument()
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('opens the source/license dialog from the always-visible footer link', async () => {
    window.__OPAA_DEMO_MODE__ = 'true'
    const user = userEvent.setup()
    renderWithProviders(<DemoNotice />)

    await user.click(screen.getByRole('button', { name: 'Quellen & Lizenz' }))

    const dialog = screen.getByRole('dialog')
    expect(dialog).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'LHM-Dienstleistungen-Corpus' })).toHaveAttribute(
      'href',
      'https://huggingface.co/datasets/it-at-m/LHM-Dienstleistungen-Corpus',
    )
    expect(screen.getByText(/MIT-Lizenz/)).toBeInTheDocument()
    // MIT requires redistributing the license text (#728 review, finding "NIT 6") - the dialog
    // links to the full text committed alongside the corpus, not just the license name.
    expect(
      screen.getByRole('link', { name: 'LHM-Dienstleistungen-Corpus-MIT.txt' }),
    ).toHaveAttribute(
      'href',
      'https://github.com/criew/opaa/blob/main/demo/corpus/THIRD-PARTY-LICENSES/LHM-Dienstleistungen-Corpus-MIT.txt',
    )

    await user.click(screen.getByRole('button', { name: 'Schließen' }))
    // MUI's Dialog unmounts only after its exit transition - wait for it instead of asserting
    // synchronously right after the click.
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
  })
})
