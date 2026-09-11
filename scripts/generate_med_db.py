#!/usr/bin/env python3
"""Offline generator for the health app's medical reference catalogues.

The Medication, Vaccinations and Conditions screens let the user pick from a
real terminology rather than typing free text, so that the FHIR resources
written to Health Connect carry proper codes. Everything is compiled into the
APK, so no picker makes a network request at runtime - the downloads below
happen here, at build time, not on the device.

Two outputs, deliberately handled differently:

  health/src/main/assets/cvx.json           committed
  health/src/main/assets/catalog.db.br      gitignored, plus a .meta.json

Vaccines - CVX
--------------
The CDC's CVX code set is the vocabulary FHIR's `Immunization.vaccineCode`
expects. Retired and non-US codes are kept alongside the active ones because a
vaccination log is retrospective: see `build_cvx`. That is about 250 codes and
roughly 40 KB as JSON, small enough that the app parses it straight into memory.
Because it is small it is committed, so the vaccination picker works in a clean
checkout with no build step. Public domain.

    https://www2a.cdc.gov/vaccines/iis/iisstandards/downloads/cvx.txt

Medications - RxNorm
--------------------
RxNorm Current Prescribable Content, the subset of RxNorm covering drugs
currently prescribable in the US. Unlike full RxNorm it carries no UMLS licence
requirement and may be redistributed freely.

    https://download.nlm.nih.gov/rxnorm/RxNorm_full_prescribe_current.zip

That is a ~75 MB zip of pipe-delimited RRF files. We keep the prescribable drug
products (TTY of SCD, SBD, GPCK or BPCK), each of which has an RXCUI for
`medicationCodeableConcept` and a name like "amoxicillin 500 MG Oral Capsule",
and resolve each one's ingredient and dose form through RXNREL so the app can
offer the two-step ingredient-then-strength picker. That is ~21,000 rows.

Conditions - ICD-10-CM
----------------------
The CDC's ICD-10-CM release, which is public domain. ~75,000 billable codes for
`Condition.code`.

    https://ftp.cdc.gov/pub/Health_Statistics/NCHS/Publications/ICD10CM/

US Core actually prefers SNOMED CT for Condition, but SNOMED needs a UMLS
licence and cannot be shipped in an APK, so ICD-10-CM it is. Codes are stored
dotted (A00.0), which is the form FHIR expects, though the source file is
dotless. Search ranks shorter descriptions first, which keeps the everyday
diagnoses above the long tail of "struck by turtle, subsequent encounter".

Lab tests - LOINC
-----------------
LOINC is free to use and redistribute with attribution, but unlike every other
source here the download sits behind a login, so this script cannot fetch it.
Take "LOINC Table File (CSV)" from

    https://loinc.org/downloads/

and pass the zip, or the extracted Loinc.csv, with --loinc. Without it the lab
table is built empty and the app falls back to free-text entry.

This content LOINC(R) is copyright (c) 1995-2024 Regenstrief Institute, Inc.
and the LOINC Committee, and is available at no cost under the licence at
http://loinc.org/license. That notice ships with the app.

Of the ~112,000 LOINC codes, this keeps the ~62,000 that are active laboratory
codes (CLASSTYPE=1). Excluded are:

  * everything but CLASSTYPE 1 - clinical, claims and survey codes are real
    LOINC but none of them is a lab result
  * the handful of rows carrying EXTERNAL_COPYRIGHT_NOTICE, which are
    third-party instruments inside LOINC under their own terms

Orderable panels are kept rather than filtered to reportable results only. That
looks like the tidier rule but throws away "CBC panel", "lipid panel" and
"comprehensive metabolic panel", which rank 162, 208 and 87 among common tests
and are how people describe their own blood work.

Ranking is LOINC's own COMMON_TEST_RANK, which covers ~18,000 codes and puts
the tests making up nearly all real lab volume first.

Three name fields go into the index, not one. LONG_COMMON_NAME is what gets
displayed, but it spells everything out - 1742-6 is "Alanine aminotransferase
[Enzymatic activity/volume] in Serum or Plasma" - so searching it alone for
"ALT" finds nothing. The abbreviation lives in SHORTNAME and DisplayName, so
both are indexed as hidden aliases. CONSUMER_NAME would be the ideal display
name but is empty for every laboratory row in 2.83.

Columns are looked up by name from the CSV header rather than by position,
because LOINC adds and reorders them between releases.

Both database-backed catalogues share one SQLite file so the app has a single
asset to unpack and a single schema version to check.

Unlike `generate_food_db.py`, this ships a finished SQLite database rather than
a columnar file the app rebuilds. That script's format exists because at Open
Food Facts' 465,000-row scale the SQLite container and FTS index cost more than
the data; at these sizes they do not, and shipping the database directly removes
the whole on-device build step and the format-version coupling with it. Brotli,
because the app already links a decoder for the food database.

Usage
-----
    python3 scripts/generate_med_db.py              # everything
    python3 scripts/generate_med_db.py --cvx-only   # just the committed JSON
"""

