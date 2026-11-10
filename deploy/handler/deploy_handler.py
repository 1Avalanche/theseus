#!/usr/bin/python3
"""Ограниченный root-обработчик деплоя; установка — docs/deploy-setup.md."""

import contextlib
import datetime
import fcntl
import hashlib
import io
import json
import os
from pathlib import Path
import re
import signal
import stat
import subprocess
import sys
import tempfile
import time
import urllib.request
import urllib.error
import urllib.parse
import uuid
import zipfile

ROOT = Path('/opt/theseus')
DOCKER = '/usr/bin/docker'
SHA = re.compile(r'[0-9a-f]{40}')
DIGEST = re.compile(r'sha256:[0-9a-f]{64}')
VERSION = re.compile(r'[A-Za-z0-9][A-Za-z0-9._-]{0,63}')
MAX_REQUEST = 1024
MAX_ARTIFACT = 65536


class DeployError(Exception):
    """Безопасная для вывода клиенту причина отказа."""


def require(condition, message):
    if not condition:
        raise DeployError(message)


def unique_fields(pairs):
    """Дубли полей JSON не должны менять смысл запроса или разрешения."""
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError('Повтор поля JSON')
        result[key] = value
    return result


def trusted(path, secret=False):
    """Все компоненты пути — root-owned, без симлинков и чужого права записи."""
    path = Path(path)
    require(path.is_absolute(), 'Ожидался абсолютный путь')
    for part in reversed((path,) + tuple(path.parents)):
        info = part.lstat()
        require(not stat.S_ISLNK(info.st_mode), 'Симлинк в защищённом пути')
        require(info.st_uid == 0 and not info.st_mode & 0o022,
                'Небезопасный владелец или права защищённого пути')
        require(stat.S_ISREG(info.st_mode) or stat.S_ISDIR(info.st_mode),
                'Недопустимый тип файла')
    if secret:
        require(not path.stat().st_mode & 0o077, 'Секрет доступен другим пользователям')
    return path


def read_json(path):
    with trusted(path).open() as stream:
        return json.load(stream, object_pairs_hook=unique_fields)


def parse_request(raw, stand):
    require(stand in ('test', 'prod'), 'Неизвестный стенд')
    require(len(raw) <= MAX_REQUEST, 'Запрос слишком большой')
    try:
        request = json.loads(raw, object_pairs_hook=unique_fields)
    except (ValueError, UnicodeError):
        raise DeployError('Ожидался JSON-запрос') from None
    require(isinstance(request, dict), 'Ожидался объект запроса')
    if request == {'action': 'stop'}:
        require(stand == 'test', 'Выключение prod запрещено')
        return request
    require(set(request) == {'action', 'release'} and request['action'] == 'deploy',
            'Недопустимые поля или операция')
    require(isinstance(request['release'], str) and SHA.fullmatch(request['release']),
            'Релиз должен быть полным SHA коммита')
    return request


def parse_authorization(raw, stand):
    require(stand in ('test', 'prod'), 'Неизвестный стенд')
    require(len(raw) <= MAX_REQUEST, 'Запрос слишком большой')
    try:
        request = json.loads(raw, object_pairs_hook=unique_fields)
    except (ValueError, UnicodeError):
        raise DeployError('Ожидался JSON-запрос') from None
    require(isinstance(request, dict) and set(request) == {
        'action', 'build_run_id', 'artifact_id', 'artifact_digest', 'release'},
        'Недопустимые поля разрешения')
    require(request['action'] == 'authorize', 'Недопустимая операция')
    for key in ('build_run_id', 'artifact_id'):
        require(type(request[key]) is int and 0 < request[key] < 10 ** 20,
                'Некорректный идентификатор GitHub')
    require(isinstance(request['artifact_digest'], str) and
            DIGEST.fullmatch(request['artifact_digest']), 'Некорректный digest артефакта')
    require(isinstance(request['release'], str) and SHA.fullmatch(request['release']),
            'Релиз должен быть полным SHA коммита')
    return request


