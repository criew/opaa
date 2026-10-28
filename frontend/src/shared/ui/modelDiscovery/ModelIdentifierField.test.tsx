import { describe, expect, it, vi } from 'vitest'
import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { DiscoveredLlmModel } from '@/shared/api/types'
import { renderWithProviders } from '@/test/test-utils'
import ModelIdentifierField from './ModelIdentifierField'

const MODELS: DiscoveredLlmModel[] = [
  { identifier: 'qwen3:8b', capabilities: ['completion'], chatSuitable: true },
  {
    identifier: 'bge-m3',
    capabilities: ['embedding'],
    chatSuitable: false,
    contextLength: 8192,
  },
]

describe('ModelIdentifierField', () => {
  it('offers embedding models first for the embedding role, every model selectable', async () => {
    const user = userEvent.setup()
    const onSelect = vi.fn()
    renderWithProviders(
      <ModelIdentifierField
        purpose="embedding"
        value=""
        models={MODELS}
        loading={false}
        onInputChange={() => {}}
        onSelect={onSelect}
      />,
    )

    await user.click(screen.getByRole('combobox', { name: /Modell-Kennung/ }))

    const listbox = await screen.findByRole('listbox')
    const suitable = within(listbox).getByRole('group', { name: 'Für die Einbettung geeignet' })
    expect(within(suitable).getByRole('option', { name: /bge-m3/ })).toBeInTheDocument()
    const others = within(listbox).getByRole('group', { name: 'Weitere Modelle' })
    const chat = within(others).getByRole('option', { name: /qwen3:8b/ })
    expect(chat).not.toHaveAttribute('aria-disabled', 'true')
    await user.click(chat)
    expect(onSelect).toHaveBeenCalledWith(MODELS[0])
  })

  it('offers speech models first for the transcription role, although the backend marks them unfit for the chat', async () => {
    const user = userEvent.setup()
    const onSelect = vi.fn()
    // LlmModelDiscoverer marks whisper and transcribe models chatSuitable: false.
    const whisper: DiscoveredLlmModel = {
      identifier: 'Systran/faster-whisper-large-v3',
      capabilities: [],
      chatSuitable: false,
    }
    renderWithProviders(
      <ModelIdentifierField
        purpose="transcription"
        value=""
        models={[MODELS[0], whisper]}
        loading={false}
        onInputChange={() => {}}
        onSelect={onSelect}
      />,
    )

    await user.click(screen.getByRole('combobox', { name: /Modell-Kennung/ }))

    const listbox = await screen.findByRole('listbox')
    const suitable = within(listbox).getByRole('group', { name: 'Für die Transkription geeignet' })
    const option = within(suitable).getByRole('option', { name: /faster-whisper/ })
    expect(option).not.toHaveAttribute('aria-disabled', 'true')
    expect(option).not.toHaveTextContent('nicht für den Chat geeignet')
    await user.click(option)
    expect(onSelect).toHaveBeenCalledWith(whisper)
  })

  it('stands with its label above the field and names its error', () => {
    renderWithProviders(
      <ModelIdentifierField
        id="wizard-model"
        labelAbove
        error="Bitte die Modell-Kennung angeben."
        value=""
        models={[]}
        loading={false}
        onInputChange={() => {}}
        onSelect={() => {}}
      />,
    )

    const input = screen.getByLabelText('Modell-Kennung', { selector: 'input' })
    expect(input).toHaveAttribute('id', 'wizard-model')
    expect(input).toHaveAttribute('aria-invalid', 'true')
    expect(input).toHaveAccessibleDescription('Bitte die Modell-Kennung angeben.')
  })
})