from __future__ import annotations

import argparse
import csv
import io
import json
import re
import shutil
import sqlite3
import subprocess
import sys
import zipfile
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
ASSETS = REPO_ROOT / "health" / "src" / "main" / "assets"

CVX_URL = "https://www2a.cdc.gov/vaccines/iis/iisstandards/downloads/cvx.txt"
RXNORM_URL = "https://download.nlm.nih.gov/rxnorm/RxNorm_full_prescribe_current.zip"
ICD10_YEAR = 2027
ICD10_URL = (
    "https://ftp.cdc.gov/pub/Health_Statistics/NCHS/Publications/ICD10CM/"
    f"{ICD10_YEAR}/icd10cm-code-descriptions-{ICD10_YEAR}.zip"
)

CACHE_DIR = Path.home() / ".cache" / "rxnorm"
CACHED_ZIP = CACHE_DIR / "RxNorm_full_prescribe_current.zip"
CACHED_ICD10 = CACHE_DIR / f"icd10cm-code-descriptions-{ICD10_YEAR}.zip"

# Bumped in lockstep with SUPPORTED_SCHEMA_VERSION in ReferenceCatalog.kt.
SCHEMA_VERSION = 4

# Prescribable drug products. SCD is generic, SBD branded, GPCK/BPCK are packs
# such as a course of tablets sold as one unit.
PRODUCT_TTYS = {"SCD", "SBD", "GPCK", "BPCK"}
PACK_TTYS = {"GPCK", "BPCK"}
# Semantic clinical drug components - "amoxicillin 500 MG". A drug product points
# at one of these per ingredient, and only the component points at the
# ingredient itself, so resolving an ingredient is always a two-hop walk.
COMPONENT_TTYS = {"SCDC"}
INGREDIENT_TTYS = {"IN"}
DOSE_FORM_TTYS = {"DF"}


def mb(n: int) -> str:
    return f"{n / 1_000_000:.1f} MB"


def download(url: str, dest: Path) -> Path:
    """Fetch `url` to `dest`, skipping the download if it is already cached."""
    if dest.exists() and dest.stat().st_size > 0:
        print(f"  cached  {dest} ({mb(dest.stat().st_size)})")
        return dest
    dest.parent.mkdir(parents=True, exist_ok=True)
    print(f"  fetching {url}")
    # curl rather than urllib: these redirect, and curl's resume and progress
    # reporting matter on the 75 MB one. The CDC's FTP host refuses a default
    # Python user agent, hence -A.
    subprocess.run(
        ["curl", "-sSL", "--fail", "-A", "Mozilla/5.0", "-o", str(dest), url], check=True
    )
    print(f"  saved   {dest} ({mb(dest.stat().st_size)})")
    return dest


