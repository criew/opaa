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

  /**
   * #1242: the Absender is a built-in format field - the popover offers the addresses occurring in
   * the scope, and a chosen one travels as an exact value.
   */
  it('offers the Absender with the addresses of the scope and applies the chosen ones', async () => {
    const user = userEvent.setup()
    const onChange = vi.fn()
    renderWithProviders(<MetadataFilterPopover scope={SCOPE} filter={null} onChange={onChange} />)

    await user.click(screen.getByRole('button', { name: 'Metadatenfilter setzen' }))
    expect(await screen.findByText('Absender bei 3 von 40 Dokumenten vorhanden')).toBeVisible()
    await user.click(screen.getByRole('checkbox', { name: 'mueller@stadt.de (2)' }))
    await user.click(screen.getByRole('button', { name: 'Anwenden' }))

    expect(onChange).toHaveBeenCalledWith({
      formatFields: [{ fieldKey: 'mail_sender', values: ['mueller@stadt.de'] }],
    })
  })

  /** #1242: the value set is open, so an address outside the offered ones is typed exactly. */
  it('takes an exact Absender from the free input beside the offered addresses', async () => {
    const user = userEvent.setup()
    const onChange = vi.fn()
    renderWithProviders(<MetadataFilterPopover scope={SCOPE} filter={null} onChange={onChange} />)

    await user.click(screen.getByRole('button', { name: 'Metadatenfilter setzen' }))
    await user.type(await screen.findByLabelText('Absender genau'), 'neu@stadt.de')
    await user.click(screen.getByRole('button', { name: 'Anwenden' }))

    expect(onChange).toHaveBeenCalledWith({
      formatFields: [{ fieldKey: 'mail_sender', values: ['neu@stadt.de'] }],
    })
  })

  const LIBRARY_A = '11111111-1111-1111-1111-111111111111'
  const LIBRARY_B = '22222222-2222-2222-2222-222222222222'

  function withLibraryFields(offeredB = true) {
    return http.get('/api/v1/search/metadata-filter-options', () =>
      HttpResponse.json({
        ...mockMetadataFilterOptions,
        libraryFields: [
          {
            libraryId: LIBRARY_A,
            libraryName: 'Satzungen',
            fieldKey: 'fassung',
            label: 'Fassung',
            type: 'SELECT',
            filledDocuments: 10,
            totalDocuments: 10,
            fillShare: 1,
            threshold: 0.75,
            offered: true,
            values: [
              { code: 'F2026', label: 'Fassung 2026', documentCount: 4 },
              { code: 'F2027', label: 'Fassung 2027', documentCount: 2 },
            ],
          },
          {
            libraryId: LIBRARY_B,
            libraryName: 'Projekte',
            fieldKey: 'fassung',
            label: 'Projektstand',
            type: 'SELECT',
            filledDocuments: offeredB ? 10 : 2,
            totalDocuments: 10,
            fillShare: offeredB ? 1 : 0.2,
            threshold: 0.75,
            offered: offeredB,
            values: [{ code: 'P1', label: 'Planung', documentCount: 1 }],
          },
          {
            libraryId: LIBRARY_A,
            libraryName: 'Satzungen',
            fieldKey: 'paragraf',
            label: 'Paragraf',
            type: 'PATTERN',
            filledDocuments: 10,
            totalDocuments: 10,
            fillShare: 1,
            threshold: 0.75,
            offered: true,
            values: [],
          },
        ],
      }),
    )
  }

  /** The field identity is (library, key): the same key in two libraries is two fields (#1071). */
  it('offers a library field per library and applies the chosen values with their library', async () => {
    server.use(withLibraryFields())
    const user = userEvent.setup()
    const onChange = vi.fn()
    renderWithProviders(<MetadataFilterPopover scope={SCOPE} filter={null} onChange={onChange} />)

    await user.click(screen.getByRole('button', { name: 'Metadatenfilter setzen' }))
    expect(await screen.findByText('Fassung · Satzungen')).toBeVisible()
    expect(screen.getByText('Projektstand · Projekte')).toBeVisible()

    await user.click(screen.getByRole('checkbox', { name: 'Fassung 2026 (4)' }))
    await user.click(screen.getByRole('checkbox', { name: 'Planung (1)' }))
    await user.click(screen.getByRole('button', { name: 'Anwenden' }))

    expect(onChange).toHaveBeenCalledWith({
      libraryFields: [
        { libraryId: LIBRARY_A, fieldKey: 'fassung', codes: ['F2026'] },
        { libraryId: LIBRARY_B, fieldKey: 'fassung', codes: ['P1'] },
      ],
    })
  })

  it('takes an identifier as an exact value and never as a fragment', async () => {
    server.use(withLibraryFields())
    const user = userEvent.setup()
    const onChange = vi.fn()
    renderWithProviders(<MetadataFilterPopover scope={SCOPE} filter={null} onChange={onChange} />)

    await user.click(screen.getByRole('button', { name: 'Metadatenfilter setzen' }))
    await user.type(await screen.findByLabelText('Kennung'), 'AZ-42')
    await user.click(screen.getByRole('button', { name: 'Anwenden' }))

    expect(onChange).toHaveBeenCalledWith({
      libraryFields: [{ libraryId: LIBRARY_A, fieldKey: 'paragraf', value: 'AZ-42' }],
    })
    expect(screen.queryByText(/Teiltreffer/)).not.toBeInTheDocument()
  })

  it('carries a condition of a library field below the threshold through untouched', async () => {
    server.use(withLibraryFields(false))
    const user = userEvent.setup()
    const onChange = vi.fn()
    renderWithProviders(
      <MetadataFilterPopover
        scope={SCOPE}
        filter={{ libraryFields: [{ libraryId: LIBRARY_B, fieldKey: 'fassung', codes: ['P1'] }] }}
        onChange={onChange}
      />,
    )

    await user.click(screen.getByRole('button', { name: 'Metadatenfilter setzen' }))
    await user.click(await screen.findByRole('checkbox', { name: 'Fassung 2026 (4)' }))
    await user.click(screen.getByRole('button', { name: 'Anwenden' }))

    expect(onChange).toHaveBeenCalledWith({
      libraryFields: [
        { libraryId: LIBRARY_B, fieldKey: 'fassung', codes: ['P1'] },
        { libraryId: LIBRARY_A, fieldKey: 'fassung', codes: ['F2026'] },
      ],
    })
  })
})
