"""Проверка логов контейнеров: чтение обоих потоков, ошибки Docker и отсутствие вывода данных."""
import importlib.util
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[3] / ".agents/skills/local-stand-check/scripts/check_logs.py"
SPEC = importlib.util.spec_from_file_location("check_logs", SCRIPT)
check_logs = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(check_logs)


class CheckLogsTest(unittest.TestCase):
    def test_reads_each_container_separately(self):
        calls = []

        def runner(command, **kwargs):
            calls.append(command)
            return subprocess.CompletedProcess(command, 0, "обычный stdout", "обычный stderr")

        self.assertEqual([], check_logs.check_logs("контроль", ["core", "agent"], runner))
        self.assertEqual([["docker", "logs", "core"], ["docker", "logs", "agent"]], calls)

    def test_finds_literal_text_in_either_stream_without_exposing_it(self):
        for stdout, stderr in (("секрет[.*]", ""), ("", "секрет[.*]")):
            with self.subTest(stdout=stdout):
                def runner(command, **kwargs):
                    return subprocess.CompletedProcess(command, 0, stdout, stderr)

                errors = check_logs.check_logs("секрет[.*]", ["core"], runner)
                self.assertEqual(1, len(errors))
                self.assertNotIn("секрет", errors[0])

    def test_docker_failure_is_not_a_clean_log(self):
        def runner(command, **kwargs):
            return subprocess.CompletedProcess(command, 1, "", "закрытые данные Docker")

        errors = check_logs.check_logs("контроль", ["core", "agent"], runner)
        self.assertEqual(2, len(errors))
        self.assertTrue(all("Не удалось прочитать" in error for error in errors))
        self.assertNotIn("закрытые данные", "\n".join(errors))

    def test_missing_docker_and_timeout_fail_check(self):
        for failure in (FileNotFoundError("docker"), subprocess.TimeoutExpired("docker", 15)):
            with self.subTest(failure=failure):
                def runner(command, **kwargs):
                    raise failure

                self.assertEqual(1, len(check_logs.check_logs("контроль", ["core"], runner)))

    def test_empty_control_text_is_rejected(self):
        with self.assertRaises(ValueError):
            check_logs.check_logs("", ["core"])

    def test_cli_returns_nonzero_on_docker_failure_without_printing_logs(self):
        with tempfile.TemporaryDirectory() as directory:
            docker = Path(directory) / "docker"
            docker.write_text("#!/bin/sh\necho 'закрытые данные' >&2\nexit 1\n")
            docker.chmod(0o755)
            env = dict(os.environ, PATH=directory + os.pathsep + os.environ.get("PATH", ""))
            result = subprocess.run([sys.executable, str(SCRIPT), "контроль", "core", "agent"],
                                    capture_output=True, text=True, env=env, check=False)
            self.assertEqual(1, result.returncode)
            self.assertEqual("", result.stdout)
            self.assertIn("core", result.stderr)
            self.assertIn("agent", result.stderr)
            self.assertNotIn("закрытые данные", result.stderr)


if __name__ == "__main__":
    unittest.main()
