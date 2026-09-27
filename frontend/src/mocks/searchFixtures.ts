import type { MetadataFilterOptionsResponse } from '../types/api'

// #1068: the Dokumentart vocabulary (ADR-0024, Entscheidung 3) as the backend seeds it.
// #1070: the filter options of the mock bestand - Dokumentart above its threshold and therefore
// offered, Datum/Stand below its threshold and therefore not.
export const mockMetadataFilterOptions: MetadataFilterOptionsResponse = {
  totalDocuments: 40,
  fields: [
    {
      fieldKey: 'document_type',
      label: 'Dokumentart',
      filledDocuments: 37,
      totalDocuments: 40,
      fillShare: 0.925,
      threshold: 0.9,
      offered: true,
    },
    {
      fieldKey: 'document_date',
      label: 'Datum/Stand',
      filledDocuments: 22,
      totalDocuments: 40,
      fillShare: 0.55,
      threshold: 0.75,
      offered: false,
    },
  ],
  documentTypes: [
    { code: 'SATZUNG_ORDNUNG', label: 'Satzung/Ordnung', documentCount: 19 },
    { code: 'DIENSTANWEISUNG', label: 'Dienstanweisung', documentCount: 12 },
    { code: 'VERMERK', label: 'Vermerk', documentCount: 6 },
  ],
  documentDateMin: '2019-01-01',
  documentDateMax: '2026-03-12',
  formatFields: [
    {
      fieldKey: 'mail_sender',
      label: 'Absender',
      filledDocuments: 3,
      totalDocuments: 40,
      offered: true,
      valuesCapped: false,
      values: [
        { code: 'mueller@stadt.de', label: 'mueller@stadt.de', documentCount: 2 },
        { code: 'poststelle@kreis.de', label: 'poststelle@kreis.de', documentCount: 1 },
      ],
    },
  ],
}
