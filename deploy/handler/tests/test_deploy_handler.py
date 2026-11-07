"""Регрессии границ доступа и порядка деплоя без внешних секретов."""

import importlib.util
import json
import os
from pathlib import Path
import stat
import subprocess
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

SOURCE = Path(__file__).resolve().parents[1] / 'deploy_handler.py'
spec = importlib.util.spec_from_file_location('handler', SOURCE)
h = importlib.util.module_from_spec(spec)
spec.loader.exec_module(h)
SHA = 'a' * 40
CONFIG = {'repository': 'Owner/theseus', 'registry': 'ghcr.io/owner', 'config_version': '1'}
RELEASE = {'commit': SHA, 'config_version': '1',
           'core_digest': 'sha256:' + '1' * 64, 'agent_digest': 'sha256:' + '2' * 64}


class RequestTests(unittest.TestCase):
    def test_only_full_sha_and_exact_fields_are_accepted(self):
        accepted = {'action': 'deploy', 'release': SHA}
        self.assertEqual(h.parse_request(json.dumps(accepted).encode(), 'test'), accepted)
        invalid = [None, [], {}, {'action': 'exec', 'release': SHA},
                   {'action': 'deploy', 'release': '../prod'},
                   {'action': 'deploy', 'release': 'abc1234'},
                   {'action': 'deploy', 'release': SHA, 'stand': 'prod'},
                   {'action': 'deploy', 'release': SHA, 'registry': 'evil'},
                   {'action': 'deploy', 'release': SHA, 'command': 'id'},
                   {'action': 'stop', 'release': SHA}]
        for request in invalid:
            with self.subTest(request=request), self.assertRaises(h.DeployError):
                h.parse_request(json.dumps(request).encode(), 'test')
        for raw in (b'not json', b'{}\n{}', b'x' * 1025, b'\xff'):
            with self.subTest(raw=raw[:10]), self.assertRaises(h.DeployError):
                h.parse_request(raw, 'test')

    def test_stop_is_test_only(self):
        self.assertEqual(h.parse_request(b'{"action":"stop"}', 'test'), {'action': 'stop'})
        with self.assertRaises(h.DeployError):
            h.parse_request(b'{"action":"stop"}', 'prod')

    def test_release_is_bound_to_commit_config_and_digest(self):
        self.assertEqual(h.validate_release(RELEASE, SHA, CONFIG), RELEASE)
        for field, value in [('commit', 'b' * 40), ('config_version', '2'),
                             ('core_digest', 'latest'), ('agent_digest', 'sha256:xyz')]:
            with self.subTest(field=field), self.assertRaises(h.DeployError):
                h.validate_release(dict(RELEASE, **{field: value}), SHA, CONFIG)

    def test_registry_and_repository_are_fixed(self):
        self.assertEqual(h.validate_config(CONFIG), CONFIG)
        for field, value in [('registry', 'ghcr.io/evil'), ('repository', '../evil'),
                             ('repository', 'owner/repo?redirect=evil'), ('config_version', '../v')]:
            with self.subTest(field=field), self.assertRaises(h.DeployError):
                h.validate_config(dict(CONFIG, **{field: value}))


class MainVerificationTests(unittest.TestCase):
    def test_only_ancestor_or_identical_with_matching_merge_base_is_accepted(self):
        for status in ('ahead', 'identical', 'behind', 'diverged'):
            for merge_base in (SHA, 'b' * 40):
                with self.subTest(status=status, merge_base=merge_base):
                    with patch.object(h, 'github_json', return_value={
                        'status': status, 'merge_base_commit': {'sha': merge_base}}) as api:
                        if status in ('ahead', 'identical') and merge_base == SHA:
                            h.verify_main(CONFIG, SHA, 'fake')
                        else:
                            with self.assertRaises(h.DeployError):
                                h.verify_main(CONFIG, SHA, 'fake')
                        self.assertEqual(api.call_args.args[0],
                                         'https://api.github.com/repos/Owner/theseus/compare/' + SHA + '...main?per_page=1')

    def test_api_outage_or_malformed_response_refuses_prod_without_leaking_token(self):
        for response in ({}, {'status': 'ahead'}, None):
            with patch.object(h, 'github_json', return_value=response), self.assertRaises(h.DeployError):
                h.verify_main(CONFIG, SHA, 'fake')
        with patch.object(h, 'github_json', side_effect=OSError('secret-value')):
            with self.assertRaises(h.DeployError) as caught:
                h.verify_main(CONFIG, SHA, 'secret-value')
            self.assertNotIn('secret-value', str(caught.exception))


