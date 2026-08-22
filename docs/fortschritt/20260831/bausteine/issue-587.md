# Issue #587 — feat(frontend): App-Shell und Seitenleiste nach Zielbild — Space-Wechsler, Chats, Bereichsnavigation
- Geschlossen: 2026-08-20 (completed)
- Labels: enhancement, frontend, size:L
- PRs: #652 (2026-08-20)

**Laut Issue:** Seitenleiste nach Mockup 1a umbauen — Space-Wechsler als prominentes Dropdown, Chats des aktiven Space in der Mitte, Bereichs-Navigation und Nutzer-Badge unten; „Katalog" entfällt, „Als PDF exportieren"/„Archivieren" sind nicht Teil des Issues; Mobile-Verhalten anpassen; bestehende Sidebar-Tests aktualisieren.

**Geliefert:** Wie gefordert. Navy-Leiste in beiden Farbschemata über verschachtelten `ThemeProvider`, Space-Wechsler mit Art und Mitgliederzahl je Space, Chats unverändert aus `ChatList`, Bereichs-Navigation unten (inkl. „Branding" und „Gruppen" für Systemverwaltung), Nutzer-Badge mit Menü für Einstellungen/Abmelden. Bewusste Abweichung vom Mockup, im PR benannt: die Mockup-Angabe „n Quellen" je Space wird durch die Mitgliederzahl ersetzt, weil die Listen-API noch keine Quellenzahl liefert (Lücke bereits in #593 vermerkt). Mobile Drawer/`MobileHeader` blieben unangetastet.

**Verifikation:** `frontend/src/layouts/Sidebar.tsx` existiert im aktuellen Code.

**Themen:** frontend, ui, navigation, spaces, design
