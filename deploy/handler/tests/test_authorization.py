"""Разрешение только неизменяемого артефакта доверенной сборки, без Docker."""

import copy
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import urllib.error
import zipfile

spec = importlib.util.spec_from_file_location('authorization_handler',
                                            Path(__file__).resolve().parents[1] / 'deploy_handler.py')
h = importlib.util.module_from_spec(spec)
spec.loader.exec_module(h)
SHA = 'a' * 40
CONFIG = {'repository': 'Owner/theseus', 'registry': 'ghcr.io/owner', 'config_version': '1'}
IDENTITY = {'repository_id': 10, 'owner_id': 20}
RELEASE = {'commit': SHA, 'config_version': '1',
           'core_digest': 'sha256:' + '1' * 64, 'agent_digest': 'sha256:' + '2' * 64}
RUN = {'id': 30, 'event': 'workflow_dispatch', 'status': 'completed', 'conclusion': 'success',
       'path': '.github/workflows/release.yml', 'head_branch': 'main', 'head_sha': SHA,
       'repository': {'id': 10}, 'head_repository': {'id': 10},
       'actor': {'id': 20}, 'triggering_actor': {'id': 20}, 'run_attempt': 1}


def zip_bytes(entries):
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, 'w') as archive:
        for name, content in entries:
            archive.writestr(name, content)
    return buffer.getvalue()


def fixture(data=None):
    data = data if data is not None else zip_bytes([('release.json', json.dumps(RELEASE))])
    digest = 'sha256:' + hashlib.sha256(data).hexdigest()
    artifact = {'id': 40, 'name': 'release-candidate', 'expired': False, 'digest': digest,
                'size_in_bytes': len(data), 'workflow_run': {
                    'id': 30, 'repository_id': 10, 'head_repository_id': 10,
                    'head_branch': 'main', 'head_sha': SHA}}
    request = {'action': 'authorize', 'build_run_id': 30, 'artifact_id': 40,
               'artifact_digest': digest, 'release': SHA}
    return data, artifact, request


class AuthorizationRequestTests(unittest.TestCase):
    def test_exact_schema_and_strict_ids(self):
        _, _, request = fixture()
        self.assertEqual(h.parse_authorization(json.dumps(request).encode(), 'test'), request)
        invalid = [None, [], {}, dict(request, stand='prod'), dict(request, command='id'),
                   dict(request, action='deploy'), dict(request, build_run_id=True),
                   dict(request, build_run_id='30'), dict(request, build_run_id=0),
                   dict(request, artifact_id=-1), dict(request, artifact_digest='latest'),
                   dict(request, release='../prod')]
        for item in invalid:
            with self.subTest(item=item), self.assertRaises(h.DeployError):
                h.parse_authorization(json.dumps(item).encode(), 'test')
        for data in (b'\xff', b'x' * 1025, b'{}\n{}', b'{"action":"x","action":"authorize"}'):
            with self.subTest(data=data[:10]), self.assertRaises(h.DeployError):
                h.parse_authorization(data, 'test')
        with self.assertRaises(h.DeployError):
            h.parse_request(json.dumps(request).encode(), 'test')
        with self.assertRaises(h.DeployError):
            h.parse_authorization(b'{"action":"stop"}', 'prod')

    def test_authorization_identity_fails_closed(self):
        self.assertEqual(h.validate_authorization_config(IDENTITY), IDENTITY)
        for invalid in ({}, dict(IDENTITY, owner_id=True), dict(IDENTITY, owner_id=0),
                        dict(IDENTITY, repository_id='10'), dict(IDENTITY, command='id')):
            with self.subTest(invalid=invalid), self.assertRaises(h.DeployError):
                h.validate_authorization_config(invalid)


