# Gruppierung der Bausteine nach Themenbereichen — Zeitraum 30.09.–31.10.2026

Gruppiert wird wie am [Vorgänger-Stichtag](../20260930/gruppierung.md) entlang der **elf
Themenbereiche A–K der [Produktvision](../../VISION.md)**, ergänzt um Produktvision (V), Projekt als
Produkt (P), Projektsetup (T1), Agenten-Organisation (T2) und Testinfrastruktur (T3).

Jeder der 845 geprüften Vorgänge ist genau **einem** Bereich zugeordnet; die 73
Renovate-Updates stehen gesammelt unter T1. `#N` = Issue, `PR#N` = PR ohne Issue-Verknüpfung.
Befunde stehen in [bausteine.md](./bausteine.md).

**Regel für den Report:** Der Report zeigt nur den **Endzustand** des Zeitraums. Was im Zeitraum
gebaut und wieder ersetzt wurde, ist dort kein Leistungsposten. Das betrifft den Übergangsweg mit
Startwerten und Seedern (#3189), die Übernahme des Embedding-Modells aus der Konfiguration (#3191),
bge-m3 als Konfigurationsvorgabe (PR#2618, #2386), den Zugangswunsch (#2494), die Recovery über
`ProcessStart` (#2899), die Liquibase-Teile der Migrationsregeln (#2944), das SharePoint- und
Dropbox-Formular (PR#2444), das erste Abhängigkeits-Gate (PR#3443) und die 14-Tage-Regel für
Gradle-Plugins (#3391).

---

## A · Wissensschicht & Retrieval

Feinschliff der Abfragestrecke nach Messung: top-k 20, Nachbar-Chunks im Antwortkontext, ein Chunk
je Dokument, Quote je Teilfrage, Spitzentreffer je Liste, Relevanzschnitt (aus); bge-m3 als
gemessenes Embedding-Modell, HNSW-Trefferzahl und geschützte Bezeichner im Volltext; Zitierposition
der Bibliotheksfelder entfernt.

#2041, #2298, #2300, #2320, #2334, #2338, #2342, #2343, #2345, #2363, #2373, #2374, #2375, #2376,
#2386, #2437, #2502, #2507, #2514, #2576, #2606, #2611, #2612, #2614, #2619, #2620, #2621, #2622,
#2623, #2624, #2634, #2703, #3226, #3279, PR#2592, PR#2618

(36 Vorgänge, davon 9 mit Befund)

---

## B · Wissensquellen & Konnektoren

Datei-Konnektoren auf gemeinsamem Datei-Abgleich (Epic #2146: Google Drive, Nextcloud, SMB,
SharePoint, Dropbox), Zugänge, verbundene Konten und private Bibliotheken mit OAuth-Kern (Epic
#2147), OneDrive for Business, Vereinfachung der Verbindungen mit globalem Ausgangsproxy (Epic
#2492), Umfang „Alle“, Fundstellen-Anker je Chunk (ADR-0062) und Feed-Einstellungen je
RSS-Bibliothek.

#2124, #2127, #2128, #2146, #2147, #2148, #2149, #2151, #2152, #2153, #2154, #2155, #2159, #2160,
#2161, #2162, #2163, #2164, #2165, #2166, #2167, #2168, #2169, #2178, #2184, #2192, #2201, #2202,
#2204, #2216, #2218, #2219, #2220, #2221, #2226, #2236, #2241, #2246, #2249, #2255, #2265, #2266,
#2268, #2271, #2273, #2276, #2279, #2284, #2294, #2295, #2297, #2327, #2328, #2329, #2330, #2331,
#2365, #2408, #2413, #2417, #2428, #2430, #2467, #2472, #2479, #2480, #2482, #2490, #2492, #2494,
#2495, #2496, #2811, #2814, #2911, #3083, #3110, #3117, #3124, #3147, #3156, #3175, #3315, #3396,
PR#2185, PR#2189, PR#2191, PR#2195, PR#2196, PR#2197, PR#2199, PR#2211, PR#2212, PR#2223, PR#2224,
PR#2225, PR#2227, PR#2228, PR#2233, PR#2234, PR#2235, PR#2242, PR#2250, PR#2254, PR#2259, PR#2264,
PR#2269, PR#2272, PR#2274, PR#2286, PR#2288, PR#2409, PR#2444, PR#2503, PR#3130, PR#3148, PR#3322,
PR#3351

(118 Vorgänge, davon 11 mit Befund)

---

## C · Spaces, Assets & Verteilung

Ein Katalog als einziger Einstieg, Favoriten und die Space-Zuordnung als harte Suchgrenze (Epic
#2070, ADR-0039); Anweisung je Space (ADR-0043), Chat-Aufräumen je Space, vereinfachte Space-Anlage,
„Privater Bereich“. Zurückgebaut: Schaufenster `listed`, Space-Sichtbarkeit, Übergangsregel und
Strikt-Modus.

#1637, #1923, #2070, #2091, #2092, #2093, #2094, #2095, #2096, #2097, #2098, #2099, #2129, #2130,
#2131, #2134, #2156, #2180, #2205, #2207, #2208, #2302, #2585, PR#2111, PR#2114, PR#2115

(26 Vorgänge, davon 7 mit Befund)

---

## D · Agenten, Prompts & Werkzeuge

MCP-Server als Profilart von Zugängen (Grundlage für Werkzeuge).

#2173

(1 Vorgang, ohne Befund)

---

## E · Modelle & zentrale Steuerung

Installationsweite Feature-Flags (Epic #2586, ADR-0044), Denktext von Reasoning-Modellen entfernt,
Modelle am Endpunkt ermitteln, Rerank- und Transkriptionsmodell in der Modellverwaltung, geführter
Wechsel des Embedding-Modells (ADR-0061).

#2123, #2486, #2488, #2509, #2511, #2586, #2587, #2588, #2589, #2632, #3207, #3214

(12 Vorgänge, davon 0 mit Befund)

---

## F · Identität, Rechte & Mandanten

Erneute Anmeldung vor sensiblen Vorgängen, Fehlversuchssperre als Einstellung, Notanker-Schutz,
ruhende Konten nach Inaktivität, Übergabe mit Zeilensperre, Seiten „Anlegerechte“, „Ohne
Zuständigkeit“ und Verzeichnisabgleich überarbeitet.

#1987, #1988, #2174, #2251, #2260, #2263, #2289, #2321, #2336, #2340, #2341, #2498, #2902, #2910,
#2979, #2996, #3062, #3394, PR#2187, PR#2231, PR#3348

(21 Vorgänge, davon 0 mit Befund)

---

## G · Sicherheit, Nachweis & Prüfbarkeit

Revisionsprotokoll mit Ereigniskatalog, Prüfsummenkette und SIEM-Ausleitung (Epic #1296, ADR-0055,
ADR-0059), Fakten-Manifest mit Wächter und Faktenblättern (Epic #2543) und Update-Paket (Epic
#2552), Prompt-Injection-Schutz (Epic #3008), KI-Kennzeichnung (Epic #3010), Schadprogrammprüfung,
Schutz gegen DNS-Rebinding (ADR-0063), Schlüsselwechsel (ADR-0064) und das Security-Audit 2026 (Epic
#3325) mit signierten Images, CodeQL und Dependency Verification.

#1296, #1860, #1991, #2121, #2170, #2171, #2270, #2470, #2537, #2543, #2546, #2547, #2548, #2549,
#2550, #2551, #2552, #2553, #2554, #2555, #2558, #2559, #2561, #2772, #2785, #2799, #2812, #2819,
#2824, #2844, #2850, #2851, #2852, #2853, #2854, #2855, #2859, #2860, #2900, #2901, #2903, #2905,
#2906, #2909, #2912, #2972, #3001, #3007, #3008, #3009, #3010, #3011, #3012, #3014, #3018, #3024,
#3026, #3039, #3040, #3042, #3044, #3046, #3047, #3048, #3049, #3050, #3052, #3053, #3059, #3096,
#3118, #3119, #3120, #3121, #3122, #3135, #3172, #3220, #3224, #3239, #3251, #3256, #3282, #3325,
#3326, #3327, #3328, #3329, #3330, #3331, #3332, #3333, #3335, #3336, #3337, #3338, #3339, #3340,
#3341, #3342, #3343, #3344, #3345, #3346, #3347, #3352, #3353, #3354, #3355, #3356, #3357, #3358,
#3360, #3374, #3375, #3376, #3377, #3378, #3379, #3380, #3381, #3382, #3383, #3384, #3385, #3387,
#3391, #3405, #3419, #3420, #3426, #3427, #3430, #3431, #3434, PR#2287, PR#2292, PR#2836, PR#3067,
PR#3074, PR#3089, PR#3097, PR#3371, PR#3422, PR#3437, PR#3443

(146 Vorgänge, davon 12 mit Befund)

---

## H · Monitoring, Kosten & Governance

Im Zeitraum ohne eigenen Vorgang.

(kein Vorgang)

---

## I · Kanäle & Oberflächen

UI-Politur und Admin-Seiten (Epics #1441, #1928), Quelleinrichtung nach UI-Lab-Variante F (Epic
#3104), Sprung an die Fundstelle mit PDF-Viewer und Textfassung (Epic #3260), UI-Review vom 28.10.
(Epic #3269), Spracheingabe Stufe 1, Chatexport, WCAG-AA-Kontrast als Regel.

#1441, #1925, #1926, #1928, #1995, #2077, #2085, #2135, #2139, #2150, #2193, #2215, #2303, #2377,
#2416, #2493, #2504, #2506, #2518, #2538, #2540, #2596, #2604, #2749, #2752, #2907, #2908, #2941,
#3084, #3102, #3104, #3105, #3106, #3107, #3108, #3109, #3125, #3127, #3140, #3144, #3152, #3159,
#3162, #3163, #3165, #3195, #3211, #3217, #3218, #3240, #3242, #3259, #3260, #3261, #3262, #3263,
#3264, #3265, #3266, #3267, #3268, #3269, #3276, #3283, #3284, #3285, #3286, #3287, #3292, #3298,
#3300, #3305, #3306, #3307, #3316, #3317, #3318, #3319, #3320, #3321, #3445, #3450, #3452, PR#2508,
PR#2629, PR#3082, PR#3186, PR#3297

(88 Vorgänge, davon 13 mit Befund)

---

## J · Betrieb & Deployment

Kubernetes mit Helm (Epic #2346, ADR-0042), Mehrinstanzbetrieb aktiv/aktiv (Epic #1292, ADR-0053),
schlanke Konfiguration mit Installationsmodus und Einrichtungs-Assistent (Epic #3160, ADR-0060),
Release-Zyklus mit Supportlinien (ADR-0054), Offline-Bündel, Update-Paket mit Handbuch, automatische
Wiederherstellungsprobe.

#1292, #2346, #2347, #2348, #2349, #2350, #2351, #2352, #2353, #2354, #2355, #2356, #2357, #2358,
#2364, #2368, #2387, #2388, #2393, #2394, #2395, #2396, #2397, #2398, #2399, #2401, #2410, #2418,
#2423, #2432, #2433, #2448, #2449, #2458, #2460, #2463, #2556, #2557, #2575, #2794, #2840, #2889,
#2894, #2899, #2904, #2943, #2944, #2945, #2946, #2947, #2948, #2949, #2950, #2951, #2952, #2968,
#2975, #2977, #2998, #2999, #3004, #3017, #3020, #3022, #3028, #3113, #3133, #3160, #3189, #3190,
#3191, #3192, #3193, #3194, #3200, #3201, #3208, #3209, #3210, #3234, #3237, #3244, #3245, #3246,
#3250, #3252, #3253, #3257, #3258, #3395, #3397, #3418, #3428, PR#2450, PR#2459, PR#2955, PR#2957,
PR#2966, PR#3202, PR#3203

(100 Vorgänge, davon 7 mit Befund)

---

## K · Verwaltungs-Spezifika

Behördenunterlagen (Dach-Epic #2542) mit redaktionellen Vorlagen (Epic #2560), Selbstauskunft Stufe
1 (Epic #2986), KI-Kennzeichnung nach Art. 50 KI-VO, Auskunft über die Datenerhebung für die
Personalvertretung.

#2542, #2545, #2560, #2562, #2563, #2564, #2565, #2566, #2567, #2568, #2569, #2826, #2827, #2856,
#2895, #2984, #2985, #2986, #2987, #2988, #2989, #2990, #2991, #2992, #2993, #2997

(26 Vorgänge, davon 3 mit Befund)

---

## T1 · Projektsetup, Build & Werkzeugkette

Architektur-Review des Backends (Epic #2678) mit Architekturvertrag und Ratschen (ADR-0051),
Frontend-Gliederung nach Themen per ESLint, Liquibase durch Flyway ersetzt (ADR-0056), UI-Lab als
wiederholbarer Ablauf.

#2641, #2642, #2643, #2644, #2645, #2646, #2647, #2648, #2649, #2650, #2651, #2652, #2653, #2654,
#2655, #2656, #2657, #2658, #2659, #2660, #2661, #2662, #2663, #2678, #2679, #2680, #2681, #2682,
#2683, #2684, #2685, #2687, #2688, #2689, #2690, #2691, #2692, #2693, #2694, #2695, #2704, #2715,
#2716, #2720, #2724, #2741, #2753, #2758, #2761, #2777, #2782, #2783, #2804, #2825, #2847, #2913,
#3019, #3100, #3164, #3168, #3169, #3170, #3171, #3439, PR#2919, PR#2921, PR#2926, PR#2928, PR#2930,
PR#2931, PR#2933, PR#2934, PR#3143, PR#3149, PR#3157, PR#2072, PR#2073, PR#2074, PR#2075, PR#2076,
PR#2083, PR#2084, PR#2108, PR#2109, PR#2110, PR#2140, PR#2141, PR#2142, PR#2143, PR#2144, PR#2229,
PR#2230, PR#2315, PR#2316, PR#2317, PR#2318, PR#2319, PR#2323, PR#2324, PR#2325, PR#2326, PR#2379,
PR#2380, PR#2381, PR#2382, PR#2383, PR#2451, PR#2452, PR#2453, PR#2454, PR#2455, PR#2474, PR#2475,
PR#2476, PR#2477, PR#2478, PR#2523, PR#2524, PR#2525, PR#2526, PR#2578, PR#2579, PR#2580, PR#2581,
PR#2582, PR#2667, PR#2668, PR#2669, PR#2670, PR#2807, PR#2808, PR#2809, PR#2871, PR#2872, PR#2873,
PR#2874, PR#2875, PR#2878, PR#2879, PR#2880, PR#2881, PR#2882, PR#2936, PR#2937, PR#2938, PR#2939,
PR#2940, PR#3390

(148 Vorgänge einschließlich 73 Renovate-Updates, davon 3 mit Befund)

---

## T2 · Agenten-Organisation & Projektsteuerung

Rolle „Behördenkontakt“ (Epic #2570), Kommentarregel repo-weit per CI, gekürzte AGENTS.md-Dateien,
ADRs auf den gültigen Endstand neu gefasst.

#2089, #2422, #2570, #2571, #2572, #2573, #2574, #2590, #2696, #2697, #2698, #2699, #2700, #2701,
#2702, #2711, #2759, #2760, #2762, #2766, #2770, #2775, #2781, #2789, PR#2745, PR#2764, PR#2768

(27 Vorgänge, davon 0 mit Befund)

---

## T3 · Testinfrastruktur & E2E

Teststufen für Konnektoren (Epic #1293, ADR-0057), E2E-Lücken (Epic #3462) mit Firefox-Lauf und axe
am Endzustand, E2E-Alarme mit Fix-PRs und Flaky-Fixes.

#1293, #1905, #2079, #2175, #2194, #2238, #2252, #2281, #2313, #2415, #2436, #2516, #2600, #2686,
#2793, #2796, #2798, #2803, #2806, #2823, #2831, #2838, #2843, #2845, #2858, #2868, #2870, #2876,
#2877, #2884, #2887, #2898, #2962, #2973, #2981, #2994, #3013, #3051, #3079, #3093, #3098, #3129,
#3136, #3139, #3179, #3182, #3204, #3212, #3230, #3235, #3289, #3311, #3359, #3388, #3432, #3460,
#3462, #3463, #3464, #3465, #3466, #3467, #3474, #3475, PR#2243, PR#2261, PR#2262, PR#2469, PR#3151,
PR#3185

(70 Vorgänge, davon 2 mit Befund)

---

## V · Produktvision, Strategie & Konzeption

Spezifikationen zu Verbindungen, schlanker Konfiguration und Modellwechsel; Feedbackwünsche (Epic
#2305) bewusst nicht geplant.

#2305, #2306, #2307, #2308, #2309, #2544, #2705, PR#2158, PR#2217, PR#3188, PR#3231

(11 Vorgänge, davon 5 mit Befund)

---

## P · Projekt als Produkt: Öffentlichkeit, Demo & Governance

Produkthandbuch fertig und als Kapitelbaum gegliedert (Epic #1282), Demo-Seed, Roadmap-Meilensteine,
openCode und publiccode.yml.

#1282, #2068, #2071, #2082, #2103, #2527, #2528, #2530, #2531, #2535, #2672, #3021, PR#2119,
PR#2584, PR#3087

(15 Vorgänge, davon 0 mit Befund)