def validate_authorization_config(config):
    require(isinstance(config, dict) and set(config) == {'repository_id', 'owner_id'},
            'Некорректный authorization.json')
    require(all(type(value) is int and value > 0 for value in config.values()),
            'Ожидались числовые ID репозитория и владельца')
    return config


def validate_config(config):
    require(isinstance(config, dict) and set(config) == {
        'repository', 'registry', 'config_version'}, 'Некорректный config.json')
    repository = config['repository']
    require(isinstance(repository, str) and re.fullmatch(
        r'[A-Za-z0-9][A-Za-z0-9-]{0,38}/[A-Za-z0-9_][A-Za-z0-9._-]{0,99}', repository),
        'Некорректный репозиторий')
    require(config['registry'] == 'ghcr.io/' + repository.split('/')[0].lower(),
            'Разрешён только фиксированный GHCR владельца репозитория')
    require(isinstance(config['config_version'], str) and
            VERSION.fullmatch(config['config_version']), 'Некорректная версия конфигурации')
    return config


def validate_release(release, sha, config):
    require(isinstance(release, dict) and set(release) == {
        'commit', 'config_version', 'core_digest', 'agent_digest'},
        'Некорректная запись разрешённого релиза')
    require(release['commit'] == sha, 'SHA разрешения не совпадает с запросом')
    require(release['config_version'] == config['config_version'],
            'Релиз требует другой версии серверной конфигурации')
    for module in ('core', 'agent'):
        require(isinstance(release[module + '_digest'], str) and
                DIGEST.fullmatch(release[module + '_digest']), 'Некорректный digest образа')
    return release


def github_json(url, token):
    request = urllib.request.Request(url, headers={
        'Accept': 'application/vnd.github+json',
        'Authorization': 'Bearer ' + token,
        'User-Agent': 'theseus-deploy',
        'X-GitHub-Api-Version': '2022-11-28',
    })
    # Не передавать Authorization на redirect.
    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, req, fp, code, msg, headers, newurl):
            return None
    with urllib.request.build_opener(NoRedirect).open(request, timeout=20) as response:
        data = response.read(4 * 1024 * 1024 + 1)
    require(len(data) <= 4 * 1024 * 1024, 'Ответ GitHub слишком большой')
    return json.loads(data)


def verify_main(config, sha, token):
    try:
        result = github_json('https://api.github.com/repos/' + config['repository'] +
                             '/compare/' + sha + '...main?per_page=1', token)
        allowed = (result['status'] in ('ahead', 'identical') and
                   result['merge_base_commit']['sha'] == sha)
    except Exception:
        raise DeployError('Не удалось проверить принадлежность коммита main') from None
    require(allowed, 'Коммит prod не входит в main')


def read_github_token(root):
    content = trusted(root / 'github.env', secret=True).read_text().strip()
    require(re.fullmatch(r'GITHUB_TOKEN=[A-Za-z0-9_]+', content),
            'Ожидалась одна строка GITHUB_TOKEN в github.env')
    return content.split('=', 1)[1]


def github_archive(url, token):
    """Скачивает ZIP по одноразовой ссылке GitHub, не передавая ей токен."""
    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, req, fp, code, msg, headers, newurl):
            return None
    opener = urllib.request.build_opener(NoRedirect)
    request = urllib.request.Request(url, headers={
        'Authorization': 'Bearer ' + token, 'User-Agent': 'theseus-authorize',
        'X-GitHub-Api-Version': '2022-11-28',
    })
    try:
        opener.open(request, timeout=20).close()
    except urllib.error.HTTPError as error:
        require(error.code == 302, 'GitHub не выдал ссылку артефакта')
        location = error.headers.get('Location', '')
        error.close()
    else:
        raise DeployError('Ожидалась ссылка артефакта GitHub')
    parsed = urllib.parse.urlsplit(location)
    require(parsed.scheme == 'https' and parsed.port in (None, 443) and
            not parsed.username and not parsed.password and not parsed.fragment and
            parsed.hostname is not None and any(parsed.hostname.endswith(suffix) for suffix in
                ('.blob.core.windows.net', '.actions.githubusercontent.com')),
            'Недопустимый адрес хранилища артефакта')
    with opener.open(urllib.request.Request(location), timeout=20) as response:
        data = response.read(MAX_ARTIFACT + 1)
    require(len(data) <= MAX_ARTIFACT, 'Артефакт слишком большой')
    return data


