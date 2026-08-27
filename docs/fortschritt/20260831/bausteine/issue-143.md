# Issue #143 — feat(security): Vollständigkeit nach DSGVO — Löschung, Selbstauskunft und Datenschutzhinweis
- Geschlossen: 2026-08-24 (not planned)
- Labels: enhancement
- PRs: keine

**Laut Issue:** OPAA erhebt personenbezogene Daten (Nutzerkonten, Space-/Gruppenmitgliedschaften, Asset-Rechtezuweisungen), hat aber weder Löschweg noch Selbstauskunft noch Datenschutzhinweis. Gefordert waren vier Ergebnisse: ein Datenschutzhinweis (Art. 13/14), eine vollständige Kontolöschung (Art. 17) ohne Blockade durch Asset-Eigentum, eine ausschließlich von der betroffenen Person selbst auslösbare Selbstauskunft/Datenübertragbarkeit (Art. 15/20), und Pseudonymisierung/Befristung der Netzadressen in der Ratenbegrenzung.

**Geliefert:** Nicht umgesetzt. Das Issue wurde als Ticket-Hygiene-Maßnahme geschlossen (Maintainer-Entscheidung): Die DSGVO-Vollständigkeit wird bewusst vor einem Produktivbetrieb in einer Behörde zurückgestellt und dann mit aktuellem Zuschnitt neu aufgesetzt — analog zum urverwandten Issue #798, das den Selbstauskunfts-Aspekt trug. Die fachliche Grundlage bleibt in `docs/features/security-and-compliance.md` dokumentiert. Vor der Schließung gab es noch eine inhaltliche Aktualisierung (neu erhobene Bestandsaufnahme, Streichung des Auftragsverarbeitungsvertrag-Punkts zugunsten des "Vorrangs eigener Modelle" nach ADR-0014) — das war jedoch reine Spezifikationspflege, kein Code.

**Verifikation:** Keine Konto-Lösch- oder Selbstauskunft-Endpunkte im Code gefunden (`grep` auf `deleteAccount`/`accountDeletion`/`DataExport` in `io.opaa.auth` ohne Treffer). Deckt sich mit "nicht umgesetzt, zurückgestellt".

**Themen:** dsgvo, security, doku, auth, retrieval