# --- Vaccines --------------------------------------------------------------


def build_cvx(out_path: Path) -> int:
    """Write the CVX codes to `out_path` as JSON. Returns the row count.

    Retired and non-US codes are kept, not just the 115 currently active ones. A
    vaccination log is retrospective by nature, so the codes an older record uses
    are precisely the ones that have since been retired - filtering to Active
    would leave a user unable to enter the shot they had in 2004. "Never Active"
    codes never described a real product and are dropped, as are the non-vaccine
    placeholder rows such as "no vaccine administered".
    """
    raw = download(CVX_URL, CACHE_DIR / "cvx.txt")
    text = raw.read_text(encoding="utf-8-sig", errors="replace")

    vaccines = []
    for line in text.splitlines():
        if not line.strip():
            continue
        # code|shortDescription|fullName|note|status|nonVaccine|lastUpdated
        parts = [p.strip() for p in line.split("|")]
        if len(parts) < 6:
            continue
        code, short, full, _note, status, non_vaccine = parts[:6]
        if not code or status not in ("Active", "Inactive", "Non-US"):
            continue
        if non_vaccine.strip().lower() == "true":
            continue
        vaccines.append(
            {
                "code": code,
                "name": short,
                "fullName": full or short,
                "active": status == "Active",
            }
        )

    vaccines.sort(key=lambda v: (not v["active"], v["name"].lower()))
    out_path.parent.mkdir(parents=True, exist_ok=True)
    # newline="\n" because this file is committed and .gitattributes normalises the
    # tree to LF; Python's text mode would otherwise write CRLF on Windows and
    # every line would show as changed on the next regeneration from a Mac.
    out_path.write_text(
        json.dumps(vaccines, ensure_ascii=False, indent=1) + "\n",
        encoding="utf-8",
        newline="\n",
    )
    print(f"  wrote   {out_path} ({len(vaccines)} vaccines, {mb(out_path.stat().st_size)})")
    return len(vaccines)


# --- Medications -----------------------------------------------------------


def read_rrf(zf: zipfile.ZipFile, name: str):
    """Yield each RRF row as a list of fields."""
    with zf.open(name) as handle:
        for raw in handle:
            yield raw.decode("utf-8", errors="replace").rstrip("\n").split("|")


def load_concepts(zf: zipfile.ZipFile):
    """RXCUI -> name, split by the role the concept plays."""
    products: dict[str, str] = {}
    components: dict[str, str] = {}
    ingredients: dict[str, str] = {}
    dose_forms: dict[str, str] = {}

    for row in read_rrf(zf, "rrf/RXNCONSO.RRF"):
        if len(row) < 17:
            continue
        rxcui, sab, tty, name, suppress = row[0], row[11], row[12], row[14], row[16]
        if sab != "RXNORM" or suppress != "N" or not name:
            continue
        if tty in PRODUCT_TTYS:
            products.setdefault(rxcui, name)
        elif tty in COMPONENT_TTYS:
            components.setdefault(rxcui, name)
        elif tty in INGREDIENT_TTYS:
            ingredients.setdefault(rxcui, name)
        elif tty in DOSE_FORM_TTYS:
            dose_forms.setdefault(rxcui, name)

    print(
        f"  concepts {len(products)} products, {len(components)} components, "
        f"{len(ingredients)} ingredients, {len(dose_forms)} dose forms"
    )
    return products, ingredients, components, dose_forms


