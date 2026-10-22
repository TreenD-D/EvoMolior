#!/usr/bin/env python3
"""Provider-free launcher lifecycle tests (JDK 17+, Python 3; macOS/Linux).

By default exercise the production shell fragments directly. Pass --launcher
<generated bin/evomolior> to additionally exercise Gradle's generated launcher.
All fixtures and child processes belong to this test; no installed daemon is used.
TMPDIR must point to an existing external scratch directory.
"""

import argparse
import os
from pathlib import Path
import shutil
import signal
import subprocess
import tempfile
import time
import unittest


ROOT = Path(__file__).resolve().parents[3]
OPTIONS = None


class ClasspathSnapshotTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if not os.environ.get("TMPDIR"):
            raise RuntimeError("Set TMPDIR to an external scratch directory")
        cls.suite = tempfile.TemporaryDirectory(prefix="launcher-tests-", dir=os.environ["TMPDIR"])
        cls.addClassCleanup(cls.suite.cleanup)
        cls.root = Path(cls.suite.name)
        cls.java_home = Path(os.environ["JAVA_HOME"]) if os.environ.get("JAVA_HOME") else None
        javac = str(cls.java_home / "bin/javac") if cls.java_home else shutil.which("javac")
        jar = str(cls.java_home / "bin/jar") if cls.java_home else shutil.which("jar")
        cls.classes = cls.root / "classes"
        cls.classes.mkdir()
        subprocess.run([javac, "-d", str(cls.classes), str(Path(__file__).with_name("SnapshotProbe.java"))],
                       check=True, capture_output=True, text=True)
        cls.probe = cls.root / "probe.jar"
        subprocess.run([jar, "cf", str(cls.probe), "-C", str(cls.classes), "."],
                       check=True, capture_output=True, text=True)

    def setUp(self):
        self.fixture = tempfile.TemporaryDirectory(prefix="case-", dir=self.root)
        self.work = Path(self.fixture.name)
        self.install = self.work / "install with spaces [literal]"
        (self.install / "lib").mkdir(parents=True)
        (self.install / "bin").mkdir()
        self.tmp = self.work / "tmp with spaces"
        self.tmp.mkdir()
        self.probe_jar = self.install / "lib" / "probe with spaces.jar"
        shutil.copyfile(self.probe, self.probe_jar)
        (self.install / "lib/payload.jar").write_text("original payload")
        self.launcher = self.install / "bin/evomolior"
        if OPTIONS.launcher:
            text = Path(OPTIONS.launcher).read_text()
            lines = text.splitlines()
            classpath_lines = [i for i, line in enumerate(lines) if line.startswith("CLASSPATH=")]
            self.assertEqual(len(classpath_lines), 1)
            lines[classpath_lines[0]] = 'CLASSPATH="$APP_HOME/lib/probe with spaces.jar:$APP_HOME/lib/payload.jar"'
            text = "\n".join(lines).replace(
                "com.achulkov.evomolior.cli.EvoMoliorCliMainKt", "SnapshotProbe")
        else:
            text = (
                '#!/bin/sh\nAPP_HOME=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)\n'
                'cygwin=false\nmsys=false\n'
                'die() { echo "$*" >&2; exit 1; }\n'
                'CLASSPATH="$APP_HOME/lib/probe with spaces.jar:$APP_HOME/lib/payload.jar"\n'
                + (ROOT / "src/startScripts/classpath-snapshot.sh").read_text()
                + '\nJAVACMD="${JAVA_HOME:+$JAVA_HOME/bin/}java"\n'
                + 'set -- -classpath "$CLASSPATH" SnapshotProbe "$@"\n'
                + (ROOT / "src/startScripts/classpath-launch.sh").read_text()
            )
        self.launcher.write_text(text + "\n")
        self.launcher.chmod(0o755)
        self.children = []

    def tearDown(self):
        # Kill/reap only this test's exact children if an assertion timed out.
        for child, output in self.children:
            if child.poll() is None:
                child.terminate()
                try:
                    child.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    child.kill()
                    child.wait(timeout=5)
            pid_file = output / "pid"
            if pid_file.exists() and not (output / "settled").exists():
                try:
                    os.kill(int(pid_file.read_text()), signal.SIGTERM)
                except ProcessLookupError:
                    pass
            for stream in (child.stdin, child.stdout, child.stderr):
                if stream:
                    stream.close()
        self.fixture.cleanup()

    def start(self, mode="wait", extra_env=None, arguments=()):
        output = self.work / f"output-{len(self.children)}"
        output.mkdir()
        env = os.environ.copy()
        env.update(TMPDIR=str(self.tmp), TMP=str(self.tmp), TEMP=str(self.tmp))
        env.pop("EVOMOLIOR_DISABLE_CLASSPATH_SNAPSHOT", None)
        env.pop("JAVA_OPTS", None)
        env.pop("EVOMOLIOR_OPTS", None)
        env.pop("JDK_JAVA_OPTIONS", None)
        env.pop("JAVA_TOOL_OPTIONS", None)
        env.pop("_JAVA_OPTIONS", None)
        if extra_env:
            env.update(extra_env)
        child = subprocess.Popen(
            [OPTIONS.shell, str(self.launcher), str(output), mode, *arguments],
            env=env, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
        )
        self.children.append((child, output))
        return child, output

    def await_file(self, path, child):
        deadline = time.monotonic() + 10
        while not path.exists():
            if child.poll() is not None:
                self.fail(f"Launcher exited {child.returncode} before {path.name}: {child.stderr.read().decode()}")
            if time.monotonic() >= deadline:
                self.fail(f"Timed out waiting for {path}")
            time.sleep(.01)

    def snapshot(self, output):
        return Path((output / "classpath").read_text().split(os.pathsep)[0]).parent.parent

    def settled(self, child, output, status):
        self.assertEqual(child.wait(timeout=10), status, child.stderr.read().decode())
        self.assertFalse((output / "failure").exists())
        self.assertEqual((output / "settled").read_text(), "original payload")
        self.assertEqual(list(self.tmp.iterdir()), [])

    def test_normal_exit_and_arguments(self):
        args = ("space separated", "*", "", "quotes'\"")
        child, output = self.start("0", arguments=args)
        self.settled(child, output, 0)
        self.assertEqual((output / "args").read_text(), "\n".join(args))

    def test_nonzero_exit(self):
        child, output = self.start("37")
        self.settled(child, output, 37)

    def test_stdin_preserved(self):
        child, output = self.start("stdin")
        child.stdin.write(b"caller input\n")
        child.stdin.close()
        self.settled(child, output, 0)
        self.assertEqual((output / "stdin").read_text(), "caller input\n")

    def test_jvm_temp_follows_explicit_external_tmpdir(self):
        child, output = self.start("0")
        self.settled(child, output, 0)
        self.assertEqual(Path((output / "jvm-temp").read_text()), self.tmp)

    def test_disabled_snapshot_still_binds_jvm_temp(self):
        child, output = self.start("0", {"EVOMOLIOR_DISABLE_CLASSPATH_SNAPSHOT": "1"})
        self.settled(child, output, 0)
        self.assertEqual(Path((output / "jvm-temp").read_text()), self.tmp)

    def assert_signal(self, sig, expected):
        child, output = self.start()
        self.await_file(output / "ready", child)
        snapshot = self.snapshot(output)
        child.send_signal(sig)
        self.await_file(output / "settling", child)
        self.assertTrue(snapshot.exists(), "Removed snapshot before JVM shutdown hook settled")
        self.settled(child, output, expected)
        self.assertFalse(snapshot.exists())

    def test_int(self):
        self.assert_signal(signal.SIGINT, 130)

    def test_term(self):
        self.assert_signal(signal.SIGTERM, 143)

    def test_hup(self):
        self.assert_signal(signal.SIGHUP, 129)

    def test_repeated_signals_wait_for_shutdown(self):
        child, output = self.start()
        self.await_file(output / "ready", child)
        child.terminate()
        self.await_file(output / "settling", child)
        child.terminate()
        self.settled(child, output, 143)

    def test_concurrent_invocations_and_install_replacement(self):
        first, first_output = self.start()
        second, second_output = self.start()
        self.await_file(first_output / "ready", first)
        self.await_file(second_output / "ready", second)
        first_snapshot = self.snapshot(first_output)
        second_snapshot = self.snapshot(second_output)
        self.assertNotEqual(first_snapshot, second_snapshot)
        # Simulate installDist replacing source files after both snapshots are ready.
        (self.install / "lib/payload.jar").write_text("replaced distribution")
        self.probe_jar.unlink()
        (first_output / "release").touch()
        self.assertEqual(first.wait(timeout=10), 0)
        self.assertFalse(first_snapshot.exists())
        self.assertTrue(second_snapshot.exists())
        self.assertEqual((first_output / "settled").read_text(), "original payload")
        (second_output / "release").touch()
        self.settled(second, second_output, 0)

    def test_disabled_snapshot_retains_exec_behavior(self):
        child, output = self.start(extra_env={"EVOMOLIOR_DISABLE_CLASSPATH_SNAPSHOT": "1"})
        self.await_file(output / "ready", child)
        self.assertEqual(int((output / "pid").read_text()), child.pid)
        self.assertEqual(self.snapshot(output), self.install)
        self.assertEqual(list(self.tmp.iterdir()), [])
        (output / "release").touch()
        self.settled(child, output, 0)

    def test_copy_failure_cleans_partial_snapshot_without_launch(self):
        (self.install / "lib/payload.jar").unlink()
        child, output = self.start("0")
        self.assertNotEqual(child.wait(timeout=10), 0)
        self.assertIn("Could not snapshot jar", child.stderr.read().decode())
        self.assertFalse((output / "ready").exists())
        self.assertEqual(list(self.tmp.iterdir()), [])

    def test_foreign_snapshot_is_never_removed(self):
        foreign = self.tmp / "evomolior-cli-classpath-foreign"
        foreign.mkdir()
        child, output = self.start("0")
        self.assertEqual(child.wait(timeout=10), 0)
        self.assertTrue(foreign.exists())
        self.assertEqual(list(self.tmp.iterdir()), [foreign])

    def test_only_one_disables_snapshot(self):
        child, output = self.start(extra_env={"EVOMOLIOR_DISABLE_CLASSPATH_SNAPSHOT": "0"})
        self.await_file(output / "ready", child)
        self.assertNotEqual(self.snapshot(output), self.install)
        (output / "release").touch()
        self.settled(child, output, 0)

    def test_snapshot_creation_failure_does_not_launch(self):
        missing = self.work / "does-not-exist"
        child, output = self.start("0", extra_env={"TMPDIR": str(missing)})
        self.assertNotEqual(child.wait(timeout=10), 0)
        self.assertIn("Could not create classpath snapshot directory", child.stderr.read().decode())
        self.assertFalse((output / "ready").exists())
        self.assertEqual(list(self.tmp.iterdir()), [])


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--launcher", help="Generated Gradle launcher to test instead of shell fragments")
    parser.add_argument("--shell", default="/bin/sh")
    OPTIONS, unittest_args = parser.parse_known_args()
    unittest.main(argv=[__file__, *unittest_args])
