"""Pinned selection of the LHM-Dienstleistungen-Corpus raw files used as source
material for the Rheinfurt "Meldewesen & Ausweise" and "Kfz-Zulassung" libraries.

See ``demo/generator/README.md`` for the reproduction procedure and
``demo/corpus/SOURCE.md`` for licensing details (MIT,
https://huggingface.co/datasets/it-at-m/LHM-Dienstleistungen-Corpus).

Each raw file is one plain-text service description of the Munich city
administration. The commit below pins the exact dataset revision these files
were retrieved from, so a re-run downloads byte-identical input even if the
dataset's default branch moves later.
"""

from __future__ import annotations

import hashlib
import sys
import urllib.parse
import urllib.request
from pathlib import Path

SOURCE_COMMIT = "3def28953f6d8d65bde7b6b3956fe36c9791a4de"
SOURCE_REPO = "it-at-m/LHM-Dienstleistungen-Corpus"
SOURCE_LICENSE = "MIT"
RETRIEVED_AT = "2026-08-21"

HF_BASE_URL = f"https://huggingface.co/datasets/{SOURCE_REPO}/resolve/{SOURCE_COMMIT}/"

GENERATOR_DIR = Path(__file__).resolve().parent
RAW_SOURCE_DIR = GENERATOR_DIR / "raw-source" / "lhm-dienstleistungen"

# --- Selected raw files, pinned by SHA-256 ----------------------------------
#
# Selection rationale: a curated subset of the ~740 service descriptions,
# chosen to match the two HTTP_DIRECTORY libraries from
# docs/features/demo-instance.md ("Leistungen Meldewesen & Ausweise" and
# "Leistungen Kfz-Zulassung"). Curated by topic, not sampled, so the resulting
# library reads like a coherent Bürgerbüro catalogue rather than a random cut
# of the full corpus.

