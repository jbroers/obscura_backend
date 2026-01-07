from pathlib import Path
import re
import argparse
import json
import sys
import csv
import shutil
import subprocess
import tempfile
import uuid
from collections import OrderedDict

DEFAULT_PATH = Path('build/test-results/raw-format-probe-services.txt')
DEFAULT_SAMPLES = Path('uploads')
DEFAULT_TEST_SAMPLES = Path('src/test/resources/raw/raws')

EXT_RE = re.compile(r"^Extension summary for '\s*([^']*?)\s*':\s*$")
EXT_HEADER_RE = re.compile(r"^=== Extension: \s*(.*?)\s*===$")
FILE_RE = re.compile(r"^File:\s*(.+)$")
SECTION_RE = re.compile(r"^\s*(.*?)\s*$")
KEY_RE = re.compile(r"^\s*([^:]+?)\s*:\s*(FOUND|MISSING)\s*$", re.IGNORECASE)
KV_RE = re.compile(r"^\s*([^:]+?)\s*:\s*(.*)$")
EXAMPLE_KEY_RE = re.compile(r"^\s*([^:]+?)\s*:\s*(.*)$")

# Common synonyms for keys when looking at exiftool tags (lowercase)
KEY_SYNONYMS = {
    'lens': [
        'lens', 'lensmodel', 'lens model', 'lensid', 'lens id', 'lensspec', 'lens spec', 'lensinfo', 'lens info', 'lensmake', 'lenstype', 'lensname'
    ],
    'aperture': [
        'aperture', 'fnumber', 'f-number', 'aperturevalue', 'fstop', 'f-stop'
    ],
    'focallength': [
        'focallength', 'focal length', 'focal-length', 'focallength35efl', 'focallengthin35mmformat'
    ],
    'iso': [
        'iso', 'iso speed', 'isospeedratings', 'iso speed ratings', 'isosetting', 'isoexp'
    ],
    'make': [
        'make', 'manufacturer'
    ],
    'model': [
        'model', 'camera model name', 'unique camera model', 'cameramodelname', 'camera-model-name'
    ],
    'orientation': [
        'orientation', 'imageorientation', 'rotation'
    ],
    'resolution': [
        'xresolution', 'yresolution', 'resolution', 'imagesize', 'image size', 'imagewidth', 'image width', 'imageheight', 'image height'
    ],
    'shutterspeed': [
        'shutterspeed', 'shutter speed', 'exposuretime', 'exposure time', 'shutterspeedvalue', 'exposure'
    ],
    'datetaken': [
        'datetimeoriginal', 'date/time original', 'date time original', 'createdate', 'modifydate', 'date/time', 'date time'
    ]
}


