import type { QueryResponse } from '../types/api'
import type { MetadataFilterOptionsResponse } from '../types/api'

export const mockQueryResponses: QueryResponse[] = [
  {
    answer:
      'Das Projekt nutzt eine modulare Monolith-Architektur mit drei Hauptmodulen: ' +
      'api, indexing und query【source: doc-arch#0 | architecture-overview.md】. Das Modul api ' +
      'stellt die REST-Endpunkte und DTOs bereit, das Modul indexing verwaltet die ' +
      'Dokumentenaufnahme【source: doc-start#2 | getting-started.pdf】 und das Modul query ' +
      'beantwortet Fragen über RAG【source: doc-arch#3 | architecture-overview.md】.',
    sources: [
      {
        fileName: 'architecture-overview.md',
        relevanceScore: 1,
        matchCount: 3,
        indexedAt: '2025-01-15T10:30:00Z',
        cited: true,
        citationValid: true,
        documentId: 'doc-arch',
        // #667: the Fundort per chunk the markers above name (#0, #3).
        chunkLocations: [
          { chunkIndex: 0, location: 'Abschn. Architektur › Module' },
          { chunkIndex: 3, location: 'Abschn. Architektur › Query-Pipeline' },
        ],
      },
      {
        fileName: 'getting-started.pdf',
        relevanceScore: 0.5,
        matchCount: 1,
        indexedAt: '2025-01-15T10:30:00Z',
        cited: true,
        documentId: 'doc-start',
        chunkLocations: [{ chunkIndex: 2, location: 'S. 2–3' }],
        // #697 review, Nit 6: one mock source with an invalid citation, so the mocked frontend
        // (VITE_ENABLE_MOCKS=true) can actually show the "Beleg nicht bestätigt" state (#386).
        citationValid: false,
      },
      {
        fileName: 'adr-0002-technology-stack.md',
        relevanceScore: 0.33,
        matchCount: 2,
        indexedAt: '2025-01-14T08:00:00Z',
        cited: false,
        citationValid: true,
      },
      {
        // #1242: an uncited mail source (VITE_ENABLE_MOCKS=true) whose Kopfdaten arrive as
        // ordinary entries of the generic metadata list, like every other schema field.
        fileName: 'anfrage-bauantrag.eml',
        relevanceScore: 0.2,
        matchCount: 1,
        indexedAt: '2025-01-16T09:15:00Z',
        cited: false,
        citationValid: true,
        metadata: [
          {
            fieldKey: 'title',
            label: 'Titel',
            value: 'Bebauungsplan Nord',
            displayValue: 'Bebauungsplan Nord',
            origin: 'DETERMINISTIC',
            detailOnly: false,
          },
          {
            fieldKey: 'fmt:mail_sender',
            label: 'Absender',
            value: 'mueller@stadt.de',
            displayValue: 'mueller@stadt.de',
            origin: 'DETERMINISTIC',
            detailOnly: false,
          },
          {
            fieldKey: 'fmt:mail_recipients',
            label: 'An',
            value: 'poststelle@stadt.de',
            displayValue: 'poststelle@stadt.de',
            origin: 'DETERMINISTIC',
            detailOnly: false,
          },
        ],
      },
      {
        // #1066: an uncited source with core metadata (VITE_ENABLE_MOCKS=true), so the mocked
        // frontend shows the Fundstellen metadata line without a matching citation marker.
        fileName: '2026-03-12_Dienstanweisung_IT-Nutzung.pdf',
        relevanceScore: 0.1,
        matchCount: 1,
        indexedAt: '2026-03-13T08:00:00Z',
        cited: false,
        citationValid: true,
        metadata: [
          {
            fieldKey: 'title',
            label: 'Titel',
            value: 'Dienstanweisung zur IT-Nutzung',
            displayValue: 'Dienstanweisung zur IT-Nutzung',
            origin: 'DETERMINISTIC',
            detailOnly: false,
          },
          {
            fieldKey: 'document_type',
            label: 'Dokumentart',
            value: 'DIENSTANWEISUNG',
            displayValue: 'Dienstanweisung',
            origin: 'DETERMINISTIC',
            detailOnly: false,
          },
          {
            fieldKey: 'document_date',
            label: 'Datum/Stand',
            value: '2026-03-12',
            displayValue: '12.03.2026',
            origin: 'DETERMINISTIC',
            datePrecision: 'DAY',
            detailOnly: false,
          },
        ],
      },
    ],
    metadata: {
      model: 'gpt-4o',
      tokenCount: 847,
      durationMs: 1523,
      answeredWithoutKnowledge: false,
      noKnowledgeAvailableInSpace: false,
      searchedLibraries: [
        { id: '11111111-1111-4111-8111-111111111111', name: 'Engineering-Handbuch' },
        { id: '22222222-2222-4222-8222-222222222222', name: 'Meine Dokumente' },
      ],
    },
    chatId: 'mock-conv-1',
  },
  {
    answer:
      'Für einen neuen REST-Endpunkt legen Sie im Modul api eine Controller-Klasse mit der ' +
      'Annotation @RestController an【source: doc-contrib#1 | contributing-guide.md】. Request- ' +
      'und Response-DTOs werden als Java-Records definiert, die Eingabevalidierung erfolgt ' +
      'über Jakarta Bean Validation【source: doc-contrib#4 | contributing-guide.md】. Der ' +
      'Endpunkt wird automatisch über die OpenAPI-Spezifikation dokumentiert.',
    sources: [
      {
        fileName: 'contributing-guide.md',
        relevanceScore: 1,
        matchCount: 1,
        indexedAt: '2025-01-15T10:30:00Z',
        cited: true,
        citationValid: true,
      },
    ],
    metadata: {
      model: 'gpt-4o',
      tokenCount: 312,
      durationMs: 890,
      answeredWithoutKnowledge: false,
      noKnowledgeAvailableInSpace: false,
      searchedLibraries: [
        { id: '11111111-1111-4111-8111-111111111111', name: 'Engineering-Handbuch' },
        { id: '22222222-2222-4222-8222-222222222222', name: 'Meine Dokumente' },
      ],
    },
    chatId: 'mock-conv-2',
  },
  {
    answer:
      'Die Deployment-Pipeline orchestriert alle Dienste über Docker ' +
      'Compose【source: doc-compose#0 | docker-compose.yml】. PostgreSQL mit pgvector speichert ' +
      'die Vektoren der Embeddings【source: doc-adr2#2 | adr-0002-technology-stack.md】, ' +
      'Liquibase verwaltet die Datenbankmigrationen【source: doc-deploy#1 | deployment-guide.pdf】' +
      '【source: doc-liqui#0 | liquibase-changelog.xml】. Die CI/CD-Pipeline läuft auf GitHub ' +
      'Actions mit getrennten Jobs für Backend- und Frontend-Build, Linting und ' +
      'Testausführung【source: doc-ci#3 | ci-pipeline.md】.',
    sources: [
      {
        fileName: 'docker-compose.yml',
        relevanceScore: 1,
        matchCount: 2,
        indexedAt: '2025-01-15T10:30:00Z',
        cited: true,
        citationValid: true,
      },
      {
        fileName: 'deployment-guide.pdf',
        relevanceScore: 0.5,
        matchCount: 1,
        indexedAt: '2025-01-15T10:30:00Z',
        cited: true,
        citationValid: true,
      },
      {
        fileName: 'adr-0002-technology-stack.md',
        relevanceScore: 0.33,
        matchCount: 3,
        indexedAt: '2025-01-14T08:00:00Z',
        cited: true,
        citationValid: true,
      },
      {
        fileName: 'ci-pipeline.md',
        relevanceScore: 0.25,
        matchCount: 1,
        indexedAt: '2025-01-13T15:00:00Z',
        cited: true,
        citationValid: true,
      },
      {
        fileName: 'liquibase-changelog.xml',
        relevanceScore: 0.2,
        matchCount: 1,
        indexedAt: '2025-01-12T09:00:00Z',
        cited: true,
        citationValid: true,
      },
      {
        fileName: 'postgres-setup.md',
        relevanceScore: 0.17,
        matchCount: 1,
        indexedAt: '2025-01-11T14:00:00Z',
        cited: false,
        citationValid: true,
      },
      {
        fileName: 'environment-config.md',
        relevanceScore: 0.14,
        matchCount: 1,
        indexedAt: '2025-01-10T11:00:00Z',
        cited: false,
        citationValid: true,
      },
      {
        fileName: 'monitoring-guide.md',
        relevanceScore: 0.13,
        matchCount: 1,
        indexedAt: '2025-01-09T16:00:00Z',
        cited: false,
        citationValid: true,
      },
      {
        fileName: 'backup-strategy.pdf',
        relevanceScore: 0.11,
        matchCount: 1,
        indexedAt: null,
        cited: false,
        citationValid: true,
      },
      {
        fileName: 'security-checklist.md',
        relevanceScore: 0.1,
        matchCount: 1,
        indexedAt: null,
        cited: false,
        citationValid: true,
      },
    ],
    metadata: {
      model: 'gpt-4o',
      tokenCount: 1584,
      durationMs: 2341,
      answeredWithoutKnowledge: false,
      noKnowledgeAvailableInSpace: false,
      searchedLibraries: [
        { id: '11111111-1111-4111-8111-111111111111', name: 'Engineering-Handbuch' },
        { id: '22222222-2222-4222-8222-222222222222', name: 'Meine Dokumente' },
      ],
    },
    chatId: 'mock-conv-3',
  },
]

export function getRandomMockResponse(): QueryResponse {
  return mockQueryResponses[Math.floor(Math.random() * mockQueryResponses.length)]
}

export const mockErrorResponse = {
  error: 'question: darf nicht leer sein',
  status: 400,
  timestamp: '2025-01-15T10:30:00Z',
}

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