def load_relations(zf: zipfile.ZipFile, products, ingredients, components, dose_forms):
    """product RXCUI -> (ingredient names, dose form name).

    RXNREL rows read from RXCUI2 to RXCUI1, and RxNorm ships both directions of
    every relationship, so only the forward names are consumed here.

    A drug product never points at an ingredient directly. The path is

        SCD  --consists_of-->  SCDC  --has_ingredient-->  IN

    and a branded drug goes through the same generic SCDC. Packs point at the
    products they contain with `contains`, so they resolve in a third hop once
    everything else is known. `has_ingredient` on an SBD or SBDC leads to a brand
    name rather than an ingredient and is deliberately not followed.
    """
    component_ingredients: dict[str, set[str]] = {}
    product_components: dict[str, set[str]] = {}
    pack_contents: dict[str, set[str]] = {}
    product_dose_form: dict[str, str] = {}

    for row in read_rrf(zf, "rrf/RXNREL.RRF"):
        if len(row) < 8:
            continue
        target, source, rela = row[0], row[4], row[7]
        if rela == "has_ingredient":
            if source in components and target in ingredients:
                component_ingredients.setdefault(source, set()).add(ingredients[target])
        elif rela == "consists_of":
            if source in products and target in components:
                product_components.setdefault(source, set()).add(target)
        elif rela == "contains":
            if source in products and target in products:
                pack_contents.setdefault(source, set()).add(target)
        elif rela == "has_dose_form":
            if source in products and target in dose_forms:
                product_dose_form.setdefault(source, dose_forms[target])

    product_ingredients: dict[str, set[str]] = {}
    for product, component_ids in product_components.items():
        names = set()
        for component in component_ids:
            names |= component_ingredients.get(component, set())
        if names:
            product_ingredients[product] = names
    for pack, contained in pack_contents.items():
        names = set()
        for product in contained:
            names |= product_ingredients.get(product, set())
        if names:
            product_ingredients[pack] = names

    print(
        f"  relations {len(product_ingredients)} with an ingredient, "
        f"{len(product_dose_form)} with a dose form"
    )
    return product_ingredients, product_dose_form


BRAND_SUFFIX = re.compile(r"\s*\[[^\]]*\]\s*$")
STRENGTH = re.compile(r"\d")


def derive_strength(name: str, ingredient: str, dose_form: str | None) -> str | None:
    """What is left of the RxNorm name once the ingredient and dose form are gone.

    RxNorm product names are strictly "{ingredients and strengths} {dose form}"
    with an optional "[Brand]" suffix, so stripping the two known ends leaves the
    strength. Returns None when nothing with a digit in it survives, which is the
    case for packs and for a handful of products with no stated strength.
    """
    remainder = BRAND_SUFFIX.sub("", name).strip()
    if dose_form and remainder.lower().endswith(dose_form.lower()):
        remainder = remainder[: -len(dose_form)].strip()
    if remainder.lower().startswith(ingredient.lower()):
        remainder = remainder[len(ingredient):].strip()
    return remainder if remainder and STRENGTH.search(remainder) else None


def build_medications(zip_path: Path, conn: sqlite3.Connection) -> tuple[int, int]:
    with zipfile.ZipFile(zip_path) as zf:
        products, ingredients, components, dose_forms = load_concepts(zf)
        product_ingredients, product_dose_form = load_relations(
            zf, products, ingredients, components, dose_forms
        )

    conn.execute(
        """
        CREATE TABLE medications (
            rxcui TEXT PRIMARY KEY,
            name TEXT NOT NULL,
            ingredient TEXT NOT NULL,
            strength TEXT,
            dose_form TEXT
        )
        """
    )

    rows = []
    for rxcui, name in products.items():
        names = product_ingredients.get(rxcui)
        if not names:
            # No resolvable ingredient means the two-step picker cannot place it,
            # and its name alone is not enough to file it under anything.
            continue
        ingredient = " / ".join(sorted(names))
        dose_form = product_dose_form.get(rxcui)
        rows.append(
            (rxcui, name, ingredient, derive_strength(name, ingredient, dose_form), dose_form)
        )

    rows.sort(key=lambda r: (r[2].lower(), r[1].lower()))
    conn.executemany("INSERT INTO medications VALUES (?, ?, ?, ?, ?)", rows)

    # detail=none: no token positions. The app ranks by name length rather
    # than bm25 and never issues a phrase query, so positions buy nothing.
    #
    # Deliberately no prefix= index, even though the app prefix-matches every
    # token. Measured on this data it saved ~0.4 ms on a one-letter query and
    # nothing at all past three letters, for 1.7 MB of database: the cost of a
    # broad prefix is sorting the candidates, not finding them. The app declines
    # to search on a single character instead, which removes the case entirely.
    conn.execute(
        """
        CREATE VIRTUAL TABLE medications_fts USING fts5(
            name, ingredient,
            content='medications', content_rowid='rowid', detail='none'
        )
        """
    )
    conn.execute(
        "INSERT INTO medications_fts(rowid, name, ingredient) "
        "SELECT rowid, name, ingredient FROM medications"
    )
    print(f"  medications {len(rows)} products")
    return len(rows), build_ingredients(conn, ingredients)


