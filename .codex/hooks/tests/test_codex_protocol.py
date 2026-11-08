"""Проверка реальных команд hooks.json в переносимой копии проекта и подкаталогах."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


CODEX_DIR = Path(__file__).resolve().parents[2]


class CodexProtocolTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix="theseus codex ")
        self.addCleanup(self.tmp.cleanup)
        self.project = Path(self.tmp.name).resolve()
        subprocess.run(["git", "init", "-q", str(self.project)], check=True)
        self.nested = self.project / "agent/src"
        self.nested.mkdir(parents=True)
        hooks = self.project / ".codex/hooks"
        hooks.mkdir(parents=True)
        for source in (CODEX_DIR / "hooks").glob("*.py"):
            shutil.copy2(source, hooks / source.name)
        self.config = json.loads((CODEX_DIR / "hooks.json").read_text())

    def run_hook(self, event, index=0, **fields):
        handler = self.config["hooks"][event][0]["hooks"][index]
        payload = {"hook_event_name": event, "cwd": str(self.nested), **fields}
        env = dict(os.environ, CLAUDE_PROJECT_DIR="/unused/claude/project")
        result = subprocess.run(handler["command"], shell=True, cwd=self.nested, env=env,
                                input=json.dumps(payload), text=True, capture_output=True,
                                timeout=15, check=True)
        return json.loads(result.stdout) if result.stdout.strip() else None

    def test_bash_guard_blocks_project_write_from_subdirectory(self):
        result = self.run_hook("PreToolUse", tool_name="Bash",
                               tool_input={"command": "echo x > ../AGENTS.md"})
        self.assertEqual("deny", result["hookSpecificOutput"]["permissionDecision"])
        self.assertIn("apply_patch", result["hookSpecificOutput"]["permissionDecisionReason"])
        self.assertNotIn("Edit/Write", result["hookSpecificOutput"]["permissionDecisionReason"])

    def test_bash_guard_allows_read_command(self):
        self.assertIsNone(self.run_hook("PreToolUse", tool_name="Bash",
                                        tool_input={"command": "git status --short"}))

    def test_doc_guard_finds_changes_at_git_root(self):
        source = self.project / "core/src/main/kotlin/A.kt"
        source.parent.mkdir(parents=True)
        source.write_text("class A")
        result = self.run_hook("Stop", stop_hook_active=False)
        self.assertEqual("block", result["decision"])
        self.assertIn("docs/modules/core.md", result["reason"])
        self.assertTrue((self.project / ".git/codex-doc-guard.json").is_file())
        self.assertFalse((self.project / ".git/claude-doc-guard.json").exists())
        self.assertIsNone(self.run_hook("Stop", stop_hook_active=False))

    def test_test_guard_checks_root_and_remembers_green_result(self):
        source = self.project / "core/src/main/kotlin/A.kt"
        source.parent.mkdir(parents=True)
        source.write_text("class A")
        wrapper = self.project / "gradlew"
        wrapper.write_text('#!/bin/sh\n[ "$1" = check ] || exit 1\nexit 0\n')
        wrapper.chmod(0o755)
        self.assertIsNone(self.run_hook("Stop", index=1, stop_hook_active=False))
        self.assertTrue((self.project / ".git/codex-test-guard.json").is_file())
        self.assertFalse((self.project / ".git/claude-test-guard.json").exists())
        wrapper.write_text("#!/bin/sh\nexit 1\n")
        self.assertIsNone(self.run_hook("Stop", index=1, stop_hook_active=False))

    def test_test_guard_returns_failure_and_does_not_loop(self):
        (self.project / "build.gradle.kts").write_text("// проверяемый код")
        wrapper = self.project / "gradlew"
        wrapper.write_text("#!/bin/sh\necho 'e: compile failed'\nexit 1\n")
        wrapper.chmod(0o755)
        result = self.run_hook("Stop", index=1, stop_hook_active=False)
        self.assertEqual("block", result["decision"])
        self.assertIn("e: compile failed", result["reason"])
        repeated = self.run_hook("Stop", index=1, stop_hook_active=True)
        self.assertNotIn("decision", repeated)
        self.assertIn("всё ещё падает", repeated["systemMessage"])

    def test_python_failure_blocks_even_after_green_gradle(self):
        (self.project / "build.gradle.kts").write_text("// код приложения")
        wrapper = self.project / "gradlew"
        wrapper.write_text("#!/bin/sh\nexit 0\n")
        wrapper.chmod(0o755)
        tests = self.project / ".codex/hooks/tests"
        tests.mkdir()
        sample = tests / "test_sample.py"
        sample.write_text("import unittest\nclass SampleTest(unittest.TestCase):\n"
                          "    def test_hook(self):\n        self.fail('ошибка хука')\n")
        result = self.run_hook("Stop", index=1, stop_hook_active=False)
        self.assertEqual("block", result["decision"])
        self.assertIn("test_hook", result["reason"])
        self.assertIn("ошибка хука", result["reason"])
        self.assertTrue((self.project / ".git/codex-test-guard.json").is_file())
        self.assertFalse((self.project / ".git/codex-hooks-test-guard.json").exists())
        repeated = self.run_hook("Stop", index=1, stop_hook_active=True)
        self.assertIn("всё ещё падает", repeated["systemMessage"])
        sample.write_text("import unittest\nclass SampleTest(unittest.TestCase):\n"
                          "    def test_hook(self):\n        self.assertTrue(True)\n")
        self.assertIsNone(self.run_hook("Stop", index=1, stop_hook_active=False))
        self.assertTrue((self.project / ".git/codex-hooks-test-guard.json").is_file())


if __name__ == "__main__":
    unittest.main()
