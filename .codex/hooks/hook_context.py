"""Определение корня проекта по cwd события Codex, включая запуск из подкаталога."""
import os
import subprocess


def project_root(data):
    """Возвращает корень Git; вне Git — корень репозитория со скриптами хуков."""
    cwd = data.get("cwd") or os.getcwd()
    result = subprocess.run(["git", "-C", cwd, "rev-parse", "--show-toplevel"],
                            capture_output=True, text=True, check=False)
    if result.returncode == 0:
        return os.path.realpath(result.stdout.strip())
    return os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