def parse_report(path: Path):
    if not path.exists():
        return None, f"Report not found: {path}"
    text = path.read_text(encoding='utf-8')
    lines = text.splitlines()

    res = OrderedDict()
    i = 0
    current_ext = None
    while i < len(lines):
        line = lines[i]
        m_ext = EXT_HEADER_RE.match(line)
        if m_ext:
            current_ext = m_ext.group(1) or "(none)"
            res[current_ext] = {'files': OrderedDict(), 'extension_summary': OrderedDict(), 'examples': OrderedDict()}
            i += 1
            continue

        if current_ext is None:
            i += 1
            continue

        m_file = FILE_RE.match(line)
        if m_file:
            fname = m_file.group(1).strip()
            res[current_ext]['files'][fname] = {'sources': OrderedDict(), 'aggregate': OrderedDict()}
            i += 1
            current_source = None
            while i < len(lines):
                l = lines[i]
                if not l.strip():
                    i += 1
                    break
                if l.strip() == 'Findings per source:':
                    current_source = None
                    i += 1
                    continue
                if l.strip().endswith(':') and not l.strip().startswith('Example') and not l.strip().startswith('Extension summary'):
                    current_source = l.strip()[:-1]
                    res[current_ext]['files'][fname]['sources'][current_source] = OrderedDict()
                    i += 1
                    continue
                if l.strip() == 'Aggregate for file:':
                    current_source = None
                    i += 1
                    while i < len(lines) and lines[i].strip():
                        km = KEY_RE.match(lines[i])
                        if km:
                            k = km.group(1).strip()
                            v = km.group(2).upper()
                            res[current_ext]['files'][fname]['aggregate'][k] = v
                        i += 1
                    break
                if current_source is not None:
                    km = KV_RE.match(l)
                    if km:
                        k = km.group(1).strip()
                        v = km.group(2).strip()
                        res[current_ext]['files'][fname]['sources'][current_source][k] = v
                i += 1
            continue

        if line.startswith("Extension summary for '"):
            i += 1
            while i < len(lines) and lines[i].strip():
                km = KEY_RE.match(lines[i])
                if km:
                    k = km.group(1).strip()
                    v = km.group(2).upper()
                    res[current_ext]['extension_summary'][k] = v
                i += 1
            continue

        if line.strip().startswith('Example files missing keys'):
            i += 1
            while i < len(lines) and lines[i].strip():
                m = EXAMPLE_KEY_RE.match(lines[i])
                if m:
                    k = m.group(1).strip()
                    rest = m.group(2).strip()
                    parts = [p for p in rest.split() if p]
                    res[current_ext]['examples'][k] = parts
                i += 1
            continue

        i += 1

    return res, None


def compute_stats(res):
    """Return a dict mapping ext -> stats dict with found_count, missing_count, percent, found_keys, missing_keys"""
    stats = OrderedDict()
    all_keys = sorted({k for d in res.values() for k in d.get('extension_summary', {}).keys()})
    for ext, info in res.items():
        data = info.get('extension_summary', {})
        found = [k for k, v in data.items() if v == 'FOUND']
        missing = [k for k, v in data.items() if v != 'FOUND']
        total = len(data) if data else len(all_keys)
        if total == 0:
            total = len(all_keys)
            found_count = 0
            missing_count = len(all_keys)
        else:
            found_count = len(found)
            missing_count = len(missing)
        pct = (found_count / total * 100) if total > 0 else 0.0
        stats[ext] = {
            'found_count': found_count,
            'missing_count': missing_count,
            'percent_found': round(pct, 1),
            'found': found,
            'missing': missing,
            'raw': data
        }
    return stats


def human_summary(res, min_percent=None):
    if not res:
        print("No extension summaries found in report.")
        return
    stats = compute_stats(res)
    rows = [(ext, s['found_count'], s['missing_count'], s['percent_found'], s['found'], s['missing']) for ext, s in stats.items()]
    rows.sort(key=lambda r: (-r[3], r[0]))

    print("Per-extension summary:")
    print("{:<12} {:>6} {:>8} {:>8}".format("Extension", "FOUND", "MISSING", "%"))
    print("" + "-" * 40)
    for ext, found_count, missing_count, pct, found, missing in rows:
        if min_percent is not None and pct >= min_percent:
            continue
        print("{:<12} {:>6} {:>8} {:7.1f}%".format(ext, found_count, missing_count, pct))
    print()

    for ext, found_count, missing_count, pct, found, missing in rows:
        if min_percent is not None and pct >= min_percent:
            continue
        if missing:
            print(f"{ext}: missing ({missing_count}) -> {', '.join(missing)}")
        else:
            print(f"{ext}: all keys FOUND ({found_count})")
    print()

    all_keys = sorted({k for d in res.values() for k in d.get('extension_summary', {}).keys()})
    key_counts = OrderedDict()
    total_ext = len(res)
    for k in all_keys:
        cnt = sum(1 for d in res.values() if d.get('extension_summary', {}).get(k) == 'FOUND')
        key_counts[k] = cnt

    print("Per-key coverage across extensions:")
    print("{:<18} {:>8} {:>8}".format("Key", "EXT_FOUND", "%_of_ext"))
    print("" + "-" * 48)
    for k, cnt in key_counts.items():
        pct = (cnt / total_ext * 100) if total_ext > 0 else 0.0
        print("{:<18} {:>8} {:7.1f}%".format(k, cnt, pct))


