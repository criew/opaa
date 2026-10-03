import type {
  LibraryListResponse,
  LibraryDocumentResponse,
  LibraryResponse,
  SourceTypeDescriptor,
} from '../types/api'
import type { ConfluenceSpaceRef } from '../utils/confluenceSource'

// #822: a plain mock shape rather than LibraryFolderResponse itself - documentCount there is
// derived (recursive, computed on read), not a stored field, so keeping it out of the stored
// fixture avoids it silently going stale as documents/folders are added or removed in tests.
export interface MockLibraryFolder {
  id: string
  libraryId: string
  parentFolderId: string | null
  name: string
  createdAt: string
}

// Also doubles as the fixture for GET /api/v1/libraries used across the library overview and
// detail pages: 'library-dienstanweisungen' carries myRole VIEWER on purpose, to exercise
// read-only rendering in tests.
export const mockLibraries: LibraryListResponse[] = [
  {
    id: 'library-mine',
    name: 'Meine Dokumente',
    description: 'Private Dokumente',
    ownerType: 'USER',
    reach: { allAccounts: false, groupCount: 0, userCount: 1 },
    myRole: 'OWNER',
    sourceType: 'UPLOAD',
    lastIndexedAt: '2026-08-25T09:30:00Z',
    documentCount: 12,
    createdAt: '2026-03-01T10:00:00Z',
    updatedAt: '2026-03-01T10:00:00Z',
  },
  {
    id: 'library-referat-50',
    name: 'Rechtsquellen Soziales',
    description: 'SGB II, SGB XII, VwVfG, Dienstanweisungen',
    ownerType: 'GROUP',
    reach: { allAccounts: false, groupCount: 0, userCount: 1 },
    myRole: 'MANAGER',
    // #500 review, finding 5: unlike the other fixtures, this one is deliberately not UPLOAD - it
    // is the fixture the indexing-trigger tests use to exercise a successful run.
    sourceType: 'FILESYSTEM',
    lastIndexedAt: '2026-08-18T06:00:00Z',
    documentCount: 431,
    createdAt: '2026-03-01T10:00:00Z',
    updatedAt: '2026-03-01T10:00:00Z',
  },
  {
    id: 'library-dienstanweisungen',
    name: 'Dienstanweisungen',
    description: 'Organisationsweite Vorgaben',
    ownerType: 'GROUP',
    reach: { allAccounts: true, groupCount: 0, userCount: 1 },
    myRole: 'VIEWER',
    sourceType: 'UPLOAD',
    documentCount: 87,
    createdAt: '2026-03-01T10:00:00Z',
    updatedAt: '2026-03-01T10:00:00Z',
  },
  // #423 code review, nit 4: a library where the caller holds OWNER (not just MANAGER), used to
  // exercise AssetGrantService's last-active-OWNER guard (409).
  {
    id: 'library-solo-owner',
    name: 'Projektakte Phoenix',
    description: 'Einzelne Eigentuemerin, kein Ko-Eigentuemer',
    ownerType: 'USER',
    reach: { allAccounts: false, groupCount: 0, userCount: 1 },
    myRole: 'OWNER',
    sourceType: 'UPLOAD',
    documentCount: 0,
    createdAt: '2026-03-01T10:00:00Z',
    updatedAt: '2026-03-01T10:00:00Z',
  },
  {
    id: 'library-s3-protokolle',
    name: 'Protokolle (S3)',
    description: 'Sitzungsprotokolle aus dem Objektspeicher',
    ownerType: 'USER',
    reach: { allAccounts: false, groupCount: 0, userCount: 1 },
    myRole: 'MANAGER',
    sourceType: 'S3',
    documentCount: 0,
    createdAt: '2026-09-06T10:00:00Z',
    updatedAt: '2026-09-06T10:00:00Z',
  },
]

