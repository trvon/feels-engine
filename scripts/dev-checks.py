#!/usr/bin/env python3
"""Local Git gates. Hooks never download tools, format files, or touch devices."""

import argparse
import hashlib
import http.client
import os
import shutil
import subprocess
import sys
import tempfile
import urllib.parse
from pathlib import Path, PurePosixPath

KTFMT_VERSION = "0.64"
KTFMT_SHA256 = "5b3d5286fd2defcc7dc8e28c21ddf156cc6b2d8682bdcd929ce4333e7a6201f2"
KTFMT_URL = (
    "https://repo.maven.apache.org/maven2/com/facebook/ktfmt/"
    f"{KTFMT_VERSION}/ktfmt-{KTFMT_VERSION}-with-dependencies.jar"
)


class CheckFailed(Exception):
    pass


def run(args, cwd, **kwargs):
    return subprocess.run([str(a) for a in args], cwd=cwd, check=True, **kwargs)


def git(root, *args):
    return run(["git", *args], root, stdout=subprocess.PIPE).stdout


def root_dir():
    return Path(git(Path.cwd(), "rev-parse", "--show-toplevel").decode().strip())


def names(data):
    return [os.fsdecode(p) for p in data.split(b"\0") if p]


def changed(root, *revisions, staged=False):
    args = ["diff", "--name-only", "-z", "--no-renames", "--diff-filter=ACM"]
    if staged:
        args.append("--cached")
    return names(git(root, *args, *revisions, "--"))


def require(tool):
    if not shutil.which(tool):
        raise CheckFailed(f"Missing {tool}. Install it before enabling/running hooks.")


def java_home():
    if os.environ.get("JAVA_HOME"):
        return Path(os.environ["JAVA_HOME"])
    if sys.platform == "darwin":
        found = subprocess.run(
            ["/usr/libexec/java_home", "-v", "21"],
            capture_output=True,
            text=True,
            check=False,
        )
        if found.returncode == 0:
            return Path(found.stdout.strip())
    return None


def java():
    home = java_home()
    executable = str(home / "bin/java") if home else shutil.which("java")
    if not executable or not Path(executable).is_file():
        raise CheckFailed("JDK required. Set JAVA_HOME to JDK 21 (the CI version).")
    return executable


def jar_path(root):
    return root / "build/dev-tools" / f"ktfmt-{KTFMT_VERSION}.jar"


def verify_jar(path):
    if not path.is_file():
        raise CheckFailed(
            "ktfmt is missing. Run: python3 scripts/dev-checks.py install"
        )
    with path.open("rb") as source:
        digest = hashlib.file_digest(source, "sha256").hexdigest()
    if digest != KTFMT_SHA256:
        raise CheckFailed(
            "ktfmt checksum mismatch; remove the cached JAR and rerun install."
        )


def download_formatter(root):
    path = jar_path(root)
    if path.exists():
        verify_jar(path)
        return
    path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(dir=path.parent, delete=False) as output:
        temporary = Path(output.name)
        try:
            print(
                f"Downloading pinned ktfmt {KTFMT_VERSION} from Maven Central",
                flush=True,
            )
            url = urllib.parse.urlsplit(KTFMT_URL)
            if url.scheme != "https" or url.netloc != "repo.maven.apache.org":
                raise CheckFailed(
                    "Formatter downloads require the pinned HTTPS Maven origin."
                )
            connection = http.client.HTTPSConnection(url.netloc, timeout=60)
            try:
                connection.request("GET", url.path)
                response = connection.getresponse()
                if response.status != 200:
                    raise CheckFailed(
                        f"Formatter download failed: HTTP {response.status}; redirects refused."
                    )
                total = 0
                while chunk := response.read(1024 * 1024):
                    total += len(chunk)
                    if total > 128 * 1024 * 1024:
                        raise CheckFailed("Unexpectedly large ktfmt download.")
                    output.write(chunk)
            finally:
                connection.close()
            output.close()
            verify_jar(temporary)
            temporary.replace(path)
        finally:
            temporary.unlink(missing_ok=True)


def install(root):
    require("gitleaks")
    require("opengrep")
    java()
    previous = subprocess.run(
        ["git", "config", "--get", "core.hooksPath"],
        cwd=root,
        capture_output=True,
        text=True,
        check=False,
    ).stdout.strip()
    if previous and previous != ".githooks":
        raise CheckFailed(f"Refusing to replace existing core.hooksPath: {previous}")
    if not previous:
        hooks = Path(os.fsdecode(git(root, "rev-parse", "--git-path", "hooks")).strip())
        if not hooks.is_absolute():
            hooks = root / hooks
        for name in ("pre-commit", "pre-push"):
            if (hooks / name).exists():
                raise CheckFailed(
                    f"Existing {name} hook; integrate it before installing these hooks."
                )
    download_formatter(root)
    for name in ("pre-commit", "pre-push"):
        path = root / ".githooks" / name
        path.chmod(path.stat().st_mode | 0o111)
    git(root, "config", "--local", "core.hooksPath", ".githooks")
    print("Enabled repository-local .githooks (no global Git configuration changed).")


