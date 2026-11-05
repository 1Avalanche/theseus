"""Тесты хука doc_guard. Запуск: python3 -m unittest discover -s .claude/hooks/tests"""
import json
import os
import subprocess
import sys
import tempfile
import unittest

HOOKS_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, HOOKS_DIR)

import doc_guard  # noqa: E402


class ClassifyTest(unittest.TestCase):
    def test_code_maps_to_module_doc(self):
        self.assertEqual({"core/src/main/kotlin/A.kt": {"docs/modules/core.md"}},
                         doc_guard.classify(["core/src/main/kotlin/A.kt"]))

    def test_nested_module_name(self):
        result = doc_guard.classify(["core/reminders/src/main/kotlin/A.kt"])
        self.assertEqual({"docs/modules/core-reminders.md"}, result["core/reminders/src/main/kotlin/A.kt"])

    def test_mcp_tool_maps_to_api_and_module(self):
        result = doc_guard.classify(["core/src/main/kotlin/theseus/mcp/ReminderTools.kt"])
        self.assertEqual({"docs/api/mcp.md", "docs/modules/core.md"},
                         result["core/src/main/kotlin/theseus/mcp/ReminderTools.kt"])

    def test_migration_maps_to_operations(self):
        path = "core/src/main/resources/db/migration/V2__add_quota.sql"
        self.assertIn("docs/operations.md", doc_guard.classify([path])[path])

    def test_infrastructure_files(self):
        result = doc_guard.classify(["docker-compose.yml", ".env.example", "gradle/libs.versions.toml",
                                     ".claude/hooks/bash_guard.py", "agent/src/main/resources/persona/card.md"])
        self.assertEqual({"docs/operations.md"}, result["docker-compose.yml"])
        self.assertEqual({"docs/operations.md"}, result[".env.example"])
        self.assertEqual({"docs/architecture.md"}, result["gradle/libs.versions.toml"])
        self.assertEqual({"docs/claude-hooks.md"}, result[".claude/hooks/bash_guard.py"])
        self.assertIn("docs/persona.md", result["agent/src/main/resources/persona/card.md"])

    def test_build_logic_and_root_have_no_module_doc(self):
        result = doc_guard.classify(["build-logic/src/main/kotlin/theseus.kotlin-jvm.gradle.kts",
                                     "src/main/kotlin/Main.kt"])
        self.assertEqual({"docs/architecture.md"},
                         result["build-logic/src/main/kotlin/theseus.kotlin-jvm.gradle.kts"])
        self.assertNotIn("src/main/kotlin/Main.kt", result)

    def test_ignored_paths(self):
        self.assertEqual({}, doc_guard.classify([
            "core/src/test/kotlin/ATest.kt", ".claude/hooks/tests/test_x.py", "docs/api/mcp.md",
            "core/README.md", "CLAUDE.md", ".claude/rules/testing.md", ".idea/misc.xml", "notes.txt",
        ]))


class DecideTest(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.project = os.path.realpath(self._tmp.name)
        self.git("init", "-q")
        self.git("config", "user.email", "test@example.com")
        self.git("config", "user.name", "test")
        self.write("README.md", "x")
        self.git("add", ".")
        self.git("commit", "-qm", "init")

    def tearDown(self):
        self._tmp.cleanup()

    def git(self, *args):
        subprocess.run(["git", "-C", self.project] + list(args), check=True, capture_output=True)

    def write(self, path, text):
        full = os.path.join(self.project, path)
        os.makedirs(os.path.dirname(full) or self.project, exist_ok=True)
        with open(full, "w", encoding="utf-8") as f:
            f.write(text)

    def test_no_changes_passes(self):
        self.assertIsNone(doc_guard.decide(self.project, False))

    def test_code_change_blocks_once(self):
        self.write("core/src/main/kotlin/A.kt", "class A")
        reason = doc_guard.decide(self.project, False)
        self.assertIn("docs/modules/core.md", reason)
        self.assertIn("docs-writer", reason)
        self.assertIsNone(doc_guard.decide(self.project, False))

    def test_new_change_blocks_again(self):
        self.write("core/src/main/kotlin/A.kt", "class A")
        doc_guard.decide(self.project, False)
        self.write("core/src/main/kotlin/A.kt", "class A(val x: Int)")
        self.assertIsNotNone(doc_guard.decide(self.project, False))

    def test_stop_hook_active_never_blocks(self):
        self.write("core/src/main/kotlin/A.kt", "class A")
        self.assertIsNone(doc_guard.decide(self.project, True))

    def test_docs_changed_together_passes(self):
        self.write("core/src/main/kotlin/A.kt", "class A")
        self.write("docs/modules/core.md", "# core")
        self.assertIsNone(doc_guard.decide(self.project, False))

    def test_partial_docs_still_blocks(self):
        self.write("core/src/main/kotlin/theseus/mcp/Tools.kt", "class Tools")
        self.write("docs/modules/core.md", "# core")
        self.assertIn("docs/api/mcp.md", doc_guard.decide(self.project, False))

    def test_test_only_change_passes(self):
        self.write("core/src/test/kotlin/ATest.kt", "class ATest")
        self.assertIsNone(doc_guard.decide(self.project, False))

    def test_deleted_file_counts(self):
        self.write("core/src/main/kotlin/A.kt", "class A")
        self.git("add", ".")
        self.git("commit", "-qm", "add")
        os.remove(os.path.join(self.project, "core/src/main/kotlin/A.kt"))
        self.assertIsNotNone(doc_guard.decide(self.project, False))

    def test_hook_protocol(self):
        self.write("core/src/main/kotlin/A.kt", "class A")
        script = os.path.join(HOOKS_DIR, "doc_guard.py")
        env = dict(os.environ, CLAUDE_PROJECT_DIR=self.project)
        out = subprocess.run([sys.executable, script], input=json.dumps({"stop_hook_active": False}),
                             capture_output=True, text=True, env=env, check=True).stdout
        self.assertEqual("block", json.loads(out)["decision"])
        broken = subprocess.run([sys.executable, script], input="не json",
                                capture_output=True, text=True, env=env)
        self.assertEqual(0, broken.returncode)
        self.assertEqual("", broken.stdout)


if __name__ == "__main__":
    unittest.main()