RAW_FILE_SHA256: dict[str, str] = {
    "Personalausweis.txt": "8a368683d363b0f4b33d9244c1f979f231e12d7d4727dde835181ddf1a2c7574",
    "Personalausweis oder Reisepass abholen.txt": "27ffa725bd9275d9f77e72659eb8e6f1d741c5ce519ff4ec27534b5068a7384a",
    "Reisepass.txt": "78ce651dc5bd44c46976a51c7a6bb63b75c237677a5210ba12deeca9c794b78a",
    "Vorläufiger Reisepass.txt": "011110f25ed0ee899c107077dc1b712e976dba6b9d309d752544e43cbe6beade",
    "Kinderreisepass (bis 12 Jahre) beantragen.txt": "d25955458510bbaab65b04afc59db11faf20a43ede05ed14c0aaa068db8cbc7b",
    "Zweitpass, weitere Reisepässe.txt": "b73425a356efb4ad2824f3bda3e28c6c17f6141fc533cdcdead98ea48c2acb7d",
    "Verlust oder Diebstahl Personalausweis.txt": "2093ae6904ceb5fc5a3bc75dcc92e3ca4f9679ab896bdad4fb89256e1d054171",
    "Verlust oder Diebstahl Reisepass.txt": "27a6f5fbb2f503298567d8aa7f55e9be3e6f733ea602df6a099bebd349d8d86a",
    "Widerruf der Verlust- oder Diebstahlanzeige von Personalausweis oder Reisepass.txt": "538d5e7064e5811383c4efa3413654f1233d50941ab5dfee42f1866d7f2d6918",
    "Nachträgliche Anschriftenänderungen im Personalausweis, Reisepass, elektronischem Aufenthaltstitel.txt": "26dc7737db5deeebb98e7e200c302ab586e73fc316064333be17f7d967fb2980",
    "Nachträgliches Einschalten eID-Funktion oder Änderung der PIN.txt": "4e460b60b5c46d78702af8d8c1f157c9913ebe545e9de0beaa7ba1508537189a",
    "eID-Karte beantragen (EU-EWR).txt": "ee66f875af882b27756f1a8ee0e96f417ef153231901562cf2ece8548b589c80",
    "Verlust oder Diebstahl der eID-Karte.txt": "d8e45d630c9ce7e4463a19bb2c20b8c6405bde34ff61d2e91928dd5053a68cdf",
    "Widerruf Verlust oder Diebstahl der eID-Karte.txt": "dbfa7b124f9796e11d6ff8563e8eda9556dd52a729ffb8aa9314b3e9b24034d1",
    "Befreiung von der Ausweispflicht.txt": "0aea0441896ce464b89b2bb855812bb1fdaabf6aa38080d80925bae954bca30e",
    "Ausweisdokumente für die ganze Familie.txt": "f188b8e17b60ccec5be0a3eb97061b1f1a570e9b833e02fe6ea71d9e26f44d8d",
    "Führungszeugnis.txt": "a69b26d3accecb4376a8b366183706719db24369e3a07c8e6af65eb24baba813",
    "Führungszeugnis für Ehrenamtliche.txt": "3f750fae8cd0267b0f7640f946f5e30a0abb2a761ae0b11bed95d6a8c7b89dfd",
    "Beglaubigung von Unterschriften.txt": "0087453498ce12effac25d70daba08ac80f4757ebef8c337e9c3f5227583ed9c",
    "Beglaubigung von Dokumenten bis zu 5 Dokumenten.txt": "0d66962a0f795827f527e5d3d1c862442b6e335ef068c86941dc52fe567969eb",
    "Beglaubigung von bis zu 20 Dokumenten.txt": "5a27eecf8f50a111d20adb6d38edc24fd0d271f6ff0c5a0614ed825d7f93eeb0",
    "Beglaubigung von mehr als 20 Dokumenten.txt": "509072165f80a4f0a30d5c808da99c379569e7eb927fa4d3e4a1807a87d71c9e",
    "Beglaubigung für Rentenzwecke.txt": "4a2190074f089267f049bded480ace9ec509c8dc1f01282c3adafb53899812ba",
    "Wohnsitz anmelden oder ummelden.txt": "9c042cc6f7c907dbcd2caf14cbb0c0b394a18684d3c0ca041f76b58767fe179b",
    "Wohnsitz abmelden.txt": "5da901a23ae370a23b89dff7422d142998664a3ec49d8d043211374fd4cba0d8",
    "Melderegisterauskunft.txt": "173de5210ce236c2d8f3227e6ebe646619b1270f84eaa12f22d55efc27d64b22",
    "Melderechtliche Bescheinigung.txt": "01894c8dbb71f825e074245bd13374bf82fb273c5343892de32f1e90e989e927",
    "Haushaltsbescheinigung beantragen.txt": "48c34a6ba812d389bac7f5e2d03a0e2ca9ce33528ab2aa71d8630b53e27003d3",
    "Lebensbescheinigung beantragen.txt": "fa38bbe56a753fca44df465d2ecd62068040f656a30c4878d4aed842c30795f7",
    "Auskunftssperre einrichten.txt": "5b6db5fc13985b298e88c17694ea14d6150b8ae2ec56b50159ec1978231145ba",
    "Übermittlungssperre von Daten.txt": "473feb7ffdb435f56c603f9797c0ac47ddb1c610586ca4a725b1fa3c1851c717",
    "Geburtsurkunde – Erwachsene und Kinder ab 3 Monate.txt": "e35d9db5a5101077517aa3062f198e2a71a0aea3cc0fd0a39c36baeb5e8c7115",
    "Geburtsurkunde – Neugeborene.txt": "fe147203c780131d0f0178d008827d441fa2788e487e16e9a44509a04864886a",
    "Sterbeurkunde.txt": "97784a66ec81fb29300be924f168ea172f518161bf719578ae6ebda5422a5253",
    "Ehefähigkeitszeugnis.txt": "247591be8f3d1afd85f0e82850733854bd3ac5c92977ed41db353cbf5b9d2d1f",
    "Anmeldung einer Eheschließung.txt": "c4f85994dc0f714fbe82399251577defc546894cbf0834be46103ebfc56480cf",
    "Namensänderung.txt": "2b285245f6decec196bca38c4009b7b289234f9a52328fa2c82e988fe0c0e606",
    "Namensänderung nach Heirat oder Scheidung.txt": "2bdde07cec0df003cfea40c7bf618021f4cd2e99399a5c110ce8d7617012405b",
    "Namensänderungen für Kinder.txt": "c2bb6728ca83b4adf84960cf31c8dcd0d1bc61d3784c9400e4ce774766061cc0",
    "Kirchenaustritt erklären.txt": "baa0c645cd02d32977178817aa7b485ad19e6b76124ffd344cc4c866314da20f",
    "Kirchenaustrittsbescheinigung.txt": "58c4ab5abebf67dc657b933814cc280ec01c2ca40da3d54593b2068140fb50dd",
    "Personendaten ändern.txt": "8b37f19c563633bfd57dee3ecd2da5983ca1fb6f97893bc8cf1b7d5f34ed6874",
    "Wählbarkeitsbescheinigung.txt": "110e17bcb8c13bb330a97bd639773e31968f5be4efced5e2aecec6de87c4944f",
    "Aufnehmen in das Wählerverzeichnis.txt": "d5e6ba9040d39ad8ebbb941430fc0f1baabcfa3a24d03ce2d0d7c1e8a06c2cc2",
    "Briefwahl beantragen.txt": "9c337d5f947b295570f21558aa9dcc3958c4b6a8bd15196d09015e21a2b7253c",
    "Sorgeerklärung beurkunden.txt": "db5df2812f95ce2c6f6f06961fd4685e81536631d4a1f4c1ecd77ec2c3ba3ce7",
    "Fabrikneues Fahrzeug anmelden.txt": "8809154f9231b3aa4b7dd0246e55d578f35eec28cdc9b8c1295141759fd2b886",
    "Aus dem Ausland eingeführtes Fahrzeug anmelden.txt": "e0927623cede382db386ecbae712e3bf8617dfcab72a54d6e0e076b34144427a",
    "Fahrzeug umschreiben innerhalb Münchens.txt": "b78bb72be2cc09dfd792c185c0cc2fe1f0cf86e7e1f2c91f67412e4f6af97b6c",
    "Fahrzeug umschreiben von außerhalb nach München.txt": "0335201346e16bfae4ea925233d6ef565f255583bd46dec7043d73fb719fadd8",
    "Fahrzeug abmelden.txt": "59f9948c419085273fd38644e540e78ce33389338d9977e1e5c0827e5fda4783",
    "Fahrzeug online abmelden (außer Betrieb setzen).txt": "fad1aee61550f440297ee4a368c3bb294aeae701189947289f625e7a155fe8ea",
    "Fahrzeug wieder anmelden.txt": "d8cbf155d8568fb6f243eb21f1e795e21320895c0c9bc1e8f282dfb95e4c8e55",
    "Wunschkennzeichen.txt": "fe57b04d300993db3e474a7b2ed9ceafe6e408bb401931820f650a258b4cce88",
    "Wechselkennzeichen.txt": "7716d7aa87153b6c4d7905b021775e7909cbdb17b305cdad1099a342e6e88398",
    "Saisonkennzeichen beantragen.txt": "4a26b3614154d7631cd93dd4a30a5d019e41d9c3667bb2abcfb95eda86d9dc08",
    "Kurzzeitkennzeichen beantragen.txt": "c0edba09dd4b16b24280819c5ee207f92d5a758ec454789e204db24fcde694ea",
    "Ausfuhrkennzeichen beantragen.txt": "33be6111514f262ef04747d82564ab200b0fe612c132a0adf1227fba9492be58",
    "Kennzeichen für Elektrofahrzeuge (E-Kennzeichen).txt": "3d1f77ea475c3b518fe325c8e0a006893b66a23e7b94b3c51e746c4621e205c4",
    "Historisches Kennzeichen für Oldtimer beantragen.txt": "53413666fee76a64a0c61a9df08a5da095e01a4d5ce2c57c8ab77050f3caf507",
    "Rotes Dauerkennzeichen für Oldtimer beantragen.txt": "35e676672203286d2ba39aa57a8ebad61091e66d27199ad41723e637ef640a77",
    "Rotes Dauerkennzeichen für Handel, Werkstätten und Hersteller beantragen.txt": "e61c7cf24455b98d675fa78b9f14830a3ea6f6eaadf6a79680afa938857f6ce5",
    "Verlust oder Diebstahl der Kennzeichenschilder.txt": "f4cf199bc411b6cf44bd55c4ae76a4e1050bcccf541743a82b434731cf19e724",
    "Verlust oder Diebstahl der Zulassungsbescheinigung Teil I.txt": "6f2b63664a5d16103af9a3cdd76e77e8c48e00eacd058164bd165b2c42699243",
    "Verlust oder Diebstahl der Zulassungsbescheinigung Teil II.txt": "a97c694048c2ab38515e6332c62d76df6f79266a3c59041e246a729af81cd0b5",
    "Namensänderung in den Fahrzeugpapieren.txt": "52a200371d51e178f4ab63b0726440550ea07b83c7541d26ccda2ed25b2c20d9",
    "Adressänderung in Fahrzeugpapiere eintragen lassen.txt": "6ba7598468b05c80548aa8609db8318c8515d014e303f752329dbf3aef6d3e7a",
    "Technische Änderungen in Fahrzeugpapiere eintragen lassen.txt": "58e56ad762abebad550c5549275ba3de44ad56ac16c3ba2ef308f8b537bb2d0d",
    "Halter- und Datenbestätigungen für ein Kraftfahrzeug beantragen.txt": "2ee8c7a07a5c27a18adb7806f19f3dea10ef07df2087709ab6ea0f0ac72080fb",
    "Geleastes oder finanziertes Fahrzeug anmelden- abmelden.txt": "72ad2cfce9d3f6413500b55de61cf85ba4843d2bc7d963f619a12287976fc664",
    "Führerschein mit 17.txt": "15222d9aa22832210e7907fb6e71fec9630ab85f0bd01c0170ede45dc3016275",
    "Fahrerlaubnis – Erstantrag.txt": "a1b53c63fe437372e80b9090fe6643ac8ab20bd79d4a754f177e90aa086538b0",
    "Fahrerlaubnis – Neuantrag nach Entzug oder Verzicht.txt": "14045f516a231e1e323287d6a3c2385ff07df4baa5b3e4740ec4291e6c1822d0",
    "Ersatzführerschein nach Verlust oder Diebstahl.txt": "b4eef68b24e74ac5ec50a2ad84d262b8328e3d94ae7c3ea863464f775a1186eb",
    "Ersatzführerschein – Änderung von Auflagen und Beschränkungen.txt": "429b71412079953c1993a6779d944d937cd73f75aee715b8cd6528265758ad58",
    "Umtausch in Kartenführerschein.txt": "3d50fc44f0d5bd329f3ab82a04e4b5266eba00f0193b7a02e6f294d6d1c1c2ec",
    "Internationaler Führerschein.txt": "144d07ae7e9c6a2d65aed7f92198b24d5cf00f9aaedc675afe5b71f8d4a37183",
    "Umschreibung eines ausländischen Führerscheins.txt": "534a3be7f5ef8faa475da37cb23650334b2e5201a103645898554ef039d4329d",
    "Umschreibung EU-EWR-Führerschein.txt": "e037a21105cc1f5358f9cbc62236496c6c36215fee2fffd088485af9ebc782f5",
    "Namensänderung im Führerschein.txt": "76ff56be1e5d2e6ead63600933e15d06e2d46d05106482218d76816f20540a1a",
    "Verlängerung befristeter Führerschein-Klassen.txt": "74f34ccac1d41b410c5eb92a728fc0dc39005ca139c8dbf1f07925340e94ba3f",
    "Auskunft aus dem Fahreignungsregister.txt": "34b876262b161108b2b30bbf3b67bdd32dcb988dc7855a11cc4b8e4df07ee097",
    "Karteikartenabschrift der Führerscheindaten.txt": "009eae986e113a67edc5e4dc04fac26f0a3b302867b500653c633f990bc6668f",
}

