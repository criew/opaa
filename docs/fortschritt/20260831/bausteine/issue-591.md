# Issue #591 — feat(frontend): Eingabezeile mit Suchbereichs-Statuszeile und @-Vorschlag im neuen Design
- Geschlossen: 2026-08-20 (completed)
- Labels: frontend, size:M
- PRs: #672 (2026-08-20)

**Laut Issue:** `ChatInput` nach Mockups 1a/1h gestalten — Platzhaltertext, „Fragen"-Knopf, Statuszeile mit Anzahl durchsuchter Bestände, @-Vorschlagsliste mit Präfix-Hervorhebung und Typzeile, Tastaturnavigation erhalten.

**Geliefert:** Wie gefordert. Statuszeile ersetzt den bisherigen Enter-Hinweis und zeigt „Durchsucht: n lesbare Bestände" bzw. bei @-Eingrenzung „n gewählte Bestände" bzw. ehrlich „nichts" bei leerem Suchbereich. @-Vorschlagsliste mit fettem Präfix und Typ-Badge „Bibliothek · verengt die Suche" (nimmt Agenten als zweiten Typ vorweg). Im PR benannte bewusste Einschränkung: Die Zählung bleibt beim heutigen Modell (@Alles-Wissen = alle lesbaren Bibliotheken), da Space-Datenquellen erst mit #203 kommen — keine Abweichung vom Issue, sondern dort bereits so vorgesehen.

**Verifikation:** `frontend/src/components/chat/ChatInput.tsx` existiert im aktuellen Code.

**Themen:** frontend, chat, ui, retrieval, barrierefreiheit