def validate_build(run, run_id, identity):
    """Допускает только успешную ручную сборку владельца из исходного репозитория."""
    require(run['id'] == run_id and run['event'] == 'workflow_dispatch' and
            run['status'] == 'completed' and run['conclusion'] == 'success' and
            run['path'] == '.github/workflows/release.yml' and run['head_branch'] == 'main' and
            run['repository']['id'] == identity['repository_id'] and
            run['head_repository']['id'] == identity['repository_id'] and
            run['actor']['id'] == identity['owner_id'] and
            run['triggering_actor']['id'] == identity['owner_id'] and
            isinstance(run['head_sha'], str) and SHA.fullmatch(run['head_sha']),
            'Сборка не соответствует доверенному происхождению')


def load_candidate(config, identity, run_id, token, artifact_id=None, expected_digest=None):
    """Проверяет происхождение и digest ZIP; содержимое никогда не исполняется."""
    base = 'https://api.github.com/repos/' + config['repository'] + '/actions/'
    run = github_json(base + 'runs/' + str(run_id), token)
    validate_build(run, run_id, identity)
    if artifact_id is None:
        listing = github_json(base + 'runs/' + str(run_id) + '/artifacts?per_page=100', token)
        require(listing['total_count'] <= 100, 'Слишком много артефактов сборки')
        matches = [item for item in listing['artifacts'] if item['name'] == 'release-candidate']
        require(len(matches) == 1, 'Нужен один артефакт release-candidate')
        artifact_id = matches[0]['id']
    require(type(artifact_id) is int and artifact_id > 0, 'Некорректный ID артефакта')
    artifact = github_json(base + 'artifacts/' + str(artifact_id), token)
    origin = artifact['workflow_run']
    require(artifact['id'] == artifact_id and artifact['name'] == 'release-candidate' and
            artifact['expired'] is False and 0 < artifact['size_in_bytes'] <= MAX_ARTIFACT and
            origin['id'] == run_id and origin['repository_id'] == identity['repository_id'] and
            origin['head_repository_id'] == identity['repository_id'] and
            origin['head_branch'] == 'main' and origin['head_sha'] == run['head_sha'],
            'Артефакт не принадлежит доверенной сборке')
    digest = artifact['digest']
    require(isinstance(digest, str) and DIGEST.fullmatch(digest) and
            (expected_digest is None or digest == expected_digest),
            'Digest артефакта изменился или отсутствует')
    data = github_archive(base + 'artifacts/' + str(artifact_id) + '/zip', token)
    require('sha256:' + hashlib.sha256(data).hexdigest() == digest,
            'Содержимое артефакта не соответствует digest')
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        entries = archive.infolist()
        require(len(entries) == 1 and entries[0].filename == 'release.json' and
                0 < entries[0].file_size <= 4096 and not entries[0].flag_bits & 1,
                'Ожидался только небольшой release.json')
        release = json.loads(archive.read(entries[0]), object_pairs_hook=unique_fields)
    require(isinstance(release, dict) and isinstance(release.get('commit'), str) and
            SHA.fullmatch(release['commit']), 'Некорректный SHA кандидата')
    validate_release(release, release['commit'], config)
    # Повторная сборка не должна менять происхождение во время скачивания.
    current = github_json(base + 'runs/' + str(run_id), token)
    validate_build(current, run_id, identity)
    require(current['run_attempt'] == run['run_attempt'] and current['head_sha'] == run['head_sha'],
            'Сборка изменилась во время проверки')
    request = {'action': 'authorize', 'build_run_id': run_id, 'artifact_id': artifact_id,
               'artifact_digest': digest, 'release': release['commit']}
    return release, request