def compact_summary(res):
    for ext in sorted(res.keys()):
        data = res[ext].get('extension_summary', {})
        kv = ";".join([f"{k}={v}" for k, v in data.items()])
        print(f"{ext}:{kv}")


def json_summary(res):
    out = compute_stats(res)
    print(json.dumps(out, indent=2, ensure_ascii=False))


def write_csv(res, outpath: Path, min_percent=None):
    stats = compute_stats(res)
    outpath.parent.mkdir(parents=True, exist_ok=True)
    with outpath.open('w', newline='', encoding='utf-8') as f:
        w = csv.writer(f)
        w.writerow(['extension', 'found_count', 'missing_count', 'percent_found', 'found_keys', 'missing_keys'])
        for ext, s in stats.items():
            if min_percent is not None and s['percent_found'] >= min_percent:
                continue
            w.writerow([ext, s['found_count'], s['missing_count'], s['percent_found'], '|'.join(s['found']), '|'.join(s['missing'])])
    print(f"Wrote CSV summary to: {outpath}")


def find_sample_for_ext(samples_dir: Path, ext: str):
    if not samples_dir.exists() or not samples_dir.is_dir():
        return None
    for p in samples_dir.rglob(f"*.{ext}"):
        if p.is_file():
            return p
    for p in samples_dir.rglob("*.*"):
        if p.is_file() and p.suffix.lower().lstrip('.') == ext.lower():
            return p
    return None


def _prepare_exiftool_cmd(exiftool_path: str):
    """Return a list command for exiftool. If exiftool_path contains '(-k).exe' (Windows distribution),
    copy it to a temp file named exiftool-<uuid>.exe (rename) so it won't pause, and return that path.
    Caller is responsible for cleanup of the temp file if needed (we won't delete automatically).
    """
    if not exiftool_path:
        return ['exiftool'], None
    p = Path(exiftool_path)
    if '(-k)' in p.name:
        # copy to temp file without '(-k)'
        tmpdir = Path(tempfile.gettempdir())
        tmpname = f"exiftool-{uuid.uuid4().hex}.exe"
        tmp_path = tmpdir / tmpname
        try:
            shutil.copy2(str(p), str(tmp_path))
            return [str(tmp_path)], str(tmp_path)
        except Exception:
            return [str(p)], None
    else:
        return [str(p)], None


def debug_samples(res, samples_dir: Path, debug_dir: Path, pick=6, min_percent=None, run_exiftool=False, exiftool_path: str = None):
    """Select worst-performing extensions and copy one sample per extension into debug_dir.
    If exiftool is available and run_exiftool=True, run it and save output next to each sample."""
    stats = compute_stats(res)
    items = sorted(stats.items(), key=lambda kv: (kv[1]['percent_found'], kv[0]))
    selected = []
    for ext, s in items:
        if min_percent is not None and s['percent_found'] >= min_percent:
            continue
        selected.append((ext, s))
        if len(selected) >= pick:
            break

    if not selected:
        print("No extensions selected for debugging (none below min_percent or nothing to pick).")
        return

    debug_dir.mkdir(parents=True, exist_ok=True)

    exiftool_available = False
    exiftool_cmd = None
    temp_exiftool = None
    if run_exiftool:
        if exiftool_path:
            exiftool_cmd, temp_exiftool = _prepare_exiftool_cmd(exiftool_path)
        else:
            exiftool_cmd = ['exiftool']
        try:
            subprocess.run(exiftool_cmd + ['-ver'], capture_output=True, check=True)
            exiftool_available = True
        except Exception:
            print(f"exiftool not available at {exiftool_cmd}; skipping exiftool output.")
            exiftool_available = False

    for ext, s in selected:
        sample = find_sample_for_ext(samples_dir, ext)
        ext_dir = debug_dir / ext
        # copy up to one representative sample by extension if available
        sample = find_sample_for_ext(samples_dir, ext)
            (ext_dir / 'README.txt').write_text(f"No sample found for extension '{ext}' in {samples_dir}\n")
            print(f"No sample found for {ext} (looked in {samples_dir})")
            continue
        else:
            dest = ext_dir / sample.name
            shutil.copy2(sample, dest)
            print(f"Copied sample for {ext}: {sample} -> {dest}")
            (ext_dir / 'stats.json').write_text(json.dumps(s, indent=2, ensure_ascii=False))
            if exiftool_available and exiftool_cmd:
                try:
                    p = subprocess.run(exiftool_cmd + ['-a', '-G1', '-s', str(dest)], capture_output=True, text=True, check=False)
                    (ext_dir / 'exiftool.txt').write_text(p.stdout or p.stderr)
                    print(f"Wrote exiftool output to {ext_dir / 'exiftool.txt'}")
                except Exception as e:
                    (ext_dir / 'exiftool-error.txt').write_text(str(e))
    print(f"Prepared debug samples in: {debug_dir}")
    # Note: if we created a temporary exiftool copy, we leave it in the temp dir; user can remove it later.


