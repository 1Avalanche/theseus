"""Реальный SSH/sudo в одноразовом Ubuntu-контейнере без Docker socket хоста."""

import json
import os
from pathlib import Path
import shutil
import subprocess
import time
import unittest


@unittest.skipUnless(os.environ.get('THESEUS_SSH_INTEGRATION') == '1',
                     'Только одноразовый Ubuntu-контейнер; см. tests/Dockerfile')
class SshBoundaryTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.source = Path('/source/deploy')
        cls.root = Path('/opt/theseus')
        def command(*args):
            subprocess.run(args, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
        command('useradd', '--create-home', '--user-group', '--shell', '/bin/bash', 'deploy')
        for path in [Path('/home/deploy'), *Path('/home/deploy').rglob('*')]:
            os.chown(path, 0, 0)
        Path('/home/deploy').chmod(0o755)
        for directory in ('', 'test', 'prod', 'docker', 'releases/test', 'releases/prod',
                          'backups/test', 'backups/prod'):
            path = cls.root / directory
            path.mkdir(parents=True, exist_ok=True)
            path.chmod(0o700)
        Path('/usr/local/lib/theseus').mkdir()
        shutil.copy(cls.source / 'handler/deploy_handler.py', '/usr/local/lib/theseus/deploy_handler.py')
        shutil.copy(cls.source / 'ssh/ssh-entry', '/usr/local/lib/theseus/ssh-entry')
        Path('/usr/local/lib/theseus/ssh-entry').chmod(0o755)
        shutil.copy(cls.source / 'ssh/ssh-authorize-entry', '/usr/local/lib/theseus/ssh-authorize-entry')
        Path('/usr/local/lib/theseus/ssh-authorize-entry').chmod(0o755)
        shutil.copy(cls.source / 'ssh/theseus-deploy.sudoers', '/etc/sudoers.d/theseus-deploy')
        Path('/etc/sudoers.d/theseus-deploy').chmod(0o440)
        command('visudo', '-cf', '/etc/sudoers.d/theseus-deploy')
        shutil.copy(cls.source / 'vps/docker-compose.yml', cls.root / 'docker-compose.yml')
        (cls.root / 'config.json').write_text(json.dumps({
            'repository': 'Owner/theseus', 'registry': 'ghcr.io/owner', 'config_version': '1'}))
        (cls.root / 'authorization.json').write_text(json.dumps({'repository_id': 10, 'owner_id': 20}))
        (cls.root / 'github.env').write_text('GITHUB_TOKEN=fake\n')
        (cls.root / 'github.env').chmod(0o600)
        for name in ('test/stand.env', 'prod/stand.env', 'docker/config.json'):
            (cls.root / name).write_text('{}')
            (cls.root / name).chmod(0o600)
        cls.sha = 'a' * 40
        release = {'commit': cls.sha, 'config_version': '1', 'core_digest': 'sha256:' + '1' * 64,
                   'agent_digest': 'sha256:' + '2' * 64}
        (cls.root / 'releases/test' / (cls.sha + '.json')).write_text(json.dumps(release))
        # Ответы GitHub заменены только в root-owned копии одноразового контейнера.
        import hashlib
        import io
        import zipfile
        archive = io.BytesIO()
        with zipfile.ZipFile(archive, 'w') as stream:
            stream.writestr('release.json', json.dumps(release))
        (cls.root / 'candidate.zip').write_bytes(archive.getvalue())
        cls.authorization = {'action': 'authorize', 'build_run_id': 30, 'artifact_id': 40,
                             'artifact_digest': 'sha256:' + hashlib.sha256(archive.getvalue()).hexdigest(),
                             'release': cls.sha}
        run = {'id': 30, 'event': 'workflow_dispatch', 'status': 'completed', 'conclusion': 'success',
               'path': '.github/workflows/release.yml', 'head_branch': 'main', 'head_sha': cls.sha,
               'repository': {'id': 10}, 'head_repository': {'id': 10}, 'actor': {'id': 20},
               'triggering_actor': {'id': 20}, 'run_attempt': 1}
        artifact = {'id': 40, 'name': 'release-candidate', 'expired': False,
                    'digest': cls.authorization['artifact_digest'], 'size_in_bytes': len(archive.getvalue()),
                    'workflow_run': {'id': 30, 'repository_id': 10, 'head_repository_id': 10,
                                     'head_branch': 'main', 'head_sha': cls.sha}}
        (cls.root / 'github-fixture.json').write_text(json.dumps({'run': run, 'artifact': artifact}))
        installed = Path('/usr/local/lib/theseus/deploy_handler.py')
        fake_api = '''
def github_json(url, token):
    fixture = json.loads((ROOT / 'github-fixture.json').read_text())
    if '/compare/' in url:
        return {'status': 'identical', 'merge_base_commit': {'sha': fixture['run']['head_sha']}}
    return fixture['artifact'] if '/artifacts/' in url else fixture['run']
def github_archive(url, token):
    return (ROOT / 'candidate.zip').read_bytes()
'''
        installed.write_text(installed.read_text().replace("if __name__ == '__main__':",
                                                           fake_api + "\nif __name__ == '__main__':"))
        # Замена только в одноразовом контейнере: проверяет точный привилегированный argv.
        Path('/usr/bin/docker').write_text('''#!/usr/bin/python3
import json, sys
with open('/opt/theseus/docker-calls.jsonl', 'a') as log:
    log.write(json.dumps(sys.argv[1:]) + '\\n')
if sys.argv[1] == 'inspect': print('healthy')
if sys.argv[1] == 'exec': print('PGDMP-fake')
''')
        Path('/usr/bin/docker').chmod(0o755)
        Path('/etc/ssh/theseus').mkdir()
        keys = []
        for stand in ('test', 'prod', 'authorize-test', 'authorize-prod'):
            command('ssh-keygen', '-q', '-t', 'ed25519', '-N', '', '-f', '/root/key-' + stand)
            public = Path('/root/key-' + stand + '.pub').read_text().strip()
            entry = ('ssh-authorize-entry ' + stand.removeprefix('authorize-')
                     if stand.startswith('authorize-') else 'ssh-entry ' + stand)
            keys.append('restrict,command="/usr/local/lib/theseus/' + entry + '" ' + public)
        Path('/etc/ssh/theseus/authorized_keys').write_text('\n'.join(keys) + '\n')
        Path('/etc/ssh/theseus/authorized_keys').chmod(0o644)
        Path('/run/sshd').mkdir(exist_ok=True)
        config = ('Port 2222\nListenAddress 127.0.0.1\nUsePAM yes\n'
                  'PasswordAuthentication no\nPermitUserEnvironment no\n'
                  'HostKey /etc/ssh/ssh_host_ed25519_key\n' +
                  (cls.source / 'ssh/90-theseus-deploy.conf').read_text())
        Path('/root/sshd-test.conf').write_text(config)
        command('/usr/sbin/sshd', '-t', '-f', '/root/sshd-test.conf')
        host = Path('/etc/ssh/ssh_host_ed25519_key.pub').read_text().strip()
        Path('/root/known_hosts').write_text('[127.0.0.1]:2222 ' + host + '\n')
        cls.server = subprocess.Popen(['/usr/sbin/sshd', '-D', '-e', '-f', '/root/sshd-test.conf'],
                                      stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        time.sleep(0.3)
        if cls.server.poll() is not None:
            raise RuntimeError('sshd не запустился')

    @classmethod
    def tearDownClass(cls):
        cls.server.terminate()
        cls.server.wait(timeout=10)

    def ssh(self, stand, request, command=None):
        args = ['ssh', '-F', '/dev/null', '-T', '-p', '2222', '-i', '/root/key-' + stand,
                '-o', 'BatchMode=yes', '-o', 'IdentitiesOnly=yes', '-o', 'StrictHostKeyChecking=yes',
                '-o', 'UserKnownHostsFile=/root/known_hosts', 'deploy@127.0.0.1']
        if command:
            args.append(command)
        raw = request if isinstance(request, bytes) else json.dumps(request).encode()
        return subprocess.run(args, input=raw, capture_output=True, timeout=20)

    def test_remote_shell_and_sftp_requests_are_rejected(self):
        for key in ('test', 'authorize-test', 'authorize-prod'):
            for command in ('id', 'sudo /bin/sh', 'internal-sftp', 'scp -t /tmp/x'):
                result = self.ssh(key, b'', command)
                self.assertNotEqual(result.returncode, 0)
                self.assertIn('SSH-команда запрещена', result.stderr.decode())

    def test_authorizer_keys_cannot_deploy_stop_or_choose_stand(self):
        for key in ('authorize-test', 'authorize-prod'):
            for request in ({'action': 'deploy', 'release': self.sha}, {'action': 'stop'},
                            dict(self.authorization, stand='prod')):
                result = self.ssh(key, request)
                self.assertNotEqual(result.returncode, 0)
        for key in ('test', 'prod'):
            self.assertNotEqual(self.ssh(key, self.authorization).returncode, 0)

    def test_authorizer_pins_stand_preserves_docker_and_shares_lock(self):
        calls = self.root / 'docker-calls.jsonl'
        before = calls.read_bytes() if calls.exists() else b''
        for key in ('authorize-test', 'authorize-prod'):
            result = self.ssh(key, self.authorization)
            self.assertEqual(result.returncode, 0, result.stderr.decode())
            self.assertIn('разрешён релиз', result.stdout.decode())
        self.assertEqual(calls.read_bytes() if calls.exists() else b'', before)
        path = self.root / 'releases/prod' / (self.sha + '.json')
        self.assertEqual(path.stat().st_uid, 0)
        self.assertEqual(path.stat().st_mode & 0o777, 0o600)
        import fcntl
        with (self.root / 'deploy.lock').open('a') as lock:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            for key, request in (('authorize-test', self.authorization),
                                 ('test', {'action': 'deploy', 'release': self.sha})):
                result = self.ssh(key, request)
                self.assertNotEqual(result.returncode, 0)
                self.assertIn('Другая операция', result.stderr.decode())

    def test_test_key_cannot_select_prod_or_deploy_prod_only_release(self):
        result = self.ssh('test', {'action': 'deploy', 'release': self.sha, 'stand': 'prod'})
        self.assertNotEqual(result.returncode, 0)
        other_sha = 'b' * 40
        release = json.loads((self.root / 'releases/test' / (self.sha + '.json')).read_text())
        release['commit'] = other_sha
        (self.root / 'releases/prod' / (other_sha + '.json')).write_text(json.dumps(release))
        result = self.ssh('test', {'action': 'deploy', 'release': other_sha})
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse((self.root / 'prod/image.env').exists())

    def test_prod_key_cannot_stop(self):
        result = self.ssh('prod', {'action': 'stop'})
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('Выключение prod запрещено', result.stderr.decode())

    def test_deploy_uid_cannot_read_secrets_change_handler_or_sudo_shell(self):
        for args in (['cat', '/opt/theseus/prod/stand.env'],
                     ['touch', '/usr/local/lib/theseus/injected.py'],
                     ['touch', '/home/deploy/.bashrc'],
                     ['sudo', '-n', '/bin/sh', '-c', 'id']):
            result = subprocess.run(['runuser', '-u', 'deploy', '--', *args], capture_output=True)
            self.assertNotEqual(result.returncode, 0, args)

    def test_valid_key_runs_fixed_deployment_and_test_stop(self):
        result = self.ssh('test', {'action': 'deploy', 'release': self.sha})
        self.assertEqual(result.returncode, 0, result.stderr.decode())
        self.assertIn('healthy', result.stdout.decode())
        self.assertEqual(json.loads((self.root / 'test/state.json').read_text())['status'], 'healthy')
        self.assertEqual((self.root / 'test/state.json').stat().st_uid, 0)
        self.assertEqual(len(list((self.root / 'backups/test').glob('*.dump'))), 1)
        result = self.ssh('test', {'action': 'stop'})
        self.assertEqual(result.returncode, 0, result.stderr.decode())
        calls = [json.loads(line) for line in (self.root / 'docker-calls.jsonl').read_text().splitlines()]
        self.assertFalse(any('theseus-prod' in call or 'theseus_prod' in call for call in calls))
        self.assertFalse(any('pg_restore' in call or 'prune' in call for call in calls))

    def test_writable_config_parent_and_symlink_are_rejected(self):
        path = self.root / 'releases/test'
        path.chmod(0o770)
        try:
            result = self.ssh('test', {'action': 'deploy', 'release': self.sha})
            self.assertNotEqual(result.returncode, 0)
            self.assertIn('Небезопасный владелец', result.stderr.decode())
        finally:
            path.chmod(0o700)
        release = path / (self.sha + '.json')
        saved = path / 'saved.json'
        release.rename(saved)
        release.symlink_to(saved)
        try:
            result = self.ssh('test', {'action': 'deploy', 'release': self.sha})
            self.assertNotEqual(result.returncode, 0)
            self.assertIn('Симлинк', result.stderr.decode())
        finally:
            release.unlink()
            saved.rename(release)