SELECTED_MELDEWESEN: list[str] = [
    "Personalausweis.txt",
    "Personalausweis oder Reisepass abholen.txt",
    "Reisepass.txt",
    "Vorläufiger Reisepass.txt",
    "Kinderreisepass (bis 12 Jahre) beantragen.txt",
    "Zweitpass, weitere Reisepässe.txt",
    "Verlust oder Diebstahl Personalausweis.txt",
    "Verlust oder Diebstahl Reisepass.txt",
    "Widerruf der Verlust- oder Diebstahlanzeige von Personalausweis oder Reisepass.txt",
    "Nachträgliche Anschriftenänderungen im Personalausweis, Reisepass, elektronischem Aufenthaltstitel.txt",
    "Nachträgliches Einschalten eID-Funktion oder Änderung der PIN.txt",
    "eID-Karte beantragen (EU-EWR).txt",
    "Verlust oder Diebstahl der eID-Karte.txt",
    "Widerruf Verlust oder Diebstahl der eID-Karte.txt",
    "Befreiung von der Ausweispflicht.txt",
    "Ausweisdokumente für die ganze Familie.txt",
    "Führungszeugnis.txt",
    "Führungszeugnis für Ehrenamtliche.txt",
    "Beglaubigung von Unterschriften.txt",
    "Beglaubigung von Dokumenten bis zu 5 Dokumenten.txt",
    "Beglaubigung von bis zu 20 Dokumenten.txt",
    "Beglaubigung von mehr als 20 Dokumenten.txt",
    "Beglaubigung für Rentenzwecke.txt",
    "Wohnsitz anmelden oder ummelden.txt",
    "Wohnsitz abmelden.txt",
    "Melderegisterauskunft.txt",
    "Melderechtliche Bescheinigung.txt",
    "Haushaltsbescheinigung beantragen.txt",
    "Lebensbescheinigung beantragen.txt",
    "Auskunftssperre einrichten.txt",
    "Übermittlungssperre von Daten.txt",
    "Geburtsurkunde – Erwachsene und Kinder ab 3 Monate.txt",
    "Geburtsurkunde – Neugeborene.txt",
    "Sterbeurkunde.txt",
    "Ehefähigkeitszeugnis.txt",
    "Anmeldung einer Eheschließung.txt",
    "Namensänderung.txt",
    "Namensänderung nach Heirat oder Scheidung.txt",
    "Namensänderungen für Kinder.txt",
    "Kirchenaustritt erklären.txt",
    "Kirchenaustrittsbescheinigung.txt",
    "Personendaten ändern.txt",
    "Wählbarkeitsbescheinigung.txt",
    "Aufnehmen in das Wählerverzeichnis.txt",
    "Briefwahl beantragen.txt",
    "Sorgeerklärung beurkunden.txt",
]

