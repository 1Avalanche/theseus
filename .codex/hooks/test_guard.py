#!/usr/bin/env python3
"""
Stop-хук: перед завершением хода прогоняет `./gradlew check` (юнит-тесты, ktlint, detekt),
если код изменился с последнего зелёного прогона. Падение — ход не завершается, агенту
возвращается выжимка ошибок.

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

GRADLE_TASKS = ["check"]
GRADLE_TIMEOUT_SEC = 540
CODE_FILES = re.compile(r"(\.kt|\.kts|\.toml|\.sql|(^|/)gradle\.properties)$")
STATE_FILE = "claude-test-guard.json"
SUMMARY_MAX_LINES = 60
SUMMARY_MAX_CHARS = 4000
# Строки вывода Gradle, полезные агенту: ошибки компиляции, упавшие тесты, линтеры, причина сбоя.
SUMMARY_LINE = re.compile(
    r"^(e: |w: file)|FAILED|Exception|AssertionFailedError|expected:|Lint error|"
    r"\.kts?:\d+:\d+|^\* What went wrong|^> (?!Task |Run with|Get more help)|^BUILD FAILED"
)


def git(project, *args):
    return subprocess.run(["git", "-C", project] + list(args), capture_output=True,
                          text=True, check=True).stdout


def code_fingerprint(project):
    """Отпечаток всех кодовых файлов рабочей копии (без игнорируемых git)."""
    files = git(project, "ls-files", "-z", "--cached", "--others", "--exclude-standard").split("\0")
    digest = hashlib.sha256()
    count = 0
    for path in sorted(f for f in files if f and CODE_FILES.search(f)):
        full = os.path.join(project, path)
        if not os.path.isfile(full):
            continue  # удалён в рабочей копии
        with open(full, "rb") as f:
            digest.update(path.encode() + b"\0" + hashlib.sha256(f.read()).digest())
        count += 1
    return digest.hexdigest() if count else None


def state_path(project):
    return os.path.join(git(project, "rev-parse", "--absolute-git-dir").strip(), STATE_FILE)


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


def summarize(output):
    """Выжимка вывода Gradle: только строки об ошибках, с ограничением объёма."""
    lines = [line.rstrip() for line in output.splitlines() if SUMMARY_LINE.search(line)]
    if not lines:
        lines = output.strip().splitlines()[-SUMMARY_MAX_LINES:]
    text = "\n".join(lines[:SUMMARY_MAX_LINES])
    return text[:SUMMARY_MAX_CHARS]


def decide(project, stop_hook_active, runner=run_gradle):
    """Возвращает dict для вывода хука или None, если вмешиваться не нужно."""
    if not os.path.isfile(os.path.join(project, "gradlew")):
        return None
    fp = code_fingerprint(project)
    if fp is None:
        return None
    store = state_path(project)
    if load_state(store).get("green_fingerprint") == fp:
        return None  # этот код уже проверен и был зелёным
    code, output = runner(project)
    if code == 0:
        save_state(store, fp)
        return None
    summary = summarize(output)
    command = "./gradlew " + " ".join(GRADLE_TASKS)
    if stop_hook_active:
        return {"systemMessage": "test_guard: `%s` всё ещё падает после попытки исправления. "
                                 "Ход завершён без зелёной сборки." % command}
    return {
        "decision": "block",
        "reason": (
            "Сборка не зелёная (test_guard, `%s`). Исправь ошибки перед завершением.\n"
            "Стиль ktlint — `./gradlew ktlintFormat`. Не ослабляй и не удаляй тесты, чтобы "
            "позеленить сборку; если тест неверен — объясни почему.\n\n%s" % (command, summary)
        ),
    }


def main():
    try:
        data = json.load(sys.stdin)
        project = os.environ.get("CLAUDE_PROJECT_DIR") or data.get("cwd") or os.getcwd()
        result = decide(project, bool(data.get("stop_hook_active")))
    except Exception as e:  # fail-open: сломанный хук не должен запирать агента
        print("test_guard: %r" % e, file=sys.stderr)
        return 0
    if result:
        print(json.dumps(result, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    sys.exit(main())