def authorize(root, stand, request):
    """Вызывается под общей блокировкой; не запускает Docker и не меняет конфигурацию."""
    config = validate_config(read_json(root / 'config.json'))
    identity = validate_authorization_config(read_json(root / 'authorization.json'))
    token = read_github_token(root)
    try:
        release, _ = load_candidate(config, identity, request['build_run_id'], token,
                                    request['artifact_id'], request['artifact_digest'])
    except Exception:
        raise DeployError('Не удалось подтвердить доверенный артефакт GitHub') from None
    require(release['commit'] == request['release'], 'SHA кандидата изменился после approval')
    if stand == 'prod':
        verify_main(config, release['commit'], token)
    path = root / 'releases' / stand / (release['commit'] + '.json')
    if path.exists() or path.is_symlink():
        require(read_json(path) == release, 'Этот SHA уже разрешён с другими образами')
    else:
        atomic_write(path, json.dumps(release, sort_keys=True) + '\n')
    print(stand + ': разрешён релиз ' + release['commit'] +
          ', сборка ' + str(request['build_run_id']) +
          ', артефакт ' + str(request['artifact_id']), flush=True)


def atomic_write(path, content):
    """Замена состояния без частично записанных файлов."""
    trusted(path.parent)
    if path.exists() or path.is_symlink():
        trusted(path, secret=True)
    fd, temporary = tempfile.mkstemp(prefix='.deploy-', dir=path.parent)
    try:
        with os.fdopen(fd, 'w') as stream:
            stream.write(content)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
        directory = os.open(path.parent, os.O_RDONLY)
        try:
            os.fsync(directory)
        finally:
            os.close(directory)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


@contextlib.contextmanager
def deployment_lock(root):
    trusted(root)
    path = root / 'deploy.lock'
    fd = os.open(path, os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW, 0o600)
    try:
        trusted(path, secret=True)
        try:
            fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            raise DeployError('Другая операция деплоя уже выполняется') from None
        yield
    finally:
        os.close(fd)


