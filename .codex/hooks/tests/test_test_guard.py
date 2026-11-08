"""Тесты хука test_guard. Запуск: python3 -m unittest discover -s .codex/hooks/tests"""
import json
import os
import subprocess
import sys
import tempfile
import unittest

HOOKS_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, HOOKS_DIR)

import test_guard  # noqa: E402


class FakeGradle:
    def __init__(self, code=0, output=""):
        self.code = code
        self.output = output
        self.calls = 0

    def __call__(self, project):
        self.calls += 1
        return self.code, self.output


FAILED_OUTPUT = """\
> Task :agent:compileKotlin UP-TO-DATE
> Task :core:detekt
> Task :core:compileKotlin FAILED
e: file:///p/core/src/main/kotlin/theseus/core/A.kt:3:5 Unresolved reference 'foo'.
some noise line
* What went wrong:
> A failure occurred while executing BuildToolsApiCompilationWork
> Run with --stacktrace option to get the stack trace.
> Get more help at https://help.gradle.org.
BUILD FAILED in 2s
"""


class DecideTest(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.project = os.path.realpath(self._tmp.name)
        subprocess.run(["git", "init", "-q", self.project], check=True)
        self.write("gradlew", "#!/bin/sh\n")

    def tearDown(self):
        self._tmp.cleanup()

    def write(self, path, text):
        full = os.path.join(self.project, path)
        os.makedirs(os.path.dirname(full), exist_ok=True)
        with open(full, "w", encoding="utf-8") as f:
            f.write(text)

    def test_no_code_files_skips_gradle(self):
        gradle = FakeGradle()
        self.write("docs/a.md", "x")
        self.assertIsNone(test_guard.decide(self.project, False, gradle))
        self.assertEqual(0, gradle.calls)

    def test_no_gradlew_skips(self):
        os.remove(os.path.join(self.project, "gradlew"))
        self.write("core/src/main/kotlin/A.kt", "class A")
        gradle = FakeGradle()
        self.assertIsNone(test_guard.decide(self.project, False, gradle))
        self.assertEqual(0, gradle.calls)

    def test_green_run_is_remembered(self):
        self.write("core/src/main/kotlin/A.kt", "class A")
        gradle = FakeGradle(code=0)
        self.assertIsNone(test_guard.decide(self.project, False, gradle))
        self.assertIsNone(test_guard.decide(self.project, False, gradle))
        self.assertEqual(1, gradle.calls)

    def test_claude_green_state_does_not_skip_codex_check(self):
        self.write("core/src/main/kotlin/A.kt", "class A")
        self.write(".git/claude-test-guard.json", json.dumps({
            "green_fingerprint": test_guard.code_fingerprint(self.project),
        }))
        gradle = FakeGradle(code=0)
        self.assertIsNone(test_guard.decide(self.project, False, gradle))
        self.assertEqual(1, gradle.calls)
        self.assertTrue(os.path.isfile(os.path.join(self.project, ".git/codex-test-guard.json")))

    def test_code_change_after_green_runs_again(self):
        self.write("core/src/main/kotlin/A.kt", "class A")
        gradle = FakeGradle(code=0)
        test_guard.decide(self.project, False, gradle)
        self.write("core/src/main/kotlin/A.kt", "class A(val x: Int)")
        test_guard.decide(self.project, False, gradle)
        self.assertEqual(2, gradle.calls)

    def test_docs_change_does_not_rerun(self):
        self.write("core/src/main/kotlin/A.kt", "class A")
        gradle = FakeGradle(code=0)
        test_guard.decide(self.project, False, gradle)
        self.write("docs/modules/core.md", "# core")
        test_guard.decide(self.project, False, gradle)
        self.assertEqual(1, gradle.calls)

    def test_resources_and_build_config_rerun_gradle(self):
        for path in ("core/src/main/resources/application.yaml",
                     "agent/src/main/resources/prompts/system.md",
                     "core/src/test/resources/input.json", "config/detekt/detekt.yml",
                     "gradle/libs.versions.toml"):
            with self.subTest(path=path):
                gradle = FakeGradle()
                self.write(path, "before")
                test_guard.decide(self.project, False, gradle)
                self.write(path, "after")
                test_guard.decide(self.project, False, gradle)
                self.assertEqual(2, gradle.calls)

    def test_role_toml_does_not_run_gradle(self):
        self.write(".codex/agents/docs-writer.toml", 'model = "gpt-6.1-terra"')
        gradle = FakeGradle()
        self.assertIsNone(test_guard.decide(self.project, False, gradle))
        self.assertEqual(0, gradle.calls)

    def test_python_changes_run_only_hook_tests_and_remember_green_result(self):
        self.write(".codex/hooks/tests/test_sample.py", "# тест")
        os.remove(os.path.join(self.project, "gradlew"))
        gradle, hooks = FakeGradle(), FakeGradle()
        self.assertIsNone(test_guard.decide(self.project, False, gradle, hooks))
        self.assertIsNone(test_guard.decide(self.project, False, gradle, hooks))
        self.assertEqual(0, gradle.calls)
        self.assertEqual(1, hooks.calls)
        self.write(".codex/hooks/example.py", "# изменение хука")
        test_guard.decide(self.project, False, gradle, hooks)
        self.assertEqual(2, hooks.calls)

    def test_log_checker_and_hook_json_invalidate_hook_tests(self):
        self.write(".codex/hooks/tests/test_sample.py", "# тест")
        gradle, hooks = FakeGradle(), FakeGradle()
        test_guard.decide(self.project, False, gradle, hooks)
        for path in (".codex/hooks.json", ".agents/skills/local-stand-check/scripts/check_logs.py"):
            self.write(path, "changed")
            test_guard.decide(self.project, False, gradle, hooks)
        self.assertEqual(0, gradle.calls)
        self.assertEqual(3, hooks.calls)

    def test_successful_gradle_does_not_hide_failed_python_tests(self):
        self.write("core/src/main/kotlin/A.kt", "class A")
        self.write(".codex/hooks/tests/test_sample.py", "# тест")
        gradle, hooks = FakeGradle(), FakeGradle(1, "FAILED (failures=1)")
        result = test_guard.decide(self.project, False, gradle, hooks)
        self.assertEqual("block", result["decision"])
        self.assertIn("unittest discover", result["reason"])
        result = test_guard.decide(self.project, True, gradle, hooks)
        self.assertIn("всё ещё падает", result["systemMessage"])
        self.assertEqual(1, gradle.calls)
        self.assertEqual(2, hooks.calls)

    def test_failed_gradle_still_runs_python_tests(self):
        self.write("core/src/main/kotlin/A.kt", "class A")
        self.write(".codex/hooks/tests/test_sample.py", "# тест")
        hooks = FakeGradle()
        result = test_guard.decide(self.project, False, FakeGradle(1, FAILED_OUTPUT), hooks)
        self.assertEqual("block", result["decision"])
        self.assertEqual(1, hooks.calls)

    def test_timeout_or_launch_failure_does_not_skip_other_check(self):
        self.write("core/src/main/kotlin/A.kt", "class A")
        self.write(".codex/hooks/tests/test_sample.py", "# тест")
        for error in (subprocess.TimeoutExpired("./gradlew", 540), OSError("нет исполняемого файла")):
            with self.subTest(error=error):
                def runner(project):
                    raise error

                self.write(".codex/hooks/tests/test_sample.py", str(error))
                hooks = FakeGradle()
                result = test_guard.decide(self.project, False, runner, hooks)
                self.assertEqual("block", result["decision"])
                self.assertIn("Не удалось выполнить проверку", result["reason"])
                self.assertEqual(1, hooks.calls)

    def test_failure_blocks_with_summary(self):
        self.write("core/src/main/kotlin/A.kt", "class A")
        result = test_guard.decide(self.project, False, FakeGradle(1, FAILED_OUTPUT))
        self.assertEqual("block", result["decision"])
        self.assertIn("Unresolved reference 'foo'", result["reason"])
        self.assertIn("ktlintFormat", result["reason"])
        self.assertIn(":core:compileKotlin FAILED", result["reason"])
        self.assertIn("A failure occurred", result["reason"])
        for noise in ("some noise line", "UP-TO-DATE", ":core:detekt\n", "Run with", "Get more help"):
            self.assertNotIn(noise, result["reason"])

    def test_failure_is_not_remembered(self):
        self.write("core/src/main/kotlin/A.kt", "class A")
        gradle = FakeGradle(1, FAILED_OUTPUT)
        test_guard.decide(self.project, False, gradle)
        test_guard.decide(self.project, False, gradle)
        self.assertEqual(2, gradle.calls)

    def test_repeated_failure_does_not_loop(self):
        self.write("core/src/main/kotlin/A.kt", "class A")
        result = test_guard.decide(self.project, True, FakeGradle(1, FAILED_OUTPUT))
        self.assertNotIn("decision", result)
        self.assertIn("всё ещё падает", result["systemMessage"])


class SummarizeTest(unittest.TestCase):
    def test_python_failure_retains_test_name_and_error(self):
        output = "FAIL: test_example (test_sample.ExampleTest)\nAssertionError: missing value\nFAILED (failures=1)"
        summary = test_guard.summarize(output)
        self.assertIn("test_example", summary)
        self.assertIn("AssertionError: missing value", summary)

    def test_falls_back_to_tail_when_nothing_matches(self):
        output = "\n".join("line %d" % i for i in range(200))
        summary = test_guard.summarize(output)
        self.assertIn("line 199", summary)
        self.assertNotIn("line 100\n", summary)

    def test_limits_size(self):
        output = "\n".join("e: error %d %s" % (i, "x" * 200) for i in range(500))
        self.assertLessEqual(len(test_guard.summarize(output)), test_guard.SUMMARY_MAX_CHARS)


if __name__ == "__main__":
    unittest.main()