def build_ingredients(conn: sqlite3.Connection, ingredients: dict[str, str]) -> int:
    """RxNorm ingredients, for coding drug allergies.

    An allergy is to the ingredient rather than to a particular strength and pack, so the allergy
    picker needs the ingredient's own RXCUI — which the product table does not carry, it only has
    the ingredient's name.
    """
    conn.execute(
        "CREATE TABLE ingredients (rxcui TEXT PRIMARY KEY, name TEXT NOT NULL)"
    )
    rows = sorted(ingredients.items(), key=lambda kv: kv[1].lower())
    conn.executemany("INSERT OR IGNORE INTO ingredients VALUES (?, ?)", rows)
    conn.execute(
        """
        CREATE VIRTUAL TABLE ingredients_fts USING fts5(
            name, content='ingredients', content_rowid='rowid', detail='none'
        )
        """
    )
    conn.execute(
        "INSERT INTO ingredients_fts(rowid, name) SELECT rowid, name FROM ingredients"
    )
    print(f"  ingredients {len(rows)} for allergy coding")
    return len(rows)


def build_conditions(zip_path: Path, conn: sqlite3.Connection) -> int:
    """ICD-10-CM billable codes, stored dotted because that is what FHIR expects."""
    rows = []
    with zipfile.ZipFile(zip_path) as zf:
        name = next(
            n for n in zf.namelist() if n.endswith(f"icd10cm-codes-{ICD10_YEAR}.txt")
        )
        with zf.open(name) as handle:
            for raw in handle:
                line = raw.decode("utf-8", errors="replace").rstrip("\n")
                if not line.strip():
                    continue
                # "A000    Cholera due to …" - code, run of spaces, description.
                parts = line.split(None, 1)
                if len(parts) != 2:
                    continue
                code, description = parts[0], parts[1].strip()
                rows.append((dotted(code), description))

    conn.execute(
        "CREATE TABLE conditions (code TEXT PRIMARY KEY, description TEXT NOT NULL)"
    )
    conn.executemany("INSERT OR IGNORE INTO conditions VALUES (?, ?)", rows)
    conn.execute(
        """
        CREATE VIRTUAL TABLE conditions_fts USING fts5(
            description,
            content='conditions', content_rowid='rowid', detail='none'
        )
        """
    )
    conn.execute(
        "INSERT INTO conditions_fts(rowid, description) "
        "SELECT rowid, description FROM conditions"
    )
    print(f"  conditions {len(rows)} codes")
    return len(rows)


def dotted(code: str) -> str:
    """A00.0 from A000. ICD-10-CM ships dotless; FHIR and humans both want the dot."""
    return code if len(code) <= 3 else f"{code[:3]}.{code[3:]}"