def exiftool_has_key(filepath: Path, key: str, exiftool_path: str = None):
def collect_missing_samples_from_report(res, samples_dirs, debug_dir: Path, collect_all: bool = False, run_exiftool: bool = False, exiftool_path: str = None):
    """Copy files referenced in the parsed report where per-file aggregate has missing keys (or all files if collect_all True).
    samples_dirs is a list of Path to search for the sample files (in order).
    For each copied file we also save exiftool output if requested.
    """
    debug_dir = Path(debug_dir)
    debug_dir.mkdir(parents=True, exist_ok=True)

    exiftool_available = False
    exiftool_cmd = None
    temp_exif = None
    if run_exiftool:
        exiftool_cmd, temp_exif = _prepare_exiftool_cmd(exiftool_path) if exiftool_path else (['exiftool'], None)
        try:
            subprocess.run(exiftool_cmd + ['-ver'], capture_output=True, check=True)
            exiftool_available = True
        except Exception:
            print(f"exiftool not available at {exiftool_cmd}; skipping exiftool output.")
            exiftool_available = False

    copied = 0
    for ext, info in res.items():
        ext_dir = debug_dir / ext
        ext_dir.mkdir(parents=True, exist_ok=True)
        # 1) collect explicit example filenames from report.examples
        examples = info.get('examples', {})
        for k, fnames in examples.items():
            for fname in fnames:
                # copy if present in any samples_dirs
                src = None
                for d in samples_dirs:
                    p = d / fname
                    if p.exists():
                        src = p
                        break
                    # fallback: case-insensitive search
                    if d.exists():
                        matches = list(d.rglob(fname))
                        if matches:
                            src = matches[0]
                            break
                if src:
                    dest = ext_dir / src.name
                    if not dest.exists():
                        shutil.copy2(src, dest)
                        copied += 1
                        print(f"Collected example file {src} -> {dest}")
                        if exiftool_available and exiftool_cmd:
                            try:
                                p = subprocess.run(exiftool_cmd + ['-a', '-G1', '-s', str(dest)], capture_output=True, text=True, check=False)
                                (ext_dir / (dest.name + '.exiftool.txt')).write_text(p.stdout or p.stderr)
                            except Exception as e:
                                (ext_dir / 'exiftool-error.txt').write_text(str(e))

        # 2) collect per-file missing entries from info['files'] where aggregate has MISSING
        files = info.get('files', {})
        for fname, finfo in files.items():
            agg = finfo.get('aggregate', {})
            has_missing = any(v != 'FOUND' for v in agg.values())
            if not collect_all and not has_missing:
                continue
            # locate the file in sample dirs
            src = None
            for d in samples_dirs:
                p = d / fname
                if p.exists():
                    src = p
                    break
                if d.exists():
                    matches = list(d.rglob(fname))
                    if matches:
                        src = matches[0]
                        break
            if src:
                dest = ext_dir / src.name
                if not dest.exists():
                    shutil.copy2(src, dest)
                    copied += 1
                    print(f"Collected missing file {src} -> {dest}")
                    if exiftool_available and exiftool_cmd:
                        try:
                            p = subprocess.run(exiftool_cmd + ['-a', '-G1', '-s', str(dest)], capture_output=True, text=True, check=False)
                            (ext_dir / (dest.name + '.exiftool.txt')).write_text(p.stdout or p.stderr)
                        except Exception as e:
                            (ext_dir / 'exiftool-error.txt').write_text(str(e))
            else:
                # write a note
                (ext_dir / 'README.txt').write_text((ext_dir / 'README.txt').read_text(encoding='utf-8') if (ext_dir / 'README.txt').exists() else '')
    print(f"Collected {copied} files into {debug_dir}")
    return copied


    print('\nComparison with exiftool (for example files):')
    print('{:<8} {:<12} {:<30} {:<8} {:s}'.format('EXT', 'KEY', 'FILENAME', 'EXIF', 'EXIF_VALUE_SNIPPET'))
    print('-' * 100)
    for e in summary:
        exif = 'N/A' if e['found_by_exiftool'] is None else ('YES' if e['found_by_exiftool'] else 'NO')
        snippet = (str(e['exif_value'])[:60].replace('\n', ' ')) if e['exif_value'] else ''
        print('{:<8} {:<12} {:<30} {:<8} {:s}'.format(e['extension'], e['key'], e['filename'], exif, snippet))


