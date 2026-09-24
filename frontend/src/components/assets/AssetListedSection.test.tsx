import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { renderWithProviders } from '../../test/test-utils'
import AssetListedSection from './AssetListedSection'

describe('AssetListedSection (#1941)', () => {
  it('says what becomes visible — the entry, not the content', () => {
    renderWithProviders(
      <AssetListedSection
        assetType="KNOWLEDGE_LIBRARY"
        listed={false}
        onSave={vi.fn(async () => undefined)}
      />,
    )

    expect(screen.getByRole('heading', { name: 'Im Katalog auffindbar' })).toBeInTheDocument()
    expect(screen.getByText(/nicht der\s+Inhalt/)).toBeInTheDocument()
    expect(screen.queryByText(/Marktplatz/)).not.toBeInTheDocument()
  })

  // Kein Sofort-Speichern mitten im Formular: Der Entwurf steht, bis der Knopf gedrückt wird.
  it('holds the draft until the section is saved', async () => {
    const onSave = vi.fn(async () => undefined)
    renderWithProviders(
      <AssetListedSection assetType="KNOWLEDGE_LIBRARY" listed={false} onSave={onSave} />,
    )
    const user = userEvent.setup()
    const save = screen.getByRole('button', { name: 'Auffindbarkeit speichern' })
    expect(save).toBeDisabled()

    await user.click(screen.getByLabelText('Im Katalog auffindbar, auch ohne Berechtigung'))
    expect(onSave).not.toHaveBeenCalled()
    await user.click(screen.getByRole('button', { name: 'Auffindbarkeit speichern' }))

    await waitFor(() => expect(onSave).toHaveBeenCalledWith(true))
  })

  it('locks the switch under a ceiling and names who set it', () => {
    renderWithProviders(
      <AssetListedSection
        assetType="KNOWLEDGE_LIBRARY"
        listed={false}
        listedCap={false}
        onSave={vi.fn(async () => undefined)}
      />,
    )

    expect(screen.getByLabelText('Im Katalog auffindbar, auch ohne Berechtigung')).toBeDisabled()
    expect(screen.getByText(/Systemverwaltung hat die Auffindbarkeit/)).toBeInTheDocument()
  })

  it('keeps a refusal in the section', async () => {
    const onSave = vi.fn(async () => {
      throw new Error('Speichern abgelehnt')
    })
    renderWithProviders(
      <AssetListedSection assetType="KNOWLEDGE_LIBRARY" listed={false} onSave={onSave} />,
    )
    const user = userEvent.setup()

    await user.click(screen.getByLabelText('Im Katalog auffindbar, auch ohne Berechtigung'))
    await user.click(screen.getByRole('button', { name: 'Auffindbarkeit speichern' }))

    expect(await screen.findByText('Speichern abgelehnt')).toBeInTheDocument()
  })
})
