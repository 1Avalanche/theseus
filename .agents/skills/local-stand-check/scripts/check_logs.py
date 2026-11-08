#!/usr/bin/env python3
"""Проверяет отсутствие контрольного текста в логах, не выводя сами логи."""
import argparse
import subprocess
import sys


def check_logs(text, containers, runner=subprocess.run):
    """Возвращает ошибки чтения и найденные утечки; ошибка Docker не считается успехом."""
    if not text:
        raise ValueError("Контрольный текст не должен быть пустым")
    errors = []
    for container in containers:
        try:
            result = runner(["docker", "logs", container], capture_output=True, text=True,
                            check=False, timeout=15)
        except (OSError, subprocess.TimeoutExpired):
            errors.append("Не удалось прочитать логи %s." % container)
            continue
        if result.returncode != 0:
            errors.append("Не удалось прочитать логи %s (код %s)." % (container, result.returncode))
        elif text in result.stdout or text in result.stderr:
            errors.append("В логах %s найден контрольный текст." % container)
    return errors


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("text", help="Уникальный контрольный текст тестового напоминания")
    parser.add_argument("containers", nargs="+", help="Имена проверяемых контейнеров")
    args = parser.parse_args(argv)
    if not args.text:
        parser.error("Контрольный текст не должен быть пустым")
    errors = check_logs(args.text, args.containers)
    if errors:
        print("\n".join(errors), file=sys.stderr)
        return 1
    print("Контрольный текст отсутствует в логах всех проверенных контейнеров.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