def protected(path):
    p = PurePosixPath(path)
    if p.parts[:2] == ("docs", "local"):
        return not (p.name == "README.md" or ".example." in p.name)
    return (
        ".pi" in p.parts
        or p.name in {"local.properties", "signing.properties", ".env"}
        or (p.name.startswith(".env.") and not p.name.endswith((".example", ".sample")))
        or p.suffix.lower() in {".jks", ".keystore", ".p12", ".pfx", ".key"}
    )


def snapshot(root, destination, paths, revision=":"):
    """Materialize Git blobs, never worktree contents or symlink targets."""
    for name in paths:
        path = PurePosixPath(name)
        if path.is_absolute() or ".." in path.parts:
            raise CheckFailed(f"Unsafe Git path: {name!r}")
        if protected(name):
            raise CheckFailed(f"Private/local artifact must not be committed: {name!r}")
        if revision == ":":
            entry = git(root, "ls-files", "--stage", "-z", "--", name).split(b"\t", 1)[
                0
            ]
            fields = entry.split()
            if len(fields) != 3 or fields[2] != b"0":
                raise CheckFailed(f"Unmerged index entry: {name!r}")
            mode, oid = fields[:2]
        else:
            entry = git(root, "ls-tree", "-z", revision, "--", name).split(b"\t", 1)[0]
            mode, _, oid = entry.split()
        if mode not in (b"100644", b"100755"):
            raise CheckFailed(
                f"Symlink/submodule cannot be scanned as a file: {name!r}"
            )
        target = destination / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(git(root, "cat-file", "blob", oid.decode()))


def secrets(root, directory):
    require("gitleaks")
    run(
        [
            "gitleaks",
            "dir",
            "--config",
            root / ".gitleaks.toml",
            "--redact",
            "--ignore-gitleaks-allow",
            "--no-banner",
            "--timeout",
            "120",
            directory,
        ],
        root,
    )


def format_files(root, directory, paths, write=False):
    kotlin = [directory / p for p in paths if p.endswith((".kt", ".kts"))]
    if not kotlin:
        return
    jar = jar_path(root)
    verify_jar(jar)
    args = [java(), "-jar", jar, "--kotlinlang-style"]
    if not write:
        args += ["--dry-run", "--set-exit-if-changed"]
    # Batch to avoid platform command-line limits. No shell splitting of filenames.
    for start in range(0, len(kotlin), 40):
        run([*args, *kotlin[start : start + 40]], root)


def pre_commit(root):
    paths = changed(root, staged=True)
    if not paths:
        return
    git(root, "diff", "--cached", "--check")
    with tempfile.TemporaryDirectory(prefix="fengine-staged-") as tmp:
        directory = Path(tmp)
        snapshot(root, directory, paths)
        secrets(root, directory)
        format_files(root, directory, paths)
    print("Staged secret and format checks passed. Index/worktree not modified.")


def opengrep(root, target):
    require("opengrep")
    run(
        [
            "opengrep",
            "scan",
            "--config",
            root / "tools/opengrep",
            "--disable-version-check",
            "--disable-nosem",
            "--error",
            "--strict",
            "--no-git-ignore",
            "--max-target-bytes=0",
            "--timeout=10",
            target,
        ],
        root,
        env={**os.environ, "SEMGREP_SEND_METRICS": "off"},
    )


def security(root):
    # Only tracked HEAD blobs: ignored signing keys, photos and tool caches stay out.
    paths = names(git(root, "ls-tree", "-r", "--name-only", "-z", "HEAD"))
    with tempfile.TemporaryDirectory(prefix="fengine-security-") as tmp:
        directory = Path(tmp)
        snapshot(root, directory, paths, "HEAD")
        secrets(root, directory)
        if (directory / "app").is_dir():
            opengrep(root, directory / "app")


def ensure_cached_gradle(root):
    properties = {}
    for line in (
        (root / "gradle/wrapper/gradle-wrapper.properties").read_text().splitlines()
    ):
        if "=" in line and not line.lstrip().startswith("#"):
            key, value = line.split("=", 1)
            properties[key] = value.replace("\\:", ":")
    archive = Path(urllib.parse.urlsplit(properties["distributionUrl"]).path).name
    distribution = archive.removesuffix(".zip")
    home = Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle"))
    base = root if properties.get("distributionBase") == "PROJECT" else home
    cache = base / properties.get("distributionPath", "wrapper/dists") / distribution
    for marker in cache.glob(f"*/{archive}.ok"):
        binary = marker.parent / distribution.rsplit("-", 1)[0] / "bin/gradle"
        if marker.is_file() and binary.is_file():
            return
    raise CheckFailed(
        "Gradle is not cached. Run the normal wrapper build once before using hooks."
    )


