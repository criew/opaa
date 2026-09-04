import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { renderWithProviders } from '../../test/test-utils'
import { server } from '../../mocks/server'
import { mockMetadataFilterOptions } from '../../mocks/fixtures'
import { useMetadataFilterOptionsStore } from '../../stores/metadataFilterOptionsStore'
import MetadataFilterPopover from './MetadataFilterPopover'
import { fillLevelText, notOfferedText } from './metadataFilterText'

const SCOPE = { chatId: null, useKnowledge: true, libraryIds: [] }

describe('MetadataFilterPopover (#1070)', () => {
  beforeEach(() => {
    useMetadataFilterOptionsStore.getState().reset()
  })

  it('shows the fill level of an offered field and the reason a field below the threshold is not offered', async () => {
    const user = userEvent.setup()
    renderWithProviders(<MetadataFilterPopover scope={SCOPE} filter={null} onChange={vi.fn()} />)

    await user.click(screen.getByRole('button', { name: 'Metadatenfilter setzen' }))

    expect(await screen.findByText('Dokumentart bei 93 % der Dokumente vorhanden')).toBeVisible()
    // The choices are the values occurring in the scope, with their counts.
    expect(screen.getByRole('checkbox', { name: 'Satzung/Ordnung (19)' })).toBeInTheDocument()
    expect(screen.getByRole('checkbox', { name: 'Vermerk (6)' })).toBeInTheDocument()
    // Datum/Stand is below its threshold: no input, but the reason with its numbers.
    expect(screen.getByTestId('filter-field-not-offered')).toHaveTextContent(
      'Datum/Stand wird nicht angeboten: nur bei 55 % der Dokumente vorhanden (Schwelle 75 %).',
    )
    expect(screen.queryByLabelText('Von')).not.toBeInTheDocument()
  })

  it('applies the chosen Dokumentart values and clears the filter again', async () => {
    const user = userEvent.setup()
    const onChange = vi.fn()
    renderWithProviders(
      <MetadataFilterPopover
        scope={SCOPE}
        filter={{ documentTypes: ['VERMERK'] }}
        onChange={onChange}
      />,
    )

    await user.click(screen.getByRole('button', { name: 'Metadatenfilter setzen' }))
    expect(await screen.findByRole('checkbox', { name: 'Vermerk (6)' })).toBeChecked()
    await user.click(screen.getByRole('checkbox', { name: 'Dienstanweisung (12)' }))
    await user.click(screen.getByRole('button', { name: 'Anwenden' }))

    expect(onChange).toHaveBeenCalledWith({ documentTypes: ['DIENSTANWEISUNG', 'VERMERK'] })

    await user.click(screen.getByRole('button', { name: 'Metadatenfilter setzen' }))
    await user.click(await screen.findByRole('button', { name: 'Filter entfernen' }))
    expect(onChange).toHaveBeenLastCalledWith(null)
  })

  it('offers the date window with the span of the scope once the field reaches its threshold', async () => {
    server.use(
      http.get('/api/v1/search/metadata-filter-options', () =>
        HttpResponse.json({
          ...mockMetadataFilterOptions,
          fields: mockMetadataFilterOptions.fields.map((field) =>
            field.fieldKey === 'document_date'
              ? { ...field, filledDocuments: 36, fillShare: 0.9, offered: true }
              : field,
          ),
        }),
      ),
    )
    const user = userEvent.setup()
    const onChange = vi.fn()
    renderWithProviders(<MetadataFilterPopover scope={SCOPE} filter={null} onChange={onChange} />)

    await user.click(screen.getByRole('button', { name: 'Metadatenfilter setzen' }))
    expect(
      await screen.findByText(/Datum\/Stand bei 90 % der Dokumente vorhanden/),
    ).toBeInTheDocument()
    expect(screen.getByText(/Werte von 2019-01-01 bis 2026-03-12/)).toBeInTheDocument()
    await user.type(screen.getByLabelText('Von'), '2024-01-01')
    await user.type(screen.getByLabelText('Bis'), '2024-12-31')
    await user.click(screen.getByRole('button', { name: 'Anwenden' }))

    expect(onChange).toHaveBeenCalledWith({
      documentDateFrom: '2024-01-01',
      documentDateTo: '2024-12-31',
    })
  })

  // Koordinator-Festlegung an #1070: a field below the threshold is not offered, but a condition
  // already set on it stays in force - applying a change to the other field must not drop it.
  it('carries a set condition of a field below the threshold through untouched', async () => {
    server.use(
      http.get('/api/v1/search/metadata-filter-options', () =>
        HttpResponse.json({
          ...mockMetadataFilterOptions,
          fields: mockMetadataFilterOptions.fields.map((field) =>
            field.fieldKey === 'document_type'
              ? { ...field, filledDocuments: 4, fillShare: 0.1, offered: false }
              : { ...field, filledDocuments: 36, fillShare: 0.9, offered: true },
          ),
        }),
      ),
    )
    const user = userEvent.setup()
    const onChange = vi.fn()
    renderWithProviders(
      <MetadataFilterPopover
        scope={SCOPE}
        filter={{ documentTypes: ['DIENSTANWEISUNG'] }}
        onChange={onChange}
      />,
    )

    await user.click(screen.getByRole('button', { name: 'Metadatenfilter setzen' }))
    expect(await screen.findByTestId('filter-field-not-offered')).toHaveTextContent(
      'Die bereits gesetzte Bedingung bleibt wirksam',
    )
    await user.type(screen.getByLabelText('Von'), '2024-01-01')
    await user.click(screen.getByRole('button', { name: 'Anwenden' }))

    expect(onChange).toHaveBeenCalledWith({
      documentTypes: ['DIENSTANWEISUNG'],
      documentDateFrom: '2024-01-01',
    })
  })

  it('cannot apply anything when no field is offered', async () => {
    server.use(
      http.get('/api/v1/search/metadata-filter-options', () =>
        HttpResponse.json({
          totalDocuments: 0,
          fields: mockMetadataFilterOptions.fields.map((field) => ({
            ...field,
            filledDocuments: 0,
            totalDocuments: 0,
            fillShare: 0,
            offered: false,
          })),
          documentTypes: [],
          documentDateMin: null,
          documentDateMax: null,
        }),
      ),
    )
    const user = userEvent.setup()
    renderWithProviders(<MetadataFilterPopover scope={SCOPE} filter={null} onChange={vi.fn()} />)

    await user.click(screen.getByRole('button', { name: 'Metadatenfilter setzen' }))

    await waitFor(() => {
      expect(screen.getByRole('button', { name: 'Anwenden' })).toBeDisabled()
    })
    expect(screen.getAllByTestId('filter-field-not-offered')).toHaveLength(2)
  })

  it('words the fill level and the entry condition with the field label and its numbers', () => {
    expect(fillLevelText({ label: 'Datum/Stand', filledDocuments: 46, totalDocuments: 50 })).toBe(
      'Datum/Stand bei 92 % der Dokumente vorhanden',
    )
    expect(
      notOfferedText({
        label: 'Dokumentart',
        filledDocuments: 6,
        totalDocuments: 50,
        threshold: 0.9,
      }),
    ).toBe(
      'Dokumentart wird nicht angeboten: nur bei 12 % der Dokumente vorhanden (Schwelle 90 %).',
    )
  })
})