export const mockLibraryDetails: Record<string, LibraryResponse> = {
  'library-mine': {
    id: 'library-mine',
    name: 'Meine Dokumente',
    description: 'Private Dokumente',
    ownerType: 'USER',
    ownerId: 'mock-user-id',
    reach: { allAccounts: false, groupCount: 0, userCount: 1 },
    myRole: 'OWNER',
    documentCount: 12,
    sourceType: 'UPLOAD',
    diagnosticsLocked: true,
    // #1278 review: the mock user is the library's named (ownerId) OWNER here, not merely an
    // admin bypass - the one case holdsIndependentOwnerRole (and this mock field) says true for.
    diagnosticsLockToggleable: true,
    // #1731: das ausgelieferte "nie freigegeben" - die Fremdzugangsfreigabe liefert das Backend
    // erst ab MANAGER, deshalb tragen die Fixtures unterhalb dieser Rolle das Feld gar nicht.
    externalAccess: {
      libraryId: 'library-mine',
      state: 'NEVER_SET',
      expiresAt: null,
      setAt: null,
      setByDisplayName: null,
      tokenCount: 0,
      maxReleaseDays: 365,
    },
    createdAt: '2026-03-01T10:00:00Z',
    updatedAt: '2026-03-01T10:00:00Z',
  },
  'library-referat-50': {
    id: 'library-referat-50',
    name: 'Rechtsquellen Soziales',
    description: 'SGB II, SGB XII, VwVfG, Dienstanweisungen',
    ownerType: 'GROUP',
    ownerId: 'group-referat-50',
    reach: { allAccounts: false, groupCount: 0, userCount: 1 },
    myRole: 'MANAGER',
    documentCount: 431,
    // #500 review, finding 5: unlike the other fixtures, this one is deliberately not UPLOAD - it
    // is the fixture the indexing-trigger tests use to exercise a successful run, since UPLOAD
    // libraries have no run type at all (DocumentIndexingService#executorFor, 409). Also
    // the fixture #479's connector-upload/-delete tests use.
    sourceType: 'FILESYSTEM',
    diagnosticsLocked: true,
    diagnosticsLockToggleable: false,
    externalAccess: {
      libraryId: 'library-referat-50',
      state: 'ACTIVE',
      expiresAt: '2027-03-01T22:59:59Z',
      setAt: '2026-03-01T10:00:00Z',
      setByDisplayName: 'Erika Mustermann',
      tokenCount: 4,
      maxReleaseDays: 365,
    },
    createdAt: '2026-03-01T10:00:00Z',
    updatedAt: '2026-03-01T10:00:00Z',
  },
  'library-dienstanweisungen': {
    id: 'library-dienstanweisungen',
    name: 'Dienstanweisungen',
    description: 'Organisationsweite Vorgaben',
    ownerType: 'GROUP',
    ownerId: 'group-referat-50',
    reach: { allAccounts: true, groupCount: 0, userCount: 1 },
    myRole: 'VIEWER',
    documentCount: 87,
    sourceType: 'UPLOAD',
    diagnosticsLocked: true,
    diagnosticsLockToggleable: false,
    createdAt: '2026-03-01T10:00:00Z',
    updatedAt: '2026-03-01T10:00:00Z',
  },
  'library-solo-owner': {
    id: 'library-solo-owner',
    name: 'Projektakte Phoenix',
    description: 'Einzelne Eigentuemerin, kein Ko-Eigentuemer',
    ownerType: 'USER',
    ownerId: 'mock-user-id',
    reach: { allAccounts: false, groupCount: 0, userCount: 1 },
    myRole: 'OWNER',
    documentCount: 0,
    sourceType: 'UPLOAD',
    diagnosticsLocked: true,
    diagnosticsLockToggleable: true,
    createdAt: '2026-03-01T10:00:00Z',
    updatedAt: '2026-03-01T10:00:00Z',
  },
  'library-s3-protokolle': {
    id: 'library-s3-protokolle',
    name: 'Protokolle (S3)',
    description: 'Sitzungsprotokolle aus dem Objektspeicher',
    ownerType: 'USER',
    ownerId: 'mock-user-id',
    reach: { allAccounts: false, groupCount: 0, userCount: 1 },
    myRole: 'MANAGER',
    documentCount: 0,
    sourceType: 'S3',
    sourceUrl: 'https://minio.intern.example:9000',
    sourceProxy: null,
    sourceInsecureSsl: false,
    sourceCredentialsSet: true,
    sourceSettings: {
      region: 'us-east-1',
      pathStyle: true,
      scopes: [
        { bucket: 'protokolle', prefix: '2025/' },
        { bucket: 'satzungen', prefix: null },
      ],
      includePatterns: ['**/*.pdf'],
      excludePatterns: [],
    },
    diagnosticsLocked: false,
    diagnosticsLockToggleable: false,
    createdAt: '2026-09-06T10:00:00Z',
    updatedAt: '2026-09-06T10:00:00Z',
  },
}