def fuzz(root):
    # Refuse wrapper downloads too: --offline alone only covers dependency resolution.
    ensure_cached_gradle(root)
    # Task-scoped --rerun forces new trials, not recompilation of every dependency.
    env = dict(os.environ)
    home = java_home()
    if home:
        env["JAVA_HOME"] = str(home)
    run(
        [
            root / "gradlew",
            "--offline",
            "--no-daemon",
            "--max-workers=2",
            ":app:testDebugUnitTest",
            "--rerun",
            ":app:lintDebug",
        ],
        root,
        env=env,
    )


def outgoing(root, lines):
    pairs = []
    head = git(root, "rev-parse", "HEAD").decode().strip()
    for line in lines:
        fields = line.split()
        if len(fields) != 4:
            raise CheckFailed("Malformed pre-push ref input.")
        _local_ref, local_oid, _remote_ref, remote_oid = fields
        if set(local_oid) == {"0"}:  # A ref deletion has no new code to test.
            continue
        tip = git(root, "rev-parse", f"{local_oid}^{{commit}}").decode().strip()
        if tip != head:
            raise CheckFailed(
                "Push one checked-out commit at a time; other tips are not tested."
            )
        pairs.append((tip, remote_oid))
    return pairs


def pre_push(root, lines):
    pairs = outgoing(root, lines)
    if not pairs:
        return
    # Gradle must test the code being pushed, not unrelated unstaged/staged edits.
    if git(root, "status", "--porcelain", "--untracked-files=normal"):
        raise CheckFailed(
            "Pre-push requires a clean worktree/index (ignored local files are fine)."
        )
    for tip, base in pairs:
        log_opts = f"{base}..{tip}" if set(base) != {"0"} else tip
        require("gitleaks")
        run(
            [
                "gitleaks",
                "git",
                "--config",
                root / ".gitleaks.toml",
                "--redact",
                "--ignore-gitleaks-allow",
                "--no-banner",
                "--timeout",
                "120",
                "--log-opts",
                log_opts,
                root,
            ],
            root,
        )
        # Existing formatting debt is not reformatted by installing hooks.
        if set(base) != {"0"}:
            paths = changed(root, base, tip)
        else:
            commits = (
                git(root, "rev-list", tip, "--not", "--remotes").decode().splitlines()
            )
            candidates = set()
            for commit in commits:
                candidates.update(
                    names(
                        git(
                            root,
                            "diff-tree",
                            "--root",
                            "--no-commit-id",
                            "--name-only",
                            "-z",
                            "--no-renames",
                            "--diff-filter=ACM",
                            "-r",
                            commit,
                        )
                    )
                )
            live = set(names(git(root, "ls-tree", "-r", "--name-only", "-z", tip)))
            paths = sorted(candidates & live)
        with tempfile.TemporaryDirectory(prefix="fengine-push-format-") as tmp:
            snapshot(root, Path(tmp), paths, tip)
            format_files(root, Path(tmp), paths)
    security(root)
    fuzz(root)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "command",
        choices=[
            "install",
            "pre-commit",
            "pre-push",
            "format",
            "security",
            "fuzz",
            "rule-tests",
        ],
    )
    parser.add_argument("paths", nargs="*")
    parser.add_argument(
        "--write", action="store_true", help="Apply formatting (format command only)"
    )
    args = parser.parse_intermixed_args()
    root = root_dir()
    if args.command == "install":
        install(root)
    elif args.command == "pre-commit":
        pre_commit(root)
    elif args.command == "pre-push":
        pre_push(root, sys.stdin)
    elif args.command == "format":
        paths = args.paths or changed(root, "HEAD")
        format_files(root, root, paths, args.write)
    elif args.command == "security":
        security(root)
    elif args.command == "fuzz":
        fuzz(root)
    elif args.command == "rule-tests":
        require("opengrep")
        for rule in sorted((root / "tools/opengrep").glob("*/*.yaml")):
            run(
                ["opengrep", "test", "--config", rule, rule.with_suffix(".kt")],
                root,
                env={
                    **os.environ,
                    "SEMGREP_SEND_METRICS": "off",
                    "SEMGREP_ENABLE_VERSION_CHECK": "0",
                },
            )


if __name__ == "__main__":
    try:
        main()
    except (CheckFailed, subprocess.CalledProcessError, OSError, ValueError) as error:
        print(f"dev-checks: {error}", file=sys.stderr)
        sys.exit(1)