SELECTED_KFZ: list[str] = [
    "Fabrikneues Fahrzeug anmelden.txt",
    "Aus dem Ausland eingeführtes Fahrzeug anmelden.txt",
    "Fahrzeug umschreiben innerhalb Münchens.txt",
    "Fahrzeug umschreiben von außerhalb nach München.txt",
    "Fahrzeug abmelden.txt",
    "Fahrzeug online abmelden (außer Betrieb setzen).txt",
    "Fahrzeug wieder anmelden.txt",
    "Wunschkennzeichen.txt",
    "Wechselkennzeichen.txt",
    "Saisonkennzeichen beantragen.txt",
    "Kurzzeitkennzeichen beantragen.txt",
    "Ausfuhrkennzeichen beantragen.txt",
    "Kennzeichen für Elektrofahrzeuge (E-Kennzeichen).txt",
    "Historisches Kennzeichen für Oldtimer beantragen.txt",
    "Rotes Dauerkennzeichen für Oldtimer beantragen.txt",
    "Rotes Dauerkennzeichen für Handel, Werkstätten und Hersteller beantragen.txt",
    "Verlust oder Diebstahl der Kennzeichenschilder.txt",
    "Verlust oder Diebstahl der Zulassungsbescheinigung Teil I.txt",
    "Verlust oder Diebstahl der Zulassungsbescheinigung Teil II.txt",
    "Namensänderung in den Fahrzeugpapieren.txt",
    "Adressänderung in Fahrzeugpapiere eintragen lassen.txt",
    "Technische Änderungen in Fahrzeugpapiere eintragen lassen.txt",
    "Halter- und Datenbestätigungen für ein Kraftfahrzeug beantragen.txt",
    "Geleastes oder finanziertes Fahrzeug anmelden- abmelden.txt",
    "Führerschein mit 17.txt",
    "Fahrerlaubnis – Erstantrag.txt",
    "Fahrerlaubnis – Neuantrag nach Entzug oder Verzicht.txt",
    "Ersatzführerschein nach Verlust oder Diebstahl.txt",
    "Ersatzführerschein – Änderung von Auflagen und Beschränkungen.txt",
    "Umtausch in Kartenführerschein.txt",
    "Internationaler Führerschein.txt",
    "Umschreibung eines ausländischen Führerscheins.txt",
    "Umschreibung EU-EWR-Führerschein.txt",
    "Namensänderung im Führerschein.txt",
    "Verlängerung befristeter Führerschein-Klassen.txt",
    "Auskunft aus dem Fahreignungsregister.txt",
    "Karteikartenabschrift der Führerscheindaten.txt",
]