const INITIAL_LIBRARY_DOCUMENTS: Record<string, LibraryDocumentResponse[]> = {
  'library-mine': [
    {
      id: 'document-dienstanweisung',
      fileName: 'dienstanweisung-2024.pdf',
      contentType: 'application/pdf',
      fileSize: 1258291,
      status: 'INDEXED',
      sourceType: 'UPLOAD',
      chunkCount: 34,
      indexedAt: '2026-03-02T09:00:00Z',
      uploadedByUserId: 'mock-user-id',
    },
    {
      id: 'document-rundschreiben',
      fileName: 'rundschreiben-03.docx',
      contentType: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
      fileSize: 84213,
      status: 'PENDING',
      sourceType: 'UPLOAD',
      chunkCount: 0,
      indexedAt: null,
      uploadedByUserId: 'mock-user-id',
    },
    // #1184 (ADR-0022, Entscheidung 5): an indexed mail whose two attachments are their own
    // document rows carrying parentDocumentId - exercises the grouped, collapsible rendering and
    // the attachment-aware search of the documents list in mock/dev mode.
    {
      id: 'document-posteingang',
      fileName: 'posteingang-foerderbescheid.eml',
      contentType: 'message/rfc822',
      fileSize: 45213,
      status: 'INDEXED',
      sourceType: 'UPLOAD',
      chunkCount: 3,
      indexedAt: '2026-03-02T10:00:00Z',
      uploadedByUserId: 'mock-user-id',
    },
    {
      id: 'document-posteingang-anhang-1',
      fileName: 'foerderbescheid.pdf',
      contentType: 'application/pdf',
      fileSize: 220145,
      status: 'INDEXED',
      sourceType: 'UPLOAD',
      chunkCount: 8,
      indexedAt: '2026-03-02T10:01:00Z',
      uploadedByUserId: null,
      parentDocumentId: 'document-posteingang',
    },
    {
      id: 'document-posteingang-anhang-2',
      fileName: 'anlage-berechnung.xlsx',
      contentType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
      fileSize: 18342,
      status: 'INDEXED',
      sourceType: 'UPLOAD',
      chunkCount: 2,
      indexedAt: '2026-03-02T10:02:00Z',
      uploadedByUserId: null,
      parentDocumentId: 'document-posteingang',
    },
    {
      id: 'document-vermerk',
      fileName: 'vermerk.pptx',
      contentType: 'application/vnd.openxmlformats-officedocument.presentationml.presentation',
      fileSize: 512000,
      status: 'FAILED',
      sourceType: 'UPLOAD',
      chunkCount: 0,
      indexedAt: null,
      uploadedByUserId: 'mock-user-id',
    },
  ],
  'library-referat-50': [
    {
      id: 'document-sgb-ii',
      fileName: 'sgb-ii-kommentierung.pdf',
      contentType: 'application/pdf',
      fileSize: 4213456,
      status: 'INDEXED',
      sourceType: 'FILESYSTEM',
      chunkCount: 212,
      indexedAt: '2026-03-01T12:00:00Z',
      uploadedByUserId: null,
    },
    // #743 (review, nit 5): exercises the remote-source branch of the "Original öffnen" action,
    // which since #747 opens through the content endpoint (openDocumentContent) for every
    // sourceType - there was previously no HTTP_DIRECTORY/RSS_FEED document anywhere in the
    // fixtures, so that branch was untestable/unclickable in mock/dev mode.
    {
      id: 'document-intranet-richtlinie',
      fileName: 'richtlinie-datenschutz.pdf',
      contentType: 'application/pdf',
      fileSize: 302145,
      status: 'INDEXED',
      sourceType: 'HTTP_DIRECTORY',
      sourceUrl: 'https://intranet.example.gov/richtlinien/richtlinie-datenschutz.pdf',
      chunkCount: 18,
      indexedAt: '2026-03-01T12:05:00Z',
      uploadedByUserId: null,
    },
  ],
  'library-dienstanweisungen': [],
}