def read_loinc(path: Path):
    """Yield (code, name, ucum_unit, rank) for active laboratory LOINC codes.

    Accepts either the release zip or an extracted Loinc.csv. Columns are found
    by header name because LOINC adds and reorders them between releases, and a
    positional reader would silently start importing the wrong field.
    """
    if path.suffix.lower() == ".zip":
        with zipfile.ZipFile(path) as zf:
            name = next(
                (n for n in zf.namelist() if n.lower().endswith("loinc.csv")), None
            )
            if name is None:
                raise SystemExit(f"{path} has no Loinc.csv in it")
            with zf.open(name) as handle:
                yield from parse_loinc_csv(io.TextIOWrapper(handle, encoding="utf-8-sig"))
    else:
        with path.open(encoding="utf-8-sig", newline="") as handle:
            yield from parse_loinc_csv(handle)


def parse_loinc_csv(handle):
    reader = csv.DictReader(handle)
    required = {"LOINC_NUM", "LONG_COMMON_NAME", "CLASSTYPE", "STATUS"}
    missing = required - set(reader.fieldnames or [])
    if missing:
        raise SystemExit(f"LOINC table is missing columns: {sorted(missing)}")

    for row in reader:
        # CLASSTYPE 1 is Laboratory. 2 is Clinical, 3 Claims, 4 Surveys - all of
        # which are real LOINC but none of which is a lab result.
        if row.get("CLASSTYPE") != "1" or row.get("STATUS") != "ACTIVE":
            continue
        # Third-party instruments inside LOINC, under their own terms.
        if (row.get("EXTERNAL_COPYRIGHT_NOTICE") or "").strip():
            continue

        # Panels are deliberately kept even though ORDER_OBS marks them as things
        # you order rather than results you get back. "CBC", "lipid panel" and
        # "comprehensive metabolic panel" are how people refer to their own blood
        # work, and they rank 162, 208 and 87 among common tests - filtering them
        # out removed the five most recognisable names in the table.

        code = (row.get("LOINC_NUM") or "").strip()
        name = (row.get("LONG_COMMON_NAME") or "").strip()
        if not code or not name:
            continue

        # Hidden search text. "ALT" and "HbA1c" appear only here, never in the
        # long name, so without these the picker cannot find a test by the
        # abbreviation anybody would actually type.
        aliases = {
            (row.get("SHORTNAME") or "").strip(),
            (row.get("DisplayName") or "").strip(),
        }
        alias = " ".join(sorted(a for a in aliases if a and a != name))

        unit = (row.get("EXAMPLE_UCUM_UNITS") or "").strip() or None
        # Unranked codes sort last rather than being dropped: the rank covers only
        # the common few thousand, and the rest are still valid answers.
        raw_rank = (row.get("COMMON_TEST_RANK") or "").strip()
        rank = int(raw_rank) if raw_rank.isdigit() and raw_rank != "0" else 0
        yield code, name, alias, unit, rank


def build_labs(loinc_path: Path | None, conn: sqlite3.Connection) -> int:
    conn.execute(
        """
        CREATE TABLE labs (
            code TEXT PRIMARY KEY,
            name TEXT NOT NULL,
            alias TEXT NOT NULL,
            unit TEXT,
            rank INTEGER NOT NULL
        )
        """
    )
    conn.execute(
        """
        CREATE VIRTUAL TABLE labs_fts USING fts5(
            name, alias, content='labs', content_rowid='rowid', detail='none'
        )
        """
    )

    rows = sorted(read_loinc(loinc_path), key=lambda r: r[1].lower()) if loinc_path else []
    if not rows:
        print("  labs      none - pass --loinc to include them (see the module docstring)")
        return 0

    conn.executemany("INSERT OR IGNORE INTO labs VALUES (?, ?, ?, ?, ?)", rows)
    conn.execute(
        "INSERT INTO labs_fts(rowid, name, alias) SELECT rowid, name, alias FROM labs"
    )
    ranked = sum(1 for r in rows if r[4])
    print(f"  labs      {len(rows)} laboratory codes, {ranked} of them commonly ordered")
    return len(rows)


