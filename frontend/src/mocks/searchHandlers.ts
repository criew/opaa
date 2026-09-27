import { http, HttpResponse } from 'msw'
import { mockMetadataFilterOptions } from './searchFixtures'

export const searchHandlers = [
  // the Füllstand and the occurring values of the filterable core fields for the caller's
  // search scope. The mock's bestand offers the Dokumentart (above the 0.90 threshold) but not
  // the date (below 0.75), so both states of the filter interface are exercised in dev mode.
  http.get('/api/v1/search/metadata-filter-options', () =>
    HttpResponse.json(mockMetadataFilterOptions),
  ),
]
