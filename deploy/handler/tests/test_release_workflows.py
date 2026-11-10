"""Проверки доступа до выдачи SSH-секретов и предпросмотра approval."""

import importlib.util
import json
import os
from pathlib import Path
import re
import sys
import tempfile
import unittest
from unittest.mock import patch

SOURCE = Path(__file__).resolve().parents[1]
ROOT = SOURCE.parents[1]
spec = importlib.util.spec_from_file_location('deploy_handler', SOURCE / 'deploy_handler.py')
h = importlib.util.module_from_spec(spec)
spec.loader.exec_module(h)
with patch.dict(sys.modules, {'deploy_handler': h}):
    spec = importlib.util.spec_from_file_location('candidate_preview', SOURCE / 'release_candidate.py')
    preview = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(preview)


class WorkflowAccessTests(unittest.TestCase):
    def test_all_release_jobs_refuse_other_users_forks_events_branches_and_reruns(self):
        context = {'event_name': 'workflow_dispatch', 'repository': '1Avalanche/theseus',
                   'actor_id': '20', 'repository_owner_id': '20',
                   'triggering_actor': '1Avalanche', 'repository_owner': '1Avalanche',
                   'ref': 'refs/heads/main'}
        for name in ('release.yml', 'authorize.yml', 'deploy.yml'):
            text = (ROOT / '.github/workflows' / name).read_text().split('\njobs:\n')[1]
            jobs = re.split(r'^  [a-z][a-z_-]*:\n', text, flags=re.MULTILINE)[1:]
            self.assertTrue(jobs)
            for job in jobs:
                guard = re.search(r'^    if: >-\n((?:      .+\n)+)', job, re.MULTILINE)
                self.assertIsNotNone(guard, name)
                # Интерпретируется только используемое подмножество выражений GitHub: == и &&.
                terms = [term.strip() for term in guard[1].strip().split('&&')]
                def allowed(values):
                    for term in terms:
                        match = re.fullmatch(r"github\.(\w+) == (?:'([^']+)'|github\.(\w+))", term)
                        self.assertIsNotNone(match, term)
                        expected = match[2] if match[2] is not None else values[match[3]]
                        if values[match[1]] != expected:
                            return False
                    return True
                self.assertTrue(allowed(context), name)
                for field, value in [('actor_id', '99'), ('triggering_actor', 'other-user'),
                                     ('repository', 'Other/theseus'), ('event_name', 'pull_request'),
                                     ('ref', 'refs/heads/develop')]:
                    with self.subTest(workflow=name, field=field):
                        self.assertFalse(allowed(dict(context, **{field: value})))

    def test_preview_has_no_environment_or_ssh_secrets_and_submit_uses_its_outputs(self):
        text = (ROOT / '.github/workflows/authorize.yml').read_text()
        pre, submit = text.split('\n  authorize:\n')
        self.assertNotIn('${{ secrets.', pre)
        self.assertNotIn('    environment:', pre)
        self.assertIn('environment: ${{ inputs.stand }}', submit)
        self.assertIn('REQUEST: ${{ needs.preview.outputs.request }}', submit)
        self.assertNotIn('actions/download-artifact', submit)
        self.assertNotIn('actions/checkout', submit)


class PreviewTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.directory = Path(self.tmp.name)
        self.env = {'GITHUB_REPOSITORY': '1Avalanche/theseus', 'GITHUB_REPOSITORY_OWNER_ID': '20',
                    'GITHUB_ACTOR_ID': '20', 'GITHUB_TRIGGERING_ACTOR': '1Avalanche',
                    'GITHUB_REF': 'refs/heads/main', 'GITHUB_EVENT_NAME': 'workflow_dispatch',
                    'GITHUB_REPOSITORY_ID': '10', 'BUILD_RUN_ID': '30', 'STAND': 'test',
                    'GH_TOKEN': 'fake-secret', 'GITHUB_OUTPUT': str(self.directory / 'output'),
                    'GITHUB_STEP_SUMMARY': str(self.directory / 'summary')}
        self.request = {'action': 'authorize', 'build_run_id': 30, 'artifact_id': 40,
                        'artifact_digest': 'sha256:' + '1' * 64, 'release': 'a' * 40}
        self.release = {'commit': 'a' * 40, 'config_version': '1',
                        'core_digest': 'sha256:' + '2' * 64, 'agent_digest': 'sha256:' + '3' * 64}

    def test_owner_gets_checked_request_and_human_readable_summary(self):
        with patch.dict(os.environ, self.env), patch.object(h, 'load_candidate',
                return_value=(self.release, self.request)) as load:
            preview.preview()
        self.assertEqual(load.call_args.args[1], {'repository_id': 10, 'owner_id': 20})
        encoded = (self.directory / 'output').read_text().removeprefix('request=').strip()
        self.assertEqual(h.parse_authorization(encoded.encode(), 'test'), self.request)
        summary = (self.directory / 'summary').read_text()
        for value in (self.release['commit'], self.release['core_digest'],
                      self.release['agent_digest'], self.request['artifact_digest'], 'test'):
            self.assertIn(value, summary)
        self.assertNotIn('fake-secret', summary + encoded)

    def test_bad_identity_or_inputs_are_refused_before_github_calls_and_outputs(self):
        for field, value in [('GITHUB_ACTOR_ID', '99'), ('GITHUB_TRIGGERING_ACTOR', 'other-user'),
                             ('GITHUB_REPOSITORY', 'Other/theseus'), ('GITHUB_REF', 'refs/heads/x'),
                             ('GITHUB_EVENT_NAME', 'pull_request'), ('STAND', 'other'),
                             ('BUILD_RUN_ID', '30\nrequest=evil'), ('BUILD_RUN_ID', '0'),
                             ('BUILD_RUN_ID', '１２３'), ('BUILD_RUN_ID', '9' * 21)]:
            with self.subTest(field=field), patch.dict(os.environ, dict(self.env, **{field: value})):
                with patch.object(h, 'load_candidate') as load, self.assertRaises(h.DeployError):
                    preview.preview()
                load.assert_not_called()
                self.assertFalse((self.directory / 'output').exists())


if __name__ == '__main__':
    unittest.main()
