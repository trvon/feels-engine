"""Git-index tests use disposable repositories, never the working repository."""

import hashlib
import importlib.util
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

SOURCE = Path(__file__).resolve().parents[1] / "dev-checks.py"
SPEC = importlib.util.spec_from_file_location("dev_checks", SOURCE)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError("Cannot load dev-checks.py")
checks = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(checks)


class GitGateTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="fengine-hook-test-")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.git("init", "-q")
        self.git("config", "user.name", "Hook test")
        self.git("config", "user.email", "hook-test@example.invalid")
        self.put("initial.txt", "initial\n")
        self.git("add", "initial.txt")
        self.git("-c", "core.hooksPath=/dev/null", "commit", "-qm", "initial")
        # Configuration needed by Gitleaks in the external-tool integration test.
        self.put(".gitleaks.toml", "[extend]\nuseDefault = true\n")

    def git(self, *args):
        return subprocess.run(
            ["git", *args],
            cwd=self.root,
            check=True,
            capture_output=True,
        ).stdout

    def put(self, name, content):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content)
        return path

    def test_snapshot_uses_staged_blob_not_unstaged_replacement(self):
        name = "source with space\nand newline.kt"
        self.put(name, "staged secret\n")
        self.git("add", "--", name)
        self.put(name, "unstaged safe replacement\n")
        with tempfile.TemporaryDirectory() as tmp:
            checks.snapshot(
                self.root, Path(tmp), checks.changed(self.root, staged=True)
            )
            self.assertEqual((Path(tmp) / name).read_text(), "staged secret\n")
        self.assertEqual((self.root / name).read_text(), "unstaged safe replacement\n")

    def test_renames_and_deletions_select_only_remaining_blobs(self):
        self.git("mv", "initial.txt", "renamed.txt")
        self.assertEqual(checks.changed(self.root, staged=True), ["renamed.txt"])
        self.git("rm", "-f", "renamed.txt")
        self.assertEqual(checks.changed(self.root, staged=True), [])

    def test_protected_files_and_safe_local_examples(self):
        for name in (
            ".pi/session.json",
            "local.properties",
            "signing.properties",
            "upload.jks",
            ".env",
            ".env.production",
            "docs/local/devices.sh",
        ):
            with self.subTest(name=name):
                self.assertTrue(checks.protected(name))
        for name in (
            "docs/local/README.md",
            "docs/local/devices.example.sh",
            ".env.example",
        ):
            self.assertFalse(checks.protected(name))
        self.put("upload.jks", "not a real key")
        self.git("add", "upload.jks")
        with (
            tempfile.TemporaryDirectory() as tmp,
            self.assertRaises(checks.CheckFailed),
        ):
            checks.snapshot(self.root, Path(tmp), ["upload.jks"])

    def test_symlink_is_rejected_without_following_target(self):
        self.put("outside.txt", "private data")
        (self.root / "link.kt").symlink_to(self.root / "outside.txt")
        self.git("add", "link.kt")
        with (
            tempfile.TemporaryDirectory() as tmp,
            self.assertRaises(checks.CheckFailed),
        ):
            checks.snapshot(self.root, Path(tmp), ["link.kt"])

    def test_fuzz_refuses_uncached_wrapper_download(self):
        self.put(
            "gradle/wrapper/gradle-wrapper.properties",
            "distributionUrl=https://services.gradle.org/distributions/gradle-0.0.0-bin.zip\n",
        )
        with (
            patch.dict("os.environ", {"GRADLE_USER_HOME": str(self.root / "cache")}),
            self.assertRaises(checks.CheckFailed),
        ):
            checks.ensure_cached_gradle(self.root)

    def test_checksum_rejects_tampered_formatter(self):
        path = self.put("formatter.jar", "tampered")
        with self.assertRaises(checks.CheckFailed):
            checks.verify_jar(path)

    def test_missing_scanner_is_not_a_pass(self):
        with (
            patch.object(checks.shutil, "which", return_value=None),
            self.assertRaises(checks.CheckFailed),
        ):
            checks.secrets(self.root, self.root)

    def test_push_rejects_dirty_or_unchecked_tips(self):
        oid = self.git("rev-parse", "HEAD").decode().strip()
        zeros = "0" * len(oid)
        # The untracked .gitleaks.toml makes this worktree dirty.
        with self.assertRaises(checks.CheckFailed):
            checks.pre_push(
                self.root, [f"refs/heads/main {oid} refs/heads/main {zeros}"]
            )
        self.put("new.txt", "new\n")
        self.git("add", ".")
        self.git("-c", "core.hooksPath=/dev/null", "commit", "-qm", "second")
        with self.assertRaises(checks.CheckFailed):
            checks.outgoing(self.root, [f"refs/heads/old {oid} refs/heads/old {zeros}"])
        self.assertEqual(
            checks.outgoing(self.root, [f"(delete) {zeros} refs/heads/old {oid}"]), []
        )

    def test_fuzz_task_forces_trials_without_device_tasks(self):
        with (
            patch.object(checks, "run") as run,
            patch.object(checks, "java_home", return_value=None),
            patch.object(checks, "ensure_cached_gradle"),
        ):
            checks.fuzz(self.root)
        command = [str(arg) for arg in run.call_args.args[0]]
        self.assertIn("--rerun", command)
        self.assertIn("--offline", command)
        self.assertIn(":app:testDebugUnitTest", command)
        self.assertIn(":app:lintDebug", command)
        self.assertFalse(any("install" in arg or "connected" in arg for arg in command))

    def test_installer_does_not_replace_existing_hooks(self):
        self.git("config", "core.hooksPath", "existing-hooks")
        with (
            patch.object(checks, "require"),
            patch.object(checks, "java"),
            self.assertRaises(checks.CheckFailed),
        ):
            checks.install(self.root)
        self.assertEqual(
            self.git("config", "core.hooksPath").decode().strip(), "existing-hooks"
        )

    @unittest.skipUnless(
        shutil.which("gitleaks"), "Gitleaks integration requires its CLI"
    )
    def test_staged_synthetic_secret_cannot_be_hidden_by_worktree(self):
        synthetic = (
            "ghp_" + hashlib.sha256(b"fengine synthetic hook fixture").hexdigest()[:36]
        )
        self.put("token.txt", 'GITHUB_TOKEN="' + synthetic + '"\n')
        self.git("add", "token.txt")
        self.put("token.txt", "safe unstaged replacement\n")
        with tempfile.TemporaryDirectory() as tmp:
            checks.snapshot(self.root, Path(tmp), ["token.txt"])
            with self.assertRaises(subprocess.CalledProcessError):
                checks.secrets(self.root, Path(tmp))
        self.assertEqual(
            (self.root / "token.txt").read_text(), "safe unstaged replacement\n"
        )

    @unittest.skipUnless(
        checks.jar_path(SOURCE.parents[1]).is_file(),
        "Formatting integration requires pinned ktfmt",
    )
    def test_format_checks_index_without_rewriting_either_copy(self):
        actual_jar = checks.jar_path(SOURCE.parents[1])
        name = "spaced file.kt"
        bad = "fun x( )=1\n"
        good = "fun x() = 1\n"
        self.put(name, bad)
        self.git("add", "--", name)
        self.put(name, good)
        with (
            tempfile.TemporaryDirectory() as tmp,
            patch.object(checks, "jar_path", return_value=actual_jar),
        ):
            checks.snapshot(self.root, Path(tmp), [name])
            with self.assertRaises(subprocess.CalledProcessError):
                checks.format_files(self.root, Path(tmp), [name])
        self.assertEqual(self.git("show", f":{name}").decode(), bad)
        self.assertEqual((self.root / name).read_text(), good)
        self.git("add", "--", name)
        self.put(name, bad)
        with (
            tempfile.TemporaryDirectory() as tmp,
            patch.object(checks, "jar_path", return_value=actual_jar),
        ):
            checks.snapshot(self.root, Path(tmp), [name])
            checks.format_files(self.root, Path(tmp), [name])
        self.assertEqual((self.root / name).read_text(), bad)

    @unittest.skipUnless(
        shutil.which("gitleaks"), "Gitleaks integration requires its CLI"
    )
    def test_push_rejects_secret_added_then_removed_in_outgoing_history(self):
        base = self.git("rev-parse", "HEAD").decode().strip()
        synthetic = "ghp_" + hashlib.sha256(b"fengine history fixture").hexdigest()[:36]
        self.put("token.txt", 'GITHUB_TOKEN="' + synthetic + '"\n')
        self.git("add", ".")
        self.git("-c", "core.hooksPath=/dev/null", "commit", "-qm", "synthetic fixture")
        self.git("rm", "token.txt")
        self.git("-c", "core.hooksPath=/dev/null", "commit", "-qm", "remove fixture")
        tip = self.git("rev-parse", "HEAD").decode().strip()
        with (
            patch.object(checks, "fuzz") as fuzz,
            self.assertRaises(subprocess.CalledProcessError) as raised,
        ):
            checks.pre_push(
                self.root, [f"refs/heads/main {tip} refs/heads/main {base}"]
            )
        self.assertEqual(raised.exception.cmd[0], "gitleaks")
        fuzz.assert_not_called()


if __name__ == "__main__":
    unittest.main()
