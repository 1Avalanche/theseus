"""Проверки проектных разрешений без обращения к Docker и выполнения Gradle."""
import ast
import contextlib
import io
import runpy
import shlex
import unittest
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[3]


class ProjectPermissionsTests(unittest.TestCase):
    def setUp(self):
        self.wrapper = runpy.run_path(str(ROOT / '.codex/scripts/gradle_check.py'))

    def test_gradle_uses_fixed_project_and_returns_process_status(self):
        for arguments, tasks in (([], ['build']), (['build', 'installDist'], ['build', 'installDist']),
                                 ([':core:integrationTest'], [':core:integrationTest'])):
            with self.subTest(arguments=arguments), patch('subprocess.run') as execute:
                execute.return_value.returncode = 7
                self.assertEqual(self.wrapper['main'](arguments), 7)
                execute.assert_called_once_with([str(ROOT / 'gradlew')] + tasks, cwd=str(ROOT), check=False)

    def test_unknown_tasks_and_flags_never_execute_gradle(self):
        for arguments in (['--project-dir', '/tmp'], ['-I', '/tmp/extra.gradle'], ['build', 'publish'],
                          ['clean'], ['--help', 'build'], ['build;whoami']):
            with self.subTest(arguments=arguments), patch('subprocess.run') as execute, contextlib.redirect_stderr(io.StringIO()):
                self.assertEqual(self.wrapper['main'](arguments), 2)
                execute.assert_not_called()

    def test_help_does_not_start_gradle(self):
        with patch('subprocess.run') as execute, contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(self.wrapper['main'](['--help']), 0)
            execute.assert_not_called()

    def test_rules_examples_match_and_broad_commands_have_no_allow(self):
        patterns = []
        source = (ROOT / '.codex/rules/commands.rules').read_text()
        for entry in ast.parse(source).body:
            self.assertIsInstance(entry.value, ast.Call)
            self.assertEqual(entry.value.func.id, 'prefix_rule')
            rule = {item.arg: ast.literal_eval(item.value) for item in entry.value.keywords}
            self.assertEqual(rule['decision'], 'allow')
            pattern = rule['pattern']
            patterns.append(pattern)
            for command in rule.get('match', []):
                self.assertEqual(shlex.split(command)[:len(pattern)], pattern)
            for command in rule.get('not_match', []):
                self.assertNotEqual(shlex.split(command)[:len(pattern)], pattern)
        for command in ('docker run alpine', 'docker exec container sh', 'docker compose down',
                        'docker volume rm data', './gradlew build', 'python3 -c print(1)', 'python3 /tmp/smoke.py'):
            words = shlex.split(command)
            self.assertFalse(any(words[:len(pattern)] == pattern for pattern in patterns), command)


if __name__ == '__main__':
    unittest.main()