class Deployer:
    def __init__(self, root, stand):
        self.root = root
        self.stand = stand
        self.directory = root / stand
        # Ни Docker/Compose env, ни PATH из SSH не наследуются.
        self.env = {'PATH': '/usr/sbin:/usr/bin:/sbin:/bin', 'HOME': '/root',
                    'LANG': 'C.UTF-8', 'DOCKER_CONFIG': str(root / 'docker')}

    def run(self, args, timeout=120, output=None):
        try:
            result = subprocess.run([DOCKER] + args, cwd=self.root, env=self.env,
                                    stdin=subprocess.DEVNULL, stdout=output or subprocess.PIPE,
                                    stderr=subprocess.PIPE, timeout=timeout, check=False)
        except (OSError, subprocess.TimeoutExpired):
            raise DeployError('Команда Docker недоступна или превысила срок ожидания') from None
        require(result.returncode == 0, 'Команда Docker завершилась ошибкой; диагностика — администратором на VPS')
        return (result.stdout or b'').decode('utf-8', errors='replace').strip()

    def compose(self, *args):
        return self.run(['compose', '--project-name', 'theseus-' + self.stand,
                         '--project-directory', str(self.root), '-f', str(self.root / 'docker-compose.yml'),
                         '--env-file', str(self.directory / 'stand.env'),
                         '--env-file', str(self.directory / 'image.env'), *args], timeout=300)

    def save_state(self, status, sha=None):
        state = {'status': status, 'release': sha,
                 'at': datetime.datetime.now(datetime.timezone.utc).isoformat()}
        atomic_write(self.directory / 'state.json', json.dumps(state) + '\n')

    def check_layout(self):
        trusted(self.directory, secret=True)
        trusted(self.root / 'docker-compose.yml')
        trusted(self.directory / 'stand.env', secret=True)
        trusted(self.root / 'docker', secret=True)
        trusted(self.root / 'docker' / 'config.json', secret=True)

    def stop(self):
        require(self.stand == 'test', 'Выключение prod запрещено')
        self.check_layout()
        trusted(self.directory / 'image.env', secret=True)
        # Без -v: тома и общий PostgreSQL не затрагиваются.
        self.compose('down', '--timeout', '30')
        self.save_state('stopped')
        print('test: остановлен; БД сохранена', flush=True)

    def backup(self, sha):
        directory = trusted(self.root / 'backups' / self.stand, secret=True)
        stamp = datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ')
        path = directory / ('pre-deploy-' + stamp + '-' + sha[:12] + '-' + uuid.uuid4().hex + '.dump')
        try:
            with path.open('xb') as stream:
                os.chmod(path, 0o600)
                self.run(['exec', 'theseus-postgres', 'pg_dump', '-U', 'postgres', '-Fc',
                          'theseus_' + self.stand], timeout=300, output=stream)
                stream.flush()
                os.fsync(stream.fileno())
                require(path.stat().st_size > 0, 'Получен пустой дамп')
        except Exception:
            path.unlink(missing_ok=True)
            raise
        dumps = sorted(directory.glob('pre-deploy-*.dump'), key=lambda p: p.name, reverse=True)
        for old in dumps[10:]:
            trusted(old, secret=True).unlink()
        print(self.stand + ': дамп сохранён', flush=True)

    def wait_healthy(self):
        deadline = time.monotonic() + 240
        for service in ('core', 'agent'):
            while True:
                health = self.run(['inspect', '--format', '{{.State.Health.Status}}',
                                   'theseus-' + self.stand + '-' + service], timeout=15)
                if health == 'healthy':
                    break
                require(time.monotonic() < deadline, 'Контейнер не стал healthy; автоматического восстановления БД нет')
                time.sleep(3)

    def deploy(self, sha):
        self.check_layout()
        config = validate_config(read_json(self.root / 'config.json'))
        release = validate_release(read_json(self.root / 'releases' / self.stand / (sha + '.json')),
                                   sha, config)
        if self.stand == 'prod':
            verify_main(config, sha, read_github_token(self.root))
        # Последний env-файл фиксирует стенд и образы поверх stand.env.
        image_env = 'THESEUS_STAND=' + self.stand + '\n'
        for module in ('core', 'agent'):
            image_env += ('THESEUS_' + module.upper() + '_IMAGE=' + config['registry'] +
                          '/theseus-' + module + '@' + release[module + '_digest'] + '\n')
        require(self.run(['inspect', '--format', '{{.State.Health.Status}}',
                          'theseus-postgres']) == 'healthy', 'PostgreSQL не healthy')
        self.backup(sha)
        current = self.directory / 'image.env'
        if current.exists():
            atomic_write(self.directory / 'image.env.prev', trusted(current, secret=True).read_text())
        atomic_write(current, image_env)
        self.save_state('deploying', sha)
        try:
            self.compose('pull', 'core', 'agent')
            self.compose('up', '-d', '--remove-orphans', '--pull', 'never', 'core', 'agent')
            self.wait_healthy()
        except Exception:
            self.save_state('failed', sha)
            raise
        self.save_state('healthy', sha)
        print(self.stand + ': релиз ' + sha + ' healthy', flush=True)


def main():
    try:
        require(os.geteuid() == 0, 'Обработчик запускается только через разрешённый sudo')
        authorizing = len(sys.argv) == 3 and sys.argv[1] == 'authorize'
        require((len(sys.argv) == 2 or authorizing) and sys.argv[-1] in ('test', 'prod'),
                'Ожидался фиксированный стенд')
        stand = sys.argv[-1]
        os.umask(0o077)
        def expired(signum, frame):
            raise DeployError('Истёк срок чтения запроса')
        signal.signal(signal.SIGALRM, expired)
        signal.alarm(10)
        try:
            parser = parse_authorization if authorizing else parse_request
            request = parser(sys.stdin.buffer.read(MAX_REQUEST + 1), stand)
        finally:
            signal.alarm(0)
        with deployment_lock(ROOT):
            deployer = Deployer(ROOT, stand)
            if authorizing:
                authorize(ROOT, stand, request)
            elif request['action'] == 'stop':
                deployer.stop()
            else:
                deployer.deploy(request['release'])
    except DeployError as error:
        print('Отказ: ' + str(error), file=sys.stderr)
        return 1
    except Exception:
        # Исключения библиотек могут содержать секреты и конфигурацию.
        print('Отказ: ошибка защищённой конфигурации или инфраструктуры', file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
