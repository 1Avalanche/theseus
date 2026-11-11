#!/usr/bin/env python3
"""Минимальный монитор Theseus: фиксированные Docker-команды, локальные сигналы и внешний пульс."""

import fcntl
import json
import math
import os
from pathlib import Path
import shutil
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request

WINDOW = 600
BACKUP_WINDOW = 26 * 3600
MAX_RESTARTS = 2
MAX_SNAPSHOT = 65536
MESSAGES = json.loads(Path(__file__).with_name('messages.json').read_text())


def run_docker(*args):
    """Не выводит stdout/stderr Docker: там могут быть переменные контейнера и тексты библиотек."""
    result = subprocess.run(['/usr/bin/docker', *args], capture_output=True, timeout=30, check=True)
    if len(result.stdout) > MAX_SNAPSHOT:
        raise ValueError('Слишком большой ответ Docker')
    return result.stdout.decode()


def read_json(path, default=None):
    if not path.exists():
        return default
    if path.stat().st_size > MAX_SNAPSHOT:
        raise ValueError('Слишком большой JSON')
    return json.loads(path.read_text())


def atomic_json(path, value):
    temporary = path.with_suffix('.tmp')
    with open(temporary, 'w', opener=lambda name, flags: os.open(name, flags, 0o600)) as output:
        json.dump(value, output, ensure_ascii=False)
        output.flush()
        os.fsync(output.fileno())
    temporary.replace(path)


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def post(url, body, content_type='text/plain; charset=utf-8'):
    """Секретные URL и тело ошибки сети не попадают в журнал; редиректы запрещены."""
    parsed = urllib.parse.urlsplit(url)
    if parsed.scheme != 'https' or not parsed.hostname or parsed.username or parsed.password:
        raise ValueError('Ожидается HTTPS URL без логина и пароля')
    request = urllib.request.Request(url, data=body.encode(), headers={'Content-Type': content_type}, method='POST')
    with urllib.request.build_opener(NoRedirect).open(request, timeout=15) as response:
        if not 200 <= response.status < 300:
            raise ValueError('Внешний сервис отклонил запрос')
        return response.read(MAX_SNAPSHOT)


def notify(message, env):
    token = env.get('THESEUS_MONITOR_BOT_TOKEN')
    chat = env.get('THESEUS_MONITOR_CHAT_ID')
    if bool(token) != bool(chat):
        raise ValueError('Нужны обе настройки канала Telegram')
    if token:
        body = urllib.parse.urlencode({'chat_id': chat, 'text': message})
        reply = post(f'https://api.telegram.org/bot{token}/sendMessage', body, 'application/x-www-form-urlencoded')
        if json.loads(reply).get('ok') is not True:
            raise ValueError('Telegram не подтвердил уведомление')


def timestamp(value):
    if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value) or value < 0:
        raise ValueError('Неверное время в диагностике')
    return value


def dependency_failed(signal, now):
    since = signal.get('failing_since')
    if since is None:
        return False
    since = timestamp(since)
    last = timestamp(signal['last_error_at'])
    count = signal['consecutive_errors']
    persistent_code = signal.get('last_code') in ('401', '403')
    return now - since >= WINDOW and (persistent_code or (count >= 2 and now - last <= WINDOW))


def validate_dependency(signal, now):
    if not isinstance(signal, dict) or type(signal.get('consecutive_errors')) is not int:
        raise ValueError('Неверный счётчик ошибок')
    if signal['consecutive_errors'] < 0 or not isinstance(signal.get('errors'), dict):
        raise ValueError('Неверные счётчики ошибок')
    for code, count in signal['errors'].items():
        if not isinstance(code, str) or len(code) > 32 or type(count) is not int or count < 0:
            raise ValueError('Неверный код или счётчик ошибок')
    since = signal['failing_since']
    last = signal['last_error_at']
    code = signal['last_code']
    if code is not None and (not isinstance(code, str) or len(code) > 32):
        raise ValueError('Неверный код ошибки')
    if last is not None and timestamp(last) > now + 60:
        raise ValueError('Ошибка из будущего')
    if since is not None:
        if last is None or code is None or timestamp(since) > timestamp(last) or signal['consecutive_errors'] == 0:
            raise ValueError('Несогласованное состояние зависимости')