def main():
    parser = argparse.ArgumentParser(description='Parse raw-format probe report and present a summary')
    parser.add_argument('path', nargs='?', default=str(DEFAULT_PATH), help='Path to the probe report file')
    parser.add_argument('--compact', action='store_true', help='Emit compact machine-readable summary')
    parser.add_argument('--json', action='store_true', help='Emit JSON summary')
    parser.add_argument('--csv', type=str, help='Write CSV summary to this path')
    parser.add_argument('--min-percent', type=float, help='Hide extensions with percent >= N (useful to focus on bad ones)')
    parser.add_argument('--debug-dir', type=str, help='Directory to prepare debug samples')
    parser.add_argument('--samples-dir', type=str, default=str(DEFAULT_SAMPLES), help='Where to look for sample files (default: uploads/)')
    parser.add_argument('--test-samples-dir', type=str, default=str(DEFAULT_TEST_SAMPLES), help='Also search this test samples directory for files')
    parser.add_argument('--pick', type=int, default=6, help='How many worst extensions to prepare samples for')
    parser.add_argument('--run-exiftool', action='store_true', help='If exiftool is on PATH, run it and save output for each sample')
    parser.add_argument('--compare-exiftool', action='store_true', help='For example files in the report, run exiftool and report whether exiftool contains the missing keys')
    parser.add_argument('--exiftool-path', type=str, help='Path to the exiftool executable (if not on PATH)')
    args = parser.parse_args()

    path = Path(args.path)
    res, err = parse_report(path)
    if err:
        print(err)
        sys.exit(1)

    if args.csv:
        write_csv(res, Path(args.csv), min_percent=args.min_percent)

    if args.debug_dir:
        debug_samples(res, Path(args.samples_dir), Path(args.debug_dir), pick=args.pick, min_percent=args.min_percent, run_exiftool=args.run_exiftool, exiftool_path=args.exiftool_path)

    if args.compact:
        compact_summary(res)
    elif args.json:
        json_summary(res)
    else:
        human_summary(res, min_percent=args.min_percent)

    if args.compare_exiftool:
        samples_dirs = [Path(args.samples_dir), Path(args.test_samples_dir)]
        compare_with_exiftool(res, samples_dirs, min_percent=args.min_percent, exiftool_path=args.exiftool_path)