def sha256_of(data: bytes) -> str:
    digest = hashlib.sha256()
    digest.update(data)
    return digest.hexdigest()


def ensure_raw_files() -> None:
    """Download every selected raw file into raw-source/ if not cached, then
    verify all of them against the pinned SHA-256 values before returning."""
    RAW_SOURCE_DIR.mkdir(parents=True, exist_ok=True)
    for filename, expected_sha256 in RAW_FILE_SHA256.items():
        target = RAW_SOURCE_DIR / filename
        if target.exists() and sha256_of(target.read_bytes()) == expected_sha256:
            continue
        url = HF_BASE_URL + urllib.parse.quote(filename)
        print(f"Downloading {url} -> {target}", file=sys.stderr)
        req = urllib.request.Request(url, headers={"User-Agent": "opaa-demo-generator/1.0"})
        with urllib.request.urlopen(req) as response:  # noqa: S310 (pinned HTTPS URL)
            data = response.read()
        actual = sha256_of(data)
        if actual != expected_sha256:
            raise SystemExit(
                f"SHA-256 mismatch for {filename}: expected {expected_sha256}, got {actual}. "
                "The pinned commit's file content may have drifted; do not proceed silently."
            )
        target.write_bytes(data)
    verify_raw_files()


def verify_raw_files() -> None:
    for filename, expected_sha256 in RAW_FILE_SHA256.items():
        path = RAW_SOURCE_DIR / filename
        if not path.exists():
            raise SystemExit(
                f"Missing raw source file {path}. Run ensure_raw_files() or place the "
                "file manually (see demo/generator/README.md)."
            )
        actual = sha256_of(path.read_bytes())
        if actual != expected_sha256:
            raise SystemExit(f"SHA-256 mismatch for {filename}: expected {expected_sha256}, got {actual}.")


def read_raw(filename: str) -> str:
    return (RAW_SOURCE_DIR / filename).read_text(encoding="utf-8")
