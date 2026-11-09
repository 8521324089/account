#!/usr/bin/env python3
"""
test_clean_dev_logs.py — Comprehensive Unit Tests for Log Retention Script

Verifies:
1. Exactly seven calendar days of eligible files are retained.
2. Older eligible files are listed for deletion (and deleted when --apply is set).
3. Non-date files (e.g. README.md, notes.txt) are strictly preserved.
4. Permanent documentation files are untouched.
5. Dry-run mode makes NO filesystem changes.
6. Missing directory is handled safely without crashing.
"""

import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from datetime import date, timedelta
from pathlib import Path

# Add scripts directory to path to import evaluate_logs directly
SCRIPT_DIR = Path(__file__).resolve().parent
sys.path.insert(0, str(SCRIPT_DIR))

from clean_dev_logs import evaluate_logs

class TestCleanDevLogs(unittest.TestCase):

    def setUp(self):
        self.temp_dir = Path(tempfile.mkdtemp(prefix="test_dev_logs_"))
        self.ref_date = date(2026, 10, 25)

    def tearDown(self):
        if self.temp_dir.exists():
            shutil.rmtree(self.temp_dir)

    def create_file(self, filename: str, content: str = "test log content"):
        file_path = self.temp_dir / filename
        file_path.write_text(content, encoding="utf-8")
        return file_path

    # ── Test 1: Exactly seven days of eligible files retained ──────────────────

    def test_retains_exactly_seven_days(self):
        # Create 10 consecutive daily logs: 2026-10-16 to 2026-10-25
        created_files = []
        for i in range(10):
            d = self.ref_date - timedelta(days=i)
            created_files.append(self.create_file(f"{d.isoformat()}.md"))

        res = evaluate_logs(self.temp_dir, retention_days=7, ref_date=self.ref_date)

        retained_names = [f[0].name for f in res["retained"]]
        expired_names = [f[0].name for f in res["expired"]]

        # Exactly 7 files retained (2026-10-19 to 2026-10-25)
        self.assertEqual(len(retained_names), 7)
        for i in range(7):
            expected = (self.ref_date - timedelta(days=i)).isoformat() + ".md"
            self.assertIn(expected, retained_names)

        # 3 files expired (2026-10-18, 2026-10-17, 2026-10-16)
        self.assertEqual(len(expired_names), 3)
        for i in range(7, 10):
            expected = (self.ref_date - timedelta(days=i)).isoformat() + ".md"
            self.assertIn(expected, expired_names)

    # ── Test 2: Non-date files are preserved ───────────────────────────────────

    def test_preserves_non_date_files(self):
        self.create_file("README.md")
        self.create_file(".gitkeep")
        self.create_file("release-notes.txt")
        self.create_file("invalid-date-2026-99-99.md")
        sub_dir = self.temp_dir / "archived"
        sub_dir.mkdir()

        res = evaluate_logs(self.temp_dir, retention_days=7, ref_date=self.ref_date)

        ignored_names = [item[0] for item in res["ignored"]]
        self.assertIn("README.md", ignored_names)
        self.assertIn(".gitkeep", ignored_names)
        self.assertIn("release-notes.txt", ignored_names)
        self.assertIn("invalid-date-2026-99-99.md", ignored_names)
        self.assertIn("archived", ignored_names)

        self.assertEqual(len(res["retained"]), 0)
        self.assertEqual(len(res["expired"]), 0)

    # ── Test 3: Missing directory handled safely ──────────────────────────────

    def test_missing_directory_handled_safely(self):
        non_existent_dir = self.temp_dir / "does_not_exist"
        res = evaluate_logs(non_existent_dir, retention_days=7, ref_date=self.ref_date)
        self.assertEqual(res["status"], "missing_directory")
        self.assertEqual(len(res["expired"]), 0)
        self.assertEqual(len(res["retained"]), 0)

    # ── Test 4: Dry-run makes NO changes on disk ──────────────────────────────

    def test_dry_run_cli_makes_no_changes(self):
        old_file = self.create_file("2026-10-01.md")
        new_file = self.create_file("2026-10-25.md")
        readme = self.create_file("README.md")

        cli_script = SCRIPT_DIR / "clean_dev_logs.py"
        proc = subprocess.run(
            [sys.executable, str(cli_script), "--dir", str(self.temp_dir), "--reference-date", "2026-10-25"],
            capture_output=True,
            text=True
        )

        self.assertEqual(proc.returncode, 0)
        self.assertIn("DRY-RUN", proc.stdout)
        self.assertIn("2026-10-01.md", proc.stdout)
        self.assertIn("[PROPOSED FOR DELETION]", proc.stdout)

        # Confirm all files still exist on disk!
        self.assertTrue(old_file.exists(), "Dry run must NOT delete expired files")
        self.assertTrue(new_file.exists(), "New file must exist")
        self.assertTrue(readme.exists(), "README must exist")

    # ── Test 5: --apply deletes only expired files ─────────────────────────────

    def test_apply_cli_deletes_only_expired_files(self):
        old_file = self.create_file("2026-10-01.md")
        new_file = self.create_file("2026-10-25.md")
        readme = self.create_file("README.md")

        cli_script = SCRIPT_DIR / "clean_dev_logs.py"
        proc = subprocess.run(
            [sys.executable, str(cli_script), "--dir", str(self.temp_dir), "--reference-date", "2026-10-25", "--apply"],
            capture_output=True,
            text=True
        )

        self.assertEqual(proc.returncode, 0)
        self.assertIn("APPLY", proc.stdout)

        # Confirm old file was deleted, but new file and README were retained!
        self.assertFalse(old_file.exists(), "Expired file must be deleted when --apply is set")
        self.assertTrue(new_file.exists(), "New file must survive")
        self.assertTrue(readme.exists(), "README must survive")

    # ── Test 6: Permanent documentation safety ────────────────────────────────

    def test_permanent_documentation_not_in_dev_logs_scope(self):
        # Emulate root project structure
        project_root = self.temp_dir / "project"
        dev_logs = project_root / "dev-logs"
        dev_logs.mkdir(parents=True)

        # Create permanent files in project root
        proj_context = project_root / "PROJECT_CONTEXT.md"
        proj_context.write_text("# Context", encoding="utf-8")
        progress_log = project_root / "PROGRESS_LOG.md"
        progress_log.write_text("# Progress", encoding="utf-8")
        decisions = project_root / "DECISIONS.md"
        decisions.write_text("# Decisions", encoding="utf-8")

        # Put an old log in dev-logs
        old_log = dev_logs / "2026-01-01.md"
        old_log.write_text("old", encoding="utf-8")

        cli_script = SCRIPT_DIR / "clean_dev_logs.py"
        subprocess.run(
            [sys.executable, str(cli_script), "--dir", str(dev_logs), "--reference-date", "2026-10-25", "--apply"],
            check=True
        )

        self.assertFalse(old_log.exists())
        self.assertTrue(proj_context.exists(), "PROJECT_CONTEXT.md must never be affected")
        self.assertTrue(progress_log.exists(), "PROGRESS_LOG.md must never be affected")
        self.assertTrue(decisions.exists(), "DECISIONS.md must never be affected")

if __name__ == "__main__":
    unittest.main()
