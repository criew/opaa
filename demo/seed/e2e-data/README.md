# E2E-Datenprofil: Rohdateien

Frozen fixture content for `demo/seed/profiles.py`'s `E2E_PROFILE` and for the E2E Playwright
suite's own UI-driven scenarios (Issue #233) - not the demo (Rheinfurt) corpus, that lives under
`demo/corpus/`.

- `test-documents/seed/e2e-basisdokument.txt` — the **only** file the seed itself uploads
  (`E2E_SEED_UPLOAD_ROOT` in `profiles.py`), into the pre-existing "E2E Wissensbibliothek". Every
  other file below is left alone by the seed; individual `e2e/tests/*.spec.ts` files upload/index
  them themselves as part of what they actually test (upload, share, connector creation, …).
- `test-documents/*.txt` (outside `seed/`) — fixtures individual specs upload through the UI
  (`knowledge-libraries.spec.ts`, `space-chats.spec.ts`). Deliberately not reused as the seed's own
  upload content - see `profiles.py`'s module comment for why that would break
  `knowledge-libraries.spec.ts`'s exclusivity assertions.
- `test-documents/formatdokument.*` — one small file per admitted upload format for
  `format-pipelines-upload.spec.ts`. DOCX, PPTX, ODT, ODP and Markdown were generated for this
  suite; CSV, ODS, DOC and MSG are copies from `demo/corpus/formate/` (`08`, `09`, `13`, `14`). The
  MSG is an Apache POI test file under the Apache License 2.0, see `demo/corpus/SOURCE.md` and
  `demo/corpus/THIRD-PARTY-LICENSES/Apache-POI-testdata-Apache-2.0.txt`.
- `test-documents/metadatendokument-*.txt` — the two documents of `library-metadata.spec.ts`.
- `rss-feed/htdocs/` — static docroot for `e2e/docker-compose.e2e.yml`'s `rss-feed` service, mounted
  read-only. **Not a seed input at all** - `RssFeedIndexingExecutorTest`-style scenarios
  (`rss-feed-library.spec.ts` #471, `knowledge-library-nacharbeiten.spec.ts` #514) point library
  *creation* through the UI at this service directly; the seed never touches it.