def evaluate(snapshot, module, now):
    """Различает отсутствие внутреннего прогресса и отказ зависимости; возвращает причину рестарта."""
    if type(snapshot.get('version')) is not int or snapshot['version'] != 1 or type(snapshot.get('task_active')) is not bool:
        raise ValueError('Неверный формат диагностики')
    observed = timestamp(snapshot['observed_at'])
    started = timestamp(snapshot['started_at'])
    if observed > now + 60 or started > now + 60 or now - observed >= WINDOW:
        return ['snapshot'], False
    for key in (('telegram',) if module == 'core' else ('polling', 'telegram', 'openrouter')):
        validate_dependency(snapshot[key], now)
    pulse = snapshot['last_success_at']
    pulse = timestamp(pulse) if pulse is not None else started
    if pulse > now + 60:
        raise ValueError('Пульс из будущего')
    stalled = now - pulse >= WINDOW
    issues = []
    if stalled or (not snapshot['task_active'] and now - started >= WINDOW):
        issues.append('scheduler' if module == 'core' else 'polling')
    for key in ('telegram', 'openrouter'):
        if key in snapshot and dependency_failed(snapshot[key], now):
            issues.append(key)
    if module == 'core':
        if type(snapshot.get('database_ok')) is not bool:
            raise ValueError('Нет состояния БД')
        if not snapshot['database_ok']:
            issues.append('database')
        oldest = snapshot['oldest_due_at']
        count = snapshot['overdue_count']
        if snapshot['database_ok'] and (type(count) is not int or count < 0 or (count == 0) != (oldest is None)):
            raise ValueError('Несогласованный агрегат очереди')
        if oldest is not None and now - timestamp(oldest) >= WINDOW:
            issues.append('overdue')
        external = not snapshot['database_ok'] or snapshot['telegram'].get('failing_since') is not None
    else:
        external = snapshot['polling'].get('failing_since') is not None
    return issues, stalled and not external


def inspect_service(stand, module, now, docker=run_docker):
    name = f'theseus-{stand}-{module}'
    try:
        state = json.loads(docker('inspect', '--format', '{{json .State}}', name))
        if not state.get('Running'):
            return ['container'], False, None
        snapshot = json.loads(docker('exec', name, 'cat', f'/tmp/theseus-{module}.monitor.json'))
        issues, restart = evaluate(snapshot, module, now)
        if state.get('Health', {}).get('Status') == 'unhealthy':
            issues.append('healthcheck')
        return issues, restart, snapshot.get('last_success_at')
    except (OSError, subprocess.SubprocessError, ValueError, KeyError, TypeError, AttributeError):
        return ['snapshot'], False, None


def message(scope, issues):
    labels = '; '.join(MESSAGES['signals'][issue] for issue in issues)
    return MESSAGES['problem'].format(scope=scope, signals=labels)


