#!/usr/bin/env python3
"""
Read build/reports/exif_compare.json and produce:
- build/reports/exif_problems.csv (rows with EXIF_ONLY keys where exiftool has data but our extractor missed it)
- build/reports/exif_problems.json (structured summary)
- human-readable console summary

Usage:
  python tools\finalize_exif_compare.py [--input path] [--out-csv path] [--out-json path]

If the input file is missing, the script exits with an error.
"""
from pathlib import Path
import json
import csv
import argparse
import sys

DEFAULT_IN = Path('build/reports/exif_compare.json')
DEFAULT_CSV = Path('build/reports/exif_problems.csv')
DEFAULT_JSON = Path('build/reports/exif_problems.json')


def load_input(path: Path):
    if not path.exists():
        print(f"Input file not found: {path}")
        return None
    with path.open('r', encoding='utf-8') as f:
        return json.load(f)


def summarize(data):
    problems = []
    per_file = {}
    for entry in data:
        ext = entry.get('extension')
        fname = entry.get('filename')
        keys = entry.get('keys', {})
        for k, kv in keys.items():
            status = kv.get('status')
            exif_val = kv.get('exif')
            our_val = kv.get('our')
            # treat placeholder empty strings as missing
            exif_has = bool(exif_val and str(exif_val).strip())
            our_has = bool(our_val and str(our_val).strip() and str(our_val).strip().lower() != 'none')

            if status == 'EXIF_ONLY' and exif_has:
                problems.append({'extension': ext, 'filename': fname, 'key': k, 'exif_value': exif_val})
                per_file.setdefault((ext, fname), []).append({'key': k, 'exif_value': exif_val})
    return problems, per_file


def write_csv(path: Path, problems):
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open('w', newline='', encoding='utf-8') as f:
        w = csv.writer(f)
        w.writerow(['extension','filename','key','exif_value'])
        for p in problems:
            w.writerow([p['extension'], p['filename'], p['key'], p['exif_value']])
    print(f"Wrote CSV: {path}")


def write_json(path: Path, per_file):
    path.parent.mkdir(parents=True, exist_ok=True)
    out = []
    for (ext,fname), items in per_file.items():
        out.append({'extension': ext, 'filename': fname, 'missing_keys': items})
    with path.open('w', encoding='utf-8') as f:
        json.dump(out, f, indent=2, ensure_ascii=False)
    print(f"Wrote JSON summary: {path}")


def print_summary(problems, per_file):
    if not problems:
        print("No EXIF_ONLY problems found — either our extractor matches exiftool or exiftool had no values.")
        return
    print("Files where exiftool has values but our extractor missed them (EXIF_ONLY):")
    for (ext,fname), items in per_file.items():
        print(f"- {ext} / {fname} : {len(items)} missing keys -> {', '.join([it['key'] for it in items])}")
    print()
    print(f"Total problematic files: {len(per_file)} (total missing key occurrences: {len(problems)})")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--input', type=str, default=str(DEFAULT_IN))
    parser.add_argument('--out-csv', type=str, default=str(DEFAULT_CSV))
    parser.add_argument('--out-json', type=str, default=str(DEFAULT_JSON))
    args = parser.parse_args()

    data = load_input(Path(args.input))
    if data is None:
        sys.exit(1)

    problems, per_file = summarize(data)
    write_csv(Path(args.out_csv), problems)
    write_json(Path(args.out_json), per_file)
    print_summary(problems, per_file)


if __name__ == '__main__':
    main()

