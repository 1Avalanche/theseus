#!/usr/bin/env python3
"""
Stop-хук: независимо проверяет Gradle (код, ресурсы и конфиг сборки) и Python-хуки.
Каждая проверка запускается по своему отпечатку; успешный результат другой проверки
не скрывает падение. Агент получает выжимку ошибок.

Запуск по отпечатку содержимого кодовых файлов (отслеживаемых и новых), а не по git status:
так проверка не пропускается, если агент закоммитил изменения внутри хода.

Против зацикливания: при stop_hook_active (ход уже продолжен Stop-хуком) повторное падение
не блокирует, а только предупреждает владельца.

Политика fail-open: если сам хук сломался, ход не блокируется.
Python 3.9+ без внешних зависимостей.
"""
import hashlib
import json
import os
import re
import subprocess
import sys

from hook_context import project_root

GRADLE_TASKS = ["check"]
GRADLE_TIMEOUT_SEC = 540
CODE_FILES = re.compile(
    r"(\.kt|\.kts|\.sql|(^|/)gradle\.properties)$"
    r"|(^|/)gradle/.*\.toml$|(^|/)src/[^/]+/resources/|^config/.*\.ya?ml$"
)
HOOK_FILES = re.compile(
    r"^\.codex/hooks/.*\.py$|^\.codex/hooks\.json$"
    r"|^\.agents/skills/local-stand-check/scripts/.*\.py$"
)
STATE_FILE = "codex-test-guard.json"
HOOK_STATE_FILE = "codex-hooks-test-guard.json"
HOOK_TEST_DIR = ".codex/hooks/tests"
HOOK_TIMEOUT_SEC = 45
SUMMARY_MAX_LINES = 60
SUMMARY_MAX_CHARS = 4000
# Полезные строки Gradle и unittest: ошибки компиляции, тестов, линтеров и причина сбоя.
SUMMARY_LINE = re.compile(
    r"^(e: |w: file)|FAILED|Exception|AssertionFailedError|expected:|Lint error|"
    r"\.kts?:\d+:\d+|^\* What went wrong|^> (?!Task |Run with|Get more help)|^BUILD FAILED|"
    r"^FAIL: |^ERROR: |^  File |Error:"
)


def git(project, *args):
    return subprocess.run(["git", "-C", project] + list(args), capture_output=True,
                          text=True, check=True).stdout


def fingerprint(project, pattern):
    """Отпечаток выбранных файлов рабочей копии (без игнорируемых Git)."""
    files = git(project, "ls-files", "-z", "--cached", "--others", "--exclude-standard").split("\0")
    digest = hashlib.sha256()
    count = 0
    for path in sorted(set(f for f in files if f and pattern.search(f))):
        full = os.path.join(project, path)
        if not os.path.isfile(full):
            continue  # удалён в рабочей копии
        with open(full, "rb") as f:
            digest.update(path.encode() + b"\0" + hashlib.sha256(f.read()).digest())
        count += 1
    return digest.hexdigest() if count else None


def code_fingerprint(project):
    """Код и ресурсы Gradle; TOML ролей Codex не относится к сборке приложения."""
    return fingerprint(project, CODE_FILES)


def state_path(project, filename=STATE_FILE):
    return os.path.join(git(project, "rev-parse", "--absolute-git-dir").strip(), filename)


def load_state(path):
    try:
        with open(path, encoding="utf-8") as f:
            return json.load(f)
    except (OSError, ValueError):
        return {}


def save_state(path, fp):
    with open(path, "w", encoding="utf-8") as f:
        json.dump({"green_fingerprint": fp}, f)


def run_gradle(project):
    proc = subprocess.run(["./gradlew"] + GRADLE_TASKS + ["--console=plain", "--continue"],
                          cwd=project, capture_output=True, text=True, timeout=GRADLE_TIMEOUT_SEC)
    return proc.returncode, proc.stdout + proc.stderr


def run_hook_tests(project):
    proc = subprocess.run([sys.executable, "-m", "unittest", "discover", "-s", HOOK_TEST_DIR],
                          cwd=project, capture_output=True, text=True, timeout=HOOK_TIMEOUT_SEC)
    return proc.returncode, proc.stdout + proc.stderr


def summarize(output):
    """Выжимка вывода проверок: только строки об ошибках, с ограничением объёма."""
    lines = [line.rstrip() for line in output.splitlines() if SUMMARY_LINE.search(line)]
    if not lines:
        lines = output.strip().splitlines()[-SUMMARY_MAX_LINES:]
    text = "\n".join(lines[:SUMMARY_MAX_LINES])
    return text[:SUMMARY_MAX_CHARS]


def check(project, stop_hook_active, runner, fp, filename, command):
    """Запускает одну проверку и запоминает только её успешный отпечаток."""
    if fp is None:
        return None
    store = state_path(project, filename)
    if load_state(store).get("green_fingerprint") == fp:
        return None  # этот код уже проверен и был зелёным
    try:
        code, output = runner(project)
    except (OSError, subprocess.TimeoutExpired) as error:
        code, output = 1, "Не удалось выполнить проверку: %s" % error
    if code == 0:
        save_state(store, fp)
        return None
    summary = summarize(output)
    if stop_hook_active:
        return {"systemMessage": "test_guard: `%s` всё ещё падает после попытки исправления. "
                                 "Ход завершён без зелёной сборки." % command}
    return {
        "decision": "block",
        "reason": (
            "Проверка не зелёная (test_guard, `%s`). Исправь ошибки перед завершением.\n"
            "Стиль ktlint — `./gradlew ktlintFormat`. Не ослабляй и не удаляй тесты, чтобы "
            "позеленить сборку; если тест неверен — объясни почему.\n\n%s" % (command, summary)
        ),
    }


def decide(project, stop_hook_active, runner=run_gradle, hook_runner=run_hook_tests):
    """Возвращает ошибки независимых проверок или None; прогоны не скрывают друг друга."""
    results = []
    if os.path.isfile(os.path.join(project, "gradlew")):
        results.append(check(project, stop_hook_active, runner, code_fingerprint(project),
                             STATE_FILE, "./gradlew " + " ".join(GRADLE_TASKS)))
    if os.path.isdir(os.path.join(project, HOOK_TEST_DIR)):
        results.append(check(project, stop_hook_active, hook_runner, fingerprint(project, HOOK_FILES),
                             HOOK_STATE_FILE, "python3 -m unittest discover -s " + HOOK_TEST_DIR))
    failures = [result for result in results if result]
    if not failures:
        return None
    if stop_hook_active:
        return {"systemMessage": "\n\n".join(result["systemMessage"] for result in failures)}
    return {"decision": "block", "reason": "\n\n".join(result["reason"] for result in failures)}


def main():
    try:
        data = json.load(sys.stdin)
        project = project_root(data)
        result = decide(project, bool(data.get("stop_hook_active")))
    except Exception as e:  # fail-open: сломанный хук не должен запирать агента
        print("test_guard: %r" % e, file=sys.stderr)
        return 0
    if result:
        print(json.dumps(result, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    sys.exit(main())