class CandidateTests(unittest.TestCase):
    def setUp(self):
        self.data, self.artifact, self.request = fixture()
        self.run = copy.deepcopy(RUN)
        self.listing = {'total_count': 1, 'artifacts': [self.artifact]}
        def github(url, token):
            self.assertEqual(token, 'fake-token')
            self.assertTrue(url.startswith('https://api.github.com/repos/Owner/theseus/actions/'))
            if '/artifacts?' in url:
                return self.listing
            if '/artifacts/' in url:
                return self.artifact
            return self.run
        self.api = patch.object(h, 'github_json', side_effect=github)
        self.archive = patch.object(h, 'github_archive', side_effect=lambda *a: self.data)
        self.api.start()
        self.archive.start()
        self.addCleanup(self.api.stop)
        self.addCleanup(self.archive.stop)

    def load(self, **kwargs):
        return h.load_candidate(CONFIG, IDENTITY, 30, 'fake-token', **kwargs)

    def test_preview_and_server_select_same_immutable_artifact(self):
        self.assertEqual(self.load(), (RELEASE, self.request))
        self.assertEqual(self.load(artifact_id=40, expected_digest=self.request['artifact_digest']),
                         (RELEASE, self.request))

    def test_wrong_workflow_fork_actor_rerun_branch_or_failed_build_is_refused(self):
        mutations = [('event', 'pull_request'), ('status', 'in_progress'), ('conclusion', 'failure'),
                     ('path', '.github/workflows/evil.yml'), ('head_branch', 'develop'),
                     ('repository', {'id': 99}), ('head_repository', {'id': 99}),
                     ('actor', {'id': 99}), ('triggering_actor', {'id': 99}), ('id', 99)]
        for field, value in mutations:
            self.run = dict(copy.deepcopy(RUN), **{field: value})
            with self.subTest(field=field), self.assertRaises(h.DeployError):
                self.load()

    def test_expired_missing_ambiguous_and_foreign_artifacts_are_refused(self):
        for field, value in [('expired', True), ('id', 99), ('name', 'evil'),
                             ('size_in_bytes', 65537), ('digest', None)]:
            _, original, _ = fixture()
            self.artifact = dict(original, **{field: value})
            with self.subTest(field=field), self.assertRaises(h.DeployError):
                self.load(artifact_id=40)
        _, self.artifact, _ = fixture()
        for field in ('id', 'repository_id', 'head_repository_id', 'head_sha', 'head_branch'):
            original = self.artifact['workflow_run'][field]
            self.artifact['workflow_run'][field] = 'wrong'
            with self.subTest(origin=field), self.assertRaises(h.DeployError):
                self.load(artifact_id=40)
            self.artifact['workflow_run'][field] = original
        for items in ([], [self.artifact, self.artifact]):
            self.listing = {'total_count': len(items), 'artifacts': items}
            with self.assertRaises(h.DeployError):
                self.load()

    def test_changed_bytes_or_approved_digest_are_refused(self):
        self.data += b'changed'
        with self.assertRaises(h.DeployError):
            self.load()
        self.data, self.artifact, _ = fixture()
        with self.assertRaises(h.DeployError):
            self.load(artifact_id=40, expected_digest='sha256:' + '0' * 64)

    def test_zip_paths_extra_files_oversize_and_invalid_release_are_refused(self):
        for entries in ([('../release.json', '{}')], [('release.json', '{}'), ('script.py', 'evil')],
                        [('release.json', ' ' * 4097)], [('release.json', '{}')],
                        [('release.json', json.dumps(dict(RELEASE, config_version='2')))],
                        [('release.json', '{"commit":"x","commit":"y"}')]):
            self.data, self.artifact, _ = fixture(zip_bytes(entries))
            with self.subTest(entries=[e[0] for e in entries]), self.assertRaises((h.DeployError, ValueError)):
                self.load(artifact_id=40)

    def test_rerun_during_download_is_refused(self):
        def archive(*args):
            self.run = dict(self.run, run_attempt=2)
            return self.data
        with patch.object(h, 'github_archive', side_effect=archive), self.assertRaises(h.DeployError):
            self.load()


class AuthorizationTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        for stand in ('test', 'prod'):
            (self.root / 'releases' / stand).mkdir(parents=True)
        (self.root / 'config.json').write_text(json.dumps(CONFIG))
        (self.root / 'authorization.json').write_text(json.dumps(IDENTITY))
        self.request = fixture()[2]
        for patcher in (patch.object(h, 'trusted', side_effect=lambda p, **kw: Path(p)),
                        patch.object(h, 'read_github_token', return_value='fake'),
                        patch.object(h, 'load_candidate', return_value=(RELEASE, self.request))):
            patcher.start()
            self.addCleanup(patcher.stop)

    def test_writes_only_chosen_stand_and_retries_preserve_permission(self):
        h.authorize(self.root, 'test', self.request)
        path = self.root / 'releases' / 'test' / (SHA + '.json')
        self.assertEqual(json.loads(path.read_text()), RELEASE)
        self.assertEqual(path.stat().st_mode & 0o777, 0o600)
        first = path.stat().st_ino
        h.authorize(self.root, 'test', self.request)
        self.assertEqual(path.stat().st_ino, first)
        self.assertEqual(list((self.root / 'releases' / 'prod').iterdir()), [])
        self.assertFalse((self.root / 'test').exists())

    def test_conflicting_digests_cannot_replace_existing_permission(self):
        h.authorize(self.root, 'test', self.request)
        changed = dict(RELEASE, core_digest='sha256:' + '9' * 64)
        with patch.object(h, 'load_candidate', return_value=(changed, self.request)):
            with self.assertRaises(h.DeployError):
                h.authorize(self.root, 'test', self.request)
        self.assertEqual(json.loads((self.root / 'releases/test' / (SHA + '.json')).read_text()), RELEASE)

    def test_prod_main_check_and_changed_sha_precede_writes(self):
        with patch.object(h, 'verify_main', side_effect=h.DeployError('not main')) as verify:
            with self.assertRaises(h.DeployError):
                h.authorize(self.root, 'prod', self.request)
            verify.assert_called_once_with(CONFIG, SHA, 'fake')
        with self.assertRaises(h.DeployError):
            h.authorize(self.root, 'test', dict(self.request, release='b' * 40))
        self.assertEqual(list((self.root / 'releases/prod').iterdir()), [])
        self.assertEqual(list((self.root / 'releases/test').iterdir()), [])

    def test_github_failure_does_not_leak_credentials_or_write(self):
        with patch.object(h, 'load_candidate', side_effect=OSError('fake-secret')):
            with self.assertRaises(h.DeployError) as caught:
                h.authorize(self.root, 'test', self.request)
        self.assertNotIn('fake-secret', str(caught.exception))
        self.assertEqual(list((self.root / 'releases/test').iterdir()), [])


class DownloadTests(unittest.TestCase):
    def test_redirect_download_has_no_authorization_and_no_followup_redirect(self):
        from unittest.mock import MagicMock
        opener = MagicMock()
        redirect = urllib.error.HTTPError('https://api.github.com/x', 302, 'redirect',
                                         {'Location': 'https://storage.blob.core.windows.net/archive?sig=fake'}, io.BytesIO())
        response = MagicMock()
        response.__enter__.return_value.read.return_value = b'archive'
        opener.open.side_effect = [redirect, response]
        with patch.object(h.urllib.request, 'build_opener', return_value=opener):
            self.assertEqual(h.github_archive('https://api.github.com/x', 'secret'), b'archive')
        initial, download = [call.args[0] for call in opener.open.call_args_list]
        self.assertEqual(initial.get_header('Authorization'), 'Bearer secret')
        self.assertIsNone(download.get_header('Authorization'))

    def test_untrusted_redirect_destinations_are_refused(self):
        from unittest.mock import MagicMock
        for url in ('http://storage.blob.core.windows.net/x', 'https://evil.test/x',
                    'https://storage.blob.core.windows.net.evil.test/x',
                    'https://user@storage.blob.core.windows.net/x',
                    'https://storage.blob.core.windows.net:444/x'):
            opener = MagicMock()
            opener.open.side_effect = urllib.error.HTTPError('https://api.github.com/x', 302, '',
                                                            {'Location': url}, io.BytesIO())
            with self.subTest(url=url), patch.object(h.urllib.request, 'build_opener', return_value=opener):
                with self.assertRaises(h.DeployError):
                    h.github_archive('https://api.github.com/x', 'secret')
            self.assertEqual(opener.open.call_count, 1)


if __name__ == '__main__':
    unittest.main()