// Mutable copy that the document handlers read and write on GET/POST/DELETE - a plain module-level object
// like the other mock*Details fixtures, but reset between tests via resetMockLibraryDocuments()
// since the handlers mutate document status in place (see resetDocumentMockState).
export let mockLibraryDocuments: Record<string, LibraryDocumentResponse[]> =
  structuredClone(INITIAL_LIBRARY_DOCUMENTS)

export function resetMockLibraryDocuments() {
  mockLibraryDocuments = structuredClone(INITIAL_LIBRARY_DOCUMENTS)
}

// #822: one pre-existing root-level folder on 'library-mine' (the OWNER/UPLOAD fixture already
// used above for canManage-gated document actions) so folder navigation itself has something to
// exercise without every test having to create a folder first.
const INITIAL_LIBRARY_FOLDERS: Record<string, MockLibraryFolder[]> = {
  'library-mine': [
    {
      id: 'folder-protokolle',
      libraryId: 'library-mine',
      parentFolderId: null,
      name: 'Protokolle',
      createdAt: '2026-03-01T10:00:00Z',
    },
  ],
  'library-referat-50': [],
  'library-dienstanweisungen': [],
}

// Mutable copy, mirroring mockLibraryDocuments above - the handlers read and write it on the folder
// CRUD endpoints, reset between tests via resetMockLibraryFolders().
export let mockLibraryFolders: Record<string, MockLibraryFolder[]> =
  structuredClone(INITIAL_LIBRARY_FOLDERS)

export function resetMockLibraryFolders() {
  mockLibraryFolders = structuredClone(INITIAL_LIBRARY_FOLDERS)
}

/**
 * The connectors the mock backend has (GET /api/v1/source-types, ADR-0038) - the six delivered
 * ones with their production abilities, ordered by key like the backend answers.
 */
export const mockSourceTypes: SourceTypeDescriptor[] = [
  {
    type: 'CONFLUENCE',
    displayName: 'Confluence',
    indexingRun: true,
    uploads: false,
    pushIntake: true,
    browsable: true,
    profileSupport: 'FORBIDDEN',
    authMethods: [],
    fullSyncIntervalDefaultDays: 7,
  },
  {
    type: 'FILESYSTEM',
    displayName: 'Dateisystem',
    indexingRun: true,
    uploads: false,
    pushIntake: false,
    browsable: false,
    profileSupport: 'FORBIDDEN',
    authMethods: [],
  },
  {
    type: 'GOOGLE_DRIVE',
    displayName: 'Google Drive',
    indexingRun: true,
    uploads: false,
    pushIntake: false,
    browsable: true,
    profileSupport: 'FORBIDDEN',
    authMethods: [],
    fullSyncIntervalDefaultDays: 7,
  },
  {
    type: 'HTTP_DIRECTORY',
    displayName: 'Webverzeichnis',
    indexingRun: true,
    uploads: false,
    pushIntake: false,
    browsable: false,
    profileSupport: 'FORBIDDEN',
    authMethods: [],
  },
  {
    type: 'RSS_FEED',
    displayName: 'RSS-Feed',
    indexingRun: true,
    uploads: false,
    pushIntake: false,
    browsable: false,
    profileSupport: 'FORBIDDEN',
    authMethods: [],
  },
  {
    type: 'S3',
    displayName: 'S3-Objektspeicher',
    indexingRun: true,
    uploads: false,
    pushIntake: true,
    browsable: true,
    profileSupport: 'FORBIDDEN',
    authMethods: [],
  },
  {
    type: 'UPLOAD',
    displayName: 'Upload',
    indexingRun: false,
    uploads: true,
    pushIntake: false,
    browsable: false,
    profileSupport: 'FORBIDDEN',
    authMethods: [],
  },
]

/** #1134: spaces the mock Confluence token may read (source of the wizard's space selection). */
export const mockConfluenceSpaces: ConfluenceSpaceRef[] = [
  { key: 'BAU', name: 'Bauamt' },
  { key: 'HR', name: 'Personal und Organisation' },
  { key: 'IT', name: 'IT-Betrieb' },
  { key: 'KAEM', name: 'Kämmerei' },
  { key: 'RECHT', name: 'Rechtsamt' },
]
