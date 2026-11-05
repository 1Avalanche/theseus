#!/usr/bin/env python3
"""
Stop-хук: перед завершением хода напоминает агенту сверить документацию с изменениями.

Что требует документации, решает детерминированно: по путям изменённых файлов (git status)
и таблице RULES. Насколько и как править документы — решает агент (обычно через
субагента docs-writer).

Чтобы не зациклиться и не надоедать:
  * срабатывает один раз на набор изменений — отпечаток содержимого сохраняется в .git/;
  * при stop_hook_active (агент уже продолжает работу из-за Stop-хука) не блокирует;
  * не блокирует, если все предложенные документы уже изменены в той же рабочей копии.

Политика fail-open: ошибка хука не мешает агенту завершить ход (это не защитный хук).
Python 3.9+ без внешних зависимостей.
"""
import hashlib
import json
import os
import re
import subprocess
import sys

# Пути, изменения в которых документации не требуют (сами документы, тесты, служебное).
IGNORED = re.compile(
    r"(^|/)src/test/"
    r"|^\.claude/hooks/tests/"
    r"|^docs/"
    r"|(^|/)README\.md$"
    r"|^CLAUDE\.md$"
    r"|^\.claude/(rules|agents|skills)/"
    r"|^\.idea/"
    r"|^\.gitignore$"
)

# (шаблон пути от корня проекта, документы). {module} — модуль, в котором лежит файл.
RULES = [
    (r"^\.claude/hooks/|^\.claude/settings\.json$", ["docs/claude-hooks.md"]),
    (r"(^|/)db/migration/", ["docs/operations.md", "docs/modules/{module}.md"]),
    (r"(^|/)resources/persona/", ["docs/persona.md"]),
    (r"(^|/)resources/prompts/", ["docs/modules/{module}.md"]),
    (r"(^|/)mcp/[^/]+\.kt$", ["docs/api/mcp.md"]),
    (r"(^|/)rest/[^/]+\.kt$", ["docs/api/rest.md"]),
    (r"(^|/)src/main/.*\.kts?$", ["docs/modules/{module}.md"]),
    (r"(^|/)(docker-)?compose[^/]*\.ya?ml$|(^|/)Dockerfile$|(^|/)\.env\.example$"
     r"|(^|/)src/main/resources/application[^/]*$", ["docs/operations.md"]),
    (r"^settings\.gradle\.kts$|^gradle/libs\.versions\.toml$|^build-logic/", ["docs/architecture.md"]),
]
COMPILED_RULES = [(re.compile(p), docs) for p, docs in RULES]

# «Модули» без собственного docs/modules/<модуль>.md: корень проекта и сборка (описана в architecture.md).
NO_MODULE_DOCS = {"root", "build-logic"}

STATE_FILE = "claude-doc-guard.json"


def module_of(path):
    """core/reminders/src/main/... -> core-reminders; src/main/... -> root."""
    idx = path.find("src/")
    if idx <= 0:
        return "root"
    return path[:idx].strip("/").replace("/", "-") or "root"


def classify(paths):
    """Сопоставляет изменённые файлы документам, которые стоит сверить."""
    result = {}
    for path in paths:
        if IGNORED.search(path):
            continue
        docs = set()
        module = module_of(path)
        for pattern, targets in COMPILED_RULES:
            if pattern.search(path):
                docs.update(t.format(module=module) for t in targets
                            if not ("{module}" in t and module in NO_MODULE_DOCS))
        if docs:
            result[path] = docs
    return result


def git(project, *args):
    return subprocess.run(["git", "-C", project] + list(args), capture_output=True,
                          text=True, check=True).stdout


def changed_paths(project):
    """Изменённые, добавленные, удалённые и неотслеживаемые файлы рабочей копии."""
    out = git(project, "status", "--porcelain=v1", "-z", "--untracked-files=all")
    entries = out.split("\0")
    paths, i = [], 0
    while i < len(entries):
        entry = entries[i]
        i += 1
        if len(entry) < 4:
            continue
        paths.append(entry[3:])
        if entry[0] in "RC":
            i += 1  # у переименования следом идёт старый путь
    return paths


def fingerprint(project, paths):
    digest = hashlib.sha256()
    for path in sorted(paths):
        full = os.path.join(project, path)
        digest.update(path.encode())
        if os.path.isfile(full):
            with open(full, "rb") as f:
                digest.update(hashlib.sha256(f.read()).digest())
        else:
            digest.update(b"<deleted>")
    return digest.hexdigest()


def state_path(project):
    git_dir = git(project, "rev-parse", "--absolute-git-dir").strip()
    return os.path.join(git_dir, STATE_FILE)


def load_state(path):
    try:
        with open(path, encoding="utf-8") as f:
            return json.load(f)
    except (OSError, ValueError):
        return {}


def save_state(path, fp):
    with open(path, "w", encoding="utf-8") as f:
        json.dump({"fingerprint": fp}, f)


def decide(project, stop_hook_active):
    """Возвращает текст для агента, если ход нужно продолжить, иначе None."""
    paths = changed_paths(project)
    relevant = classify(paths)
    if not relevant:
        return None
    store = state_path(project)
    fp = fingerprint(project, relevant)
    if load_state(store).get("fingerprint") == fp:
        return None  # этот набор изменений уже сверяли
    save_state(store, fp)
    if stop_hook_active:
        return None  # агент уже продолжает ход по Stop-хуку — не зацикливаемся
    needed = set().union(*relevant.values())
    if needed <= set(paths):
        return None  # все предложенные документы уже правились
    lines = ["- %s → %s" % (p, ", ".join(sorted(d))) for p, d in sorted(relevant.items())]
    return (
        "Проверка документации (doc_guard): изменены файлы, влияющие на документацию.\n"
        + "\n".join(lines)
        + "\n\nПеред завершением:\n"
        "1. Вызови субагента docs-writer: передай ему список файлов выше и кратко — что "
        "изменено и зачем. Он сверит и обновит описательную документацию.\n"
        "2. Если принято архитектурное решение — сам добавь запись в docs/DECISIONS.md "
        "(причину знаешь только ты).\n"
        "3. Если документация уже актуальна или изменения её не затрагивают — коротко "
        "скажи об этом владельцу, ничего не меняя.\n"
        "Хук срабатывает один раз на набор изменений."
    )


def main():
    try:
        data = json.load(sys.stdin)
        project = os.environ.get("CLAUDE_PROJECT_DIR") or data.get("cwd") or os.getcwd()
        reason = decide(project, bool(data.get("stop_hook_active")))
    except Exception as e:  # fail-open: не мешаем завершить ход
        print("doc_guard: %r" % e, file=sys.stderr)
        return 0
    if reason:
        print(json.dumps({"decision": "block", "reason": reason}, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    sys.exit(main())