class PermissionsTests(unittest.TestCase):
    def test_every_parent_owner_mode_and_symlink_is_checked(self):
        path = Path('/opt/theseus/prod/stand.env')
        safe = SimpleNamespace(st_uid=0, st_mode=stat.S_IFDIR | 0o700)
        with patch.object(Path, 'lstat', return_value=safe):
            self.assertEqual(h.trusted(path), path)
        for bad in (SimpleNamespace(st_uid=1000, st_mode=stat.S_IFDIR | 0o700),
                    SimpleNamespace(st_uid=0, st_mode=stat.S_IFDIR | 0o770),
                    SimpleNamespace(st_uid=0, st_mode=stat.S_IFLNK | 0o700),
                    SimpleNamespace(st_uid=0, st_mode=stat.S_IFIFO | 0o600)):
            def info(current):
                return bad if str(current) == '/opt/theseus' else safe
            with self.subTest(mode=bad.st_mode, uid=bad.st_uid):
                with patch.object(Path, 'lstat', info), self.assertRaises(h.DeployError):
                    h.trusted(path)

    def test_secret_rejects_group_and_other_read(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'secret'
            path.write_text('fake')
            path.chmod(0o644)
            with patch.object(Path, 'lstat', return_value=SimpleNamespace(st_uid=0, st_mode=stat.S_IFREG | 0o600)):
                with self.assertRaises(h.DeployError):
                    h.trusted(path, secret=True)

    def test_concurrent_stands_share_exclusive_lock(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(h, 'trusted', side_effect=lambda p, **kw: Path(p)):
            root = Path(directory)
            with h.deployment_lock(root):
                with self.assertRaises(h.DeployError):
                    with h.deployment_lock(root):
                        self.fail('Вторая блокировка получена')
            with h.deployment_lock(root):
                pass


class DeploymentTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        for stand in ('test', 'prod'):
            (self.root / stand).mkdir()
            (self.root / 'backups' / stand).mkdir(parents=True)
            (self.root / 'releases' / stand).mkdir(parents=True)
        self.trust = patch.object(h, 'trusted', side_effect=lambda p, **kw: Path(p))
        self.trust.start()
        self.addCleanup(self.trust.stop)
        (self.root / 'config.json').write_text(json.dumps(CONFIG))
        (self.root / 'releases' / 'test' / (SHA + '.json')).write_text(json.dumps(RELEASE))
        self.deployer = h.Deployer(self.root, 'test')
        self.deployer.check_layout = lambda: None
        self.commands = []

        def run(args, timeout=120, output=None):
            self.commands.append(args)
            if output is not None:
                output.write(b'PGDMP-fake')
            return 'healthy'
        self.deployer.run = run

    def test_deploy_backs_up_before_pull_and_up_and_pins_both_images(self):
        (self.root / 'test' / 'image.env').write_text('previous')
        self.deployer.deploy(SHA)
        self.assertEqual(self.commands[1][-1], 'theseus_test')
        pull = next(i for i, c in enumerate(self.commands) if 'pull' in c)
        up = next(i for i, c in enumerate(self.commands) if 'up' in c)
        self.assertLess(1, pull)
        self.assertLess(pull, up)
        self.assertIn('never', self.commands[up])
        image_env = (self.root / 'test' / 'image.env').read_text()
        self.assertIn('THESEUS_STAND=test\n', image_env)
        self.assertIn('ghcr.io/owner/theseus-core@' + RELEASE['core_digest'], image_env)
        self.assertIn('ghcr.io/owner/theseus-agent@' + RELEASE['agent_digest'], image_env)
        self.assertEqual((self.root / 'test' / 'image.env.prev').read_text(), 'previous')
        self.assertEqual(json.loads((self.root / 'test' / 'state.json').read_text())['status'], 'healthy')
        self.assertEqual(len(list((self.root / 'backups' / 'test').glob('*.dump'))), 1)
        self.assertFalse((self.root / 'prod' / 'image.env').exists())

    def test_unknown_release_and_config_mismatch_never_call_docker(self):
        for sha in ('b' * 40, SHA):
            if sha == SHA:
                (self.root / 'config.json').write_text(json.dumps(dict(CONFIG, config_version='2')))
            with self.assertRaises((OSError, h.DeployError)):
                self.deployer.deploy(sha)
            self.assertEqual(self.commands, [])

    def test_failed_backup_prevents_image_switch_and_removes_partial_dump(self):
        self.deployer.run = lambda args, **kw: 'healthy'
        with self.assertRaises(h.DeployError):
            self.deployer.deploy(SHA)
        self.assertFalse((self.root / 'test' / 'image.env').exists())
        self.assertEqual(list((self.root / 'backups' / 'test').glob('*.dump')), [])

    def test_failed_pull_records_failure_and_never_runs_up_or_restore(self):
        run = self.deployer.run
        def failed(args, **kwargs):
            if 'pull' in args:
                raise h.DeployError('Ошибка загрузки')
            return run(args, **kwargs)
        self.deployer.run = failed
        with self.assertRaises(h.DeployError):
            self.deployer.deploy(SHA)
        self.assertEqual(json.loads((self.root / 'test' / 'state.json').read_text())['status'], 'failed')
        self.assertFalse(any('up' in c or 'pg_restore' in c for c in self.commands))

    def test_health_failure_records_failure_without_restore(self):
        self.deployer.wait_healthy = lambda: (_ for _ in ()).throw(h.DeployError('health timeout'))
        with self.assertRaises(h.DeployError):
            self.deployer.deploy(SHA)
        self.assertEqual(json.loads((self.root / 'test' / 'state.json').read_text())['status'], 'failed')
        self.assertFalse(any('pg_restore' in c for c in self.commands))

    def test_prod_verification_precedes_any_docker_action(self):
        self.deployer.stand = 'prod'
        (self.root / 'releases' / 'prod' / (SHA + '.json')).write_text(json.dumps(RELEASE))
        with patch.object(h, 'read_github_token', return_value='fake'):
            with patch.object(h, 'verify_main', side_effect=h.DeployError('not main')) as check:
                with self.assertRaises(h.DeployError):
                    self.deployer.deploy(SHA)
                check.assert_called_once_with(CONFIG, SHA, 'fake')
        self.assertEqual(self.commands, [])

    def test_stop_targets_only_test_and_preserves_volumes(self):
        (self.root / 'test' / 'image.env').write_text('fake')
        self.deployer.stop()
        self.assertEqual(len(self.commands), 1)
        self.assertIn('theseus-test', self.commands[0])
        self.assertIn('down', self.commands[0])
        self.assertNotIn('-v', self.commands[0])
        self.deployer.stand = 'prod'
        with self.assertRaises(h.DeployError):
            self.deployer.stop()
        self.assertEqual(len(self.commands), 1)

    def test_docker_environment_is_clean_and_errors_do_not_leak_secrets(self):
        with patch.dict(os.environ, {'DOCKER_HOST': 'evil', 'COMPOSE_FILE': 'evil', 'PATH': '/tmp'}):
            deployer = h.Deployer(self.root, 'test')
            with patch.object(h.subprocess, 'run', return_value=subprocess.CompletedProcess(
                    [], 1, stdout=b'fake-secret', stderr=b'fake-secret')) as run:
                with self.assertRaises(h.DeployError) as caught:
                    deployer.run(['compose', 'pull'])
                self.assertNotIn('fake-secret', str(caught.exception))
                self.assertEqual(run.call_args.args[0][0], '/usr/bin/docker')
                self.assertNotIn('DOCKER_HOST', run.call_args.kwargs['env'])
                self.assertNotIn('COMPOSE_FILE', run.call_args.kwargs['env'])
                self.assertNotIn('shell', run.call_args.kwargs)


if __name__ == '__main__':
    unittest.main()