def build_catalog(
    rxnorm_zip: Path,
    icd10_zip: Path,
    loinc_path: Path | None,
    db_path: Path,
) -> tuple[int, int, int, int]:
    db_path.unlink(missing_ok=True)
    conn = sqlite3.connect(db_path)
    try:
        conn.execute("PRAGMA journal_mode = OFF")
        conn.execute("PRAGMA synchronous = OFF")
        medications, ingredients = build_medications(rxnorm_zip, conn)
        conditions = build_conditions(icd10_zip, conn)
        labs = build_labs(loinc_path, conn)
        # DELETE, not OFF: the app reopens the file read-only, which a journal
        # mode of OFF does not survive cleanly.
        conn.execute("PRAGMA journal_mode = DELETE")
        conn.commit()
        conn.execute("VACUUM")
        conn.commit()
    finally:
        conn.close()

    print(f"  built   {db_path} ({mb(db_path.stat().st_size)})")
    return medications, ingredients, conditions, labs


def compress(src: Path, dest: Path) -> None:
    try:
        import brotli
    except ImportError:
        sys.exit("brotli is required to compress the asset: pip install brotli")
    dest.parent.mkdir(parents=True, exist_ok=True)
    dest.write_bytes(brotli.compress(src.read_bytes(), quality=11))
    print(f"  wrote   {dest} ({mb(dest.stat().st_size)})")


def release_label(zip_path: Path) -> str:
    """The release date the zip's readme is named for, e.g. "2026-09-08"."""
    with zipfile.ZipFile(zip_path) as zf:
        for name in zf.namelist():
            match = re.search(r"Readme_Full_Prescribe_(\d{2})(\d{2})(\d{4})\.txt", name)
            if match:
                month, day, year = match.groups()
                return f"{year}-{month}-{day}"
    return ""


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--cvx-only", action="store_true",
                    help="build only the committed vaccine catalogue")
    ap.add_argument("--zip", type=Path, default=CACHED_ZIP,
                    help="a previously downloaded RxNorm prescribable release")
    ap.add_argument("--icd10", type=Path, default=CACHED_ICD10,
                    help="a previously downloaded ICD-10-CM code description release")
    ap.add_argument("--loinc", type=Path, default=None,
                    help="LOINC Table File (CSV) zip or Loinc.csv; see the module docstring, "
                         "since LOINC cannot be downloaded without a login")
    ap.add_argument("--keep-db", action="store_true",
                    help="keep the uncompressed SQLite file next to the asset")
    args = ap.parse_args()

    print("Vaccines (CVX)")
    build_cvx(ASSETS / "cvx.json")

    if args.cvx_only:
        return

    print("Medications (RxNorm), conditions (ICD-10-CM) and labs (LOINC)")
    rxnorm_zip = download(RXNORM_URL, args.zip)
    icd10_zip = download(ICD10_URL, args.icd10)
    if args.loinc and not args.loinc.exists():
        raise SystemExit(f"--loinc {args.loinc} does not exist")
    staging = ASSETS / "catalog.db"
    medications, ingredients, conditions, labs = build_catalog(
        rxnorm_zip, icd10_zip, args.loinc, staging
    )
    compress(staging, ASSETS / "catalog.db.br")

    meta = {
        "schemaVersion": SCHEMA_VERSION,
        "medications": medications,
        "ingredients": ingredients,
        "conditions": conditions,
        "labs": labs,
        "bytes": staging.stat().st_size,
        "compressedBytes": (ASSETS / "catalog.db.br").stat().st_size,
        "release": release_label(rxnorm_zip),
        "icd10Year": ICD10_YEAR,
    }
    (ASSETS / "catalog.db.meta.json").write_text(
        json.dumps(meta, indent=1) + "\n", newline="\n"
    )
    print(f"  wrote   {ASSETS / 'catalog.db.meta.json'}")

    if not args.keep_db:
        staging.unlink(missing_ok=True)


if __name__ == "__main__":
    main()
