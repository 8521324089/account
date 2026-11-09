#!/usr/bin/env python3
"""
clean_dev_logs.py — Safe Seven-Day Operational Log Retention Utility

Rules:
1. Retains operational Markdown logs for the latest N calendar days (default: 7, including today).
2. Deletes ONLY eligible dated Markdown files matching strictly: YYYY-MM-DD.md.
3. Preserves all other files (e.g. README.md, .gitkeep, arbitrary notes, subdirectories).
4. Defaults to DRY-RUN mode. Requires --apply to perform actual deletion.
5. Safely handles missing directories and empty folders.
6. Does not modify or depend on Git history.
"""

import argparse
import os
import re
import sys
from datetime import datetime, timedelta, date
from pathlib import Path

DATE_PATTERN = re.compile(r"^(\d{4})-(\d{2})-(\d{2})\.md$")

def parse_args():
    parser = argparse.ArgumentParser(
        description="Safely prune operational development logs older than the retention window."
    )
    parser.add_argument(
        "--dir",
        type=str,
        default=None,
        help="Path to dev-logs directory (default: <repo_root>/dev-logs)"
    )
    parser.add_argument(
        "--retention-days",
        type=int,
        default=7,
        help="Number of calendar days to retain, including reference date (default: 7)"
    )
    parser.add_argument(
        "--reference-date",
        type=str,
        default=None,
        help="Reference date for calculating cutoff in YYYY-MM-DD format (default: today)"
    )
    parser.add_argument(
        "--apply",
        action="store_true",
        default=False,
        help="Perform actual deletion. If omitted, runs in safe DRY-RUN mode."
    )
    return parser.parse_args()

def determine_dev_logs_dir(custom_path):
    if custom_path:
        return Path(custom_path).resolve()
    # Default relative to this script's location (<repo>/scripts/clean_dev_logs.py -> <repo>/dev-logs)
    script_dir = Path(__file__).resolve().parent
    repo_root = script_dir.parent
    return repo_root / "dev-logs"

def get_reference_date(ref_str):
    if ref_str:
        try:
            return datetime.strptime(ref_str, "%Y-%m-%d").date()
        except ValueError:
            print(f"Error: Invalid reference date format '{ref_str}'. Use YYYY-MM-DD.", file=sys.stderr)
            sys.exit(2)
    return date.today()

def evaluate_logs(dev_logs_dir: Path, retention_days: int, ref_date: date):
    if not dev_logs_dir.exists():
        return {
            "status": "missing_directory",
            "retained": [],
            "expired": [],
            "ignored": []
        }

    cutoff_date = ref_date - timedelta(days=retention_days - 1)

    retained = []
    expired = []
    ignored = []

    for item in sorted(dev_logs_dir.iterdir()):
        if item.is_dir():
            ignored.append((item.name, "directory"))
            continue

        match = DATE_PATTERN.match(item.name)
        if not match:
            ignored.append((item.name, "non-date filename"))
            continue

        try:
            file_date = date(int(match.group(1)), int(match.group(2)), int(match.group(3)))
        except ValueError:
            ignored.append((item.name, "invalid calendar date"))
            continue

        if file_date >= cutoff_date:
            retained.append((item, file_date))
        else:
            expired.append((item, file_date))

    return {
        "status": "ok",
        "cutoff_date": cutoff_date,
        "retained": retained,
        "expired": expired,
        "ignored": ignored
    }

def main():
    args = parse_args()
    dev_logs_dir = determine_dev_logs_dir(args.dir)
    ref_date = get_reference_date(args.reference_date)
    is_dry_run = not args.apply

    mode_label = "DRY-RUN (No files will be modified)" if is_dry_run else "APPLY (DELETING EXPIRED FILES)"
    print(f"==================================================")
    print(f" Ledger Log Retention Manager")
    print(f" Directory:        {dev_logs_dir}")
    print(f" Reference Date:   {ref_date}")
    print(f" Retention Days:   {args.retention_days} (Window: {ref_date - timedelta(days=args.retention_days - 1)} to {ref_date})")
    print(f" Execution Mode:   {mode_label}")
    print(f"==================================================")

    res = evaluate_logs(dev_logs_dir, args.retention_days, ref_date)

    if res["status"] == "missing_directory":
        print(f"Notice: Directory '{dev_logs_dir}' does not exist. Nothing to prune.")
        return 0

    print(f"\n[RETAINED LOGS] ({len(res['retained'])} files inside retention window):")
    if res["retained"]:
        for file_path, fdate in res["retained"]:
            print(f"  ✓ {file_path.name} (Date: {fdate})")
    else:
        print("  (None)")

    print(f"\n[EXPIRED LOGS] ({len(res['expired'])} files older than cutoff {res['cutoff_date']}):")
    if res["expired"]:
        for file_path, fdate in res["expired"]:
            action_tag = "[PROPOSED FOR DELETION]" if is_dry_run else "[DELETED]"
            print(f"  ✗ {file_path.name} (Date: {fdate}) -> {action_tag}")
            if not is_dry_run:
                try:
                    file_path.unlink()
                except Exception as e:
                    print(f"    ERROR: Could not delete {file_path.name}: {e}", file=sys.stderr)
    else:
        print("  (None)")

    if res["ignored"]:
        print(f"\n[PRESERVED NON-LOG FILES] ({len(res['ignored'])} items excluded from pruning):")
        for name, reason in res["ignored"]:
            print(f"  • {name} ({reason})")

    print("\nSummary:")
    if is_dry_run:
        print(f"  Dry-run complete. {len(res['expired'])} file(s) eligible for pruning.")
        if len(res["expired"]) > 0:
            print("  To execute deletion, rerun with: python3 scripts/clean_dev_logs.py --apply")
    else:
        print(f"  Pruning complete. {len(res['expired'])} file(s) safely deleted.")

    return 0

if __name__ == "__main__":
    sys.exit(main())
