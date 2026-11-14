#!/usr/bin/env python3
"""Запускает разрешённые сборки и тесты только из корня этого репозитория."""
import subprocess
import sys
from pathlib import Path

PROJECT = Path(__file__).resolve().parents[2]
TASKS = frozenset((
    "build", "test", "installDist", "check", "ktlintCheck", "detekt",
    ":core:build", ":agent:build", ":core:test", ":agent:test",
    ":core:integrationTest", ":agent:integrationTest",
    ":core:installDist", ":agent:installDist",
))


def command(arguments):
    """Отклоняет произвольные задачи и флаги, включая смену проекта и init-скрипты."""
    tasks = list(arguments) or ["build"]
    if any(task not in TASKS for task in tasks):
        raise ValueError("Разрешены только задачи: " + ", ".join(sorted(TASKS)))
    return [str(PROJECT / "gradlew")] + tasks


def main(arguments=None):
    arguments = sys.argv[1:] if arguments is None else arguments
    if list(arguments) == ["--help"]:
        print(__doc__ + "\nЗадачи: " + ", ".join(sorted(TASKS)))
        return 0
    try:
        selected = command(arguments)
    except ValueError as error:
        print(str(error), file=sys.stderr)
        return 2
    return subprocess.run(selected, cwd=str(PROJECT), check=False).returncode


if __name__ == "__main__":
    sys.exit(main())