def check(root, state, now, docker=run_docker, disk_usage=shutil.disk_usage, env=None):
    env = os.environ if env is None else env
    problems = {}
    restarts = state.setdefault('restarts', {})
    for stand in ('prod', 'test'):
        try:
            stand_state = read_json(root / stand / 'state.json')
            if stand_state is not None and (not isinstance(stand_state, dict) or
                                           stand_state.get('status') not in ('healthy', 'failed', 'deploying', 'stopped')):
                raise ValueError('Неизвестное состояние стенда')
            if stand == 'test' and (stand_state is None or stand_state['status'] == 'stopped'):
                continue
        except (OSError, ValueError, AttributeError):
            problems[stand] = ['stand_state']
            continue
        for module in ('core', 'agent'):
            scope = f'{stand}/{module}'
            issues, restart, last_success = inspect_service(stand, module, now, docker)
            budget = restarts.setdefault(scope, {'attempts': 0, 'last_at': 0})
            if not issues:
                # Сбрасываем лимит только после отдельной здоровой проверки через 10 минут.
                if last_success is not None and last_success >= budget['last_at'] and now - budget['last_at'] >= WINDOW:
                    budget['attempts'] = 0
            if restart and now - budget['last_at'] >= WINDOW:
                if budget['attempts'] >= MAX_RESTARTS:
                    issues.append('restart_limit')
                else:
                    budget['attempts'] += 1
                    budget['last_at'] = now
                    # Резервируем попытку до команды: прерывание монитора не сбрасывает лимит.
                    atomic_json(root / 'monitor-state.json', state)
                    print(MESSAGES['restart'].format(scope=scope, attempt=budget['attempts']), flush=True)
                    try:
                        docker('restart', '--time', '20', f'theseus-{stand}-{module}')
                    except (OSError, subprocess.SubprocessError, ValueError):
                        issues.append('restart_failed')
            if issues:
                problems[scope] = issues
    for path in env.get('THESEUS_MONITOR_DISK_PATHS', '/,/opt/theseus,/var/lib/docker').split(','):
        try:
            usage = disk_usage(path)
            if usage.used / usage.total >= 0.8:
                problems[f'disk:{path}'] = ['disk']
        except (OSError, ZeroDivisionError):
            problems[f'disk:{path}'] = ['disk_check']
    backup = env.get('THESEUS_MONITOR_BACKUP_SUCCESS_FILE')
    if backup:
        try:
            completed = timestamp(float(Path(backup).read_text().strip()))
            if completed > now + 60 or now - completed >= BACKUP_WINDOW:
                problems['backup'] = ['backup']
        except (OSError, ValueError):
            problems['backup'] = ['backup']
    return problems


def report(root, state, problems, env, sender=notify, ping=post):
    previous = state.get('notified', {})
    delivered = dict(previous)
    failed = False
    for scope in sorted(set(previous) | set(problems)):
        current = problems.get(scope)
        if current == previous.get(scope):
            continue
        text = message(scope, current) if current else MESSAGES['recovery'].format(scope=scope)
        print(text, flush=True)
        try:
            sender(text, env)
            if current:
                delivered[scope] = current
            else:
                delivered.pop(scope, None)
        except (OSError, ValueError, urllib.error.URLError):
            print('Не доставлено оповещение монитора; повтор при следующей проверке', flush=True)
            failed = True
    state['notified'] = delivered
    state['problems'] = problems
    atomic_json(root / 'monitor-state.json', state)
    # У внешнего сервиса отдельные URL успеха и отказа; отсутствие запроса означает потерю пульса.
    key = 'THESEUS_MONITOR_FAILURE_URL' if problems or failed else 'THESEUS_MONITOR_HEARTBEAT_URL'
    body = '\n'.join(message(scope, issues) for scope, issues in sorted(problems.items()))
    ping(env[key], body)


def main():
    root = Path('/opt/theseus')
    # Общий lock с Deploy: нет ложных тревог и рестартов посреди обновления или test/stop.
    with (root / 'deploy.lock').open('r+') as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            print('Идёт обслуживание стенда; проверка отложена', flush=True)
            return
        for key in ('THESEUS_MONITOR_HEARTBEAT_URL', 'THESEUS_MONITOR_FAILURE_URL'):
            if not os.environ.get(key):
                raise ValueError('Не настроен внешний heartbeat')
        state = read_json(root / 'monitor-state.json', {'notified': {}, 'restarts': {}})
        validate_state(state, time.time())
        report(root, state, check(root, state, time.time()), os.environ)


def validate_state(state, now):
    if not isinstance(state, dict) or not isinstance(state.get('notified'), dict) or not isinstance(state.get('restarts'), dict):
        raise ValueError('Повреждено состояние монитора')
    for budget in state['restarts'].values():
        if not isinstance(budget, dict) or type(budget.get('attempts')) is not int or not 0 <= budget['attempts'] <= MAX_RESTARTS:
            raise ValueError('Повреждён бюджет рестартов')
        if timestamp(budget['last_at']) > now + 60:
            raise ValueError('Некорректное время рестарта')


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, KeyError, TypeError, urllib.error.URLError):
        # Строка исключения может содержать секретный URL; traceback не печатаем.
        print('Монитор завершился с ошибкой; внешний пульс не отправлен', flush=True)
        raise SystemExit(1)
