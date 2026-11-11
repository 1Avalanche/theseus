import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from collections import namedtuple

MODULE = Path(__file__).resolve().parents[1] / 'monitor.py'
SPEC = importlib.util.spec_from_file_location('monitor', MODULE)
m = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(m)


def dependency(**kwargs):
    return dict(failing_since=None, last_error_at=None, last_code=None, consecutive_errors=0, errors={}, **kwargs)


def snapshot(now, module='core', **overrides):
    value = dict(version=1, started_at=now - 3600, observed_at=now, task_active=True,
                 last_success_at=now, telegram=dependency())
    if module == 'core':
        value.update(database_ok=True, overdue_count=0, oldest_due_at=None)
    else:
        value.update(polling=dependency(), openrouter=dependency())
    value.update(overrides)
    return value


class MonitorTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.now = 100_000
        self.snapshots = {module: snapshot(self.now, module) for module in ('core', 'agent')}
        self.calls = []
        self.state = {}
        self.env = {'THESEUS_MONITOR_DISK_PATHS': '/', 'THESEUS_MONITOR_HEARTBEAT_URL': 'https://pulse.test/ok',
                    'THESEUS_MONITOR_FAILURE_URL': 'https://pulse.test/fail'}

    def docker(self, *args):
        self.calls.append(args)
        if args[0] == 'inspect':
            return json.dumps({'Running': True})
        if args[0] == 'exec':
            module = args[1].rsplit('-', 1)[1]
            return json.dumps(self.snapshots[module])
        if args[0] == 'restart':
            return args[-1]
        self.fail(args)

    def check(self, now=None):
        usage = namedtuple('Usage', 'total used free')(100, 20, 80)
        return m.check(self.root, self.state, self.now if now is None else now,
                       self.docker, lambda _: usage, self.env)

    def test_exact_ten_minute_boundaries_and_empty_queue(self):
        self.assertEqual(([], False), m.evaluate(snapshot(self.now), 'core', self.now))
        for elapsed in (599, 600, 601):
            value = snapshot(self.now, last_success_at=self.now - elapsed, oldest_due_at=self.now - elapsed,
                             overdue_count=105)
            issues, restart = m.evaluate(value, 'core', self.now)
            self.assertEqual(elapsed >= 600, 'scheduler' in issues)
            self.assertEqual(elapsed >= 600, 'overdue' in issues)
            self.assertEqual(elapsed >= 600, restart)

    def test_no_messages_does_not_stop_polling(self):
        self.assertEqual(([], False), m.evaluate(snapshot(self.now, 'agent'), 'agent', self.now))

    def test_external_outage_and_database_failure_never_restart(self):
        signal = dependency()
        signal.update(failing_since=self.now - 1000, last_error_at=self.now, consecutive_errors=5, last_code='503')
        value = snapshot(self.now, 'agent', last_success_at=self.now - 1200, polling=signal)
        self.assertEqual((['polling'], False), m.evaluate(value, 'agent', self.now))
        core = snapshot(self.now, database_ok=False, last_success_at=self.now - 1200)
        self.assertEqual((['scheduler', 'database'], False), m.evaluate(core, 'core', self.now))

    def test_single_temporary_error_does_not_alert_and_invalid_token_does(self):
        signal = dependency()
        signal.update(failing_since=self.now - 600, last_error_at=self.now - 600, consecutive_errors=1, last_code='503')
        self.assertFalse(m.dependency_failed(signal, self.now))
        signal['last_code'] = '401'
        self.assertTrue(m.dependency_failed(signal, self.now))

    def test_stale_and_future_diagnostics_never_trigger_restart(self):
        for value in (snapshot(self.now, observed_at=self.now - 600), snapshot(self.now, observed_at=self.now + 1000)):
            self.assertEqual((['snapshot'], False), m.evaluate(value, 'core', self.now))

    def test_stopped_test_is_ignored_and_prod_still_checked(self):
        (self.root / 'test').mkdir()
        (self.root / 'test/state.json').write_text('{"status":"stopped"}')
        self.assertEqual({}, self.check())
        self.assertTrue(self.calls)
        self.assertFalse(any('theseus-test-' in str(call) for call in self.calls))

    def test_restart_budget_persists_and_cooldown_prevents_loops(self):
        self.snapshots['core']['last_success_at'] = self.now - 600
        for attempt in range(4):
            now = self.now + attempt * 600
            for value in self.snapshots.values():
                value['observed_at'] = now
            self.snapshots['agent']['last_success_at'] = now
            issues = self.check(now)
        restarts = [call for call in self.calls if call[0] == 'restart']
        self.assertEqual(2, len(restarts))
        self.assertIn('restart_limit', issues['prod/core'])
        saved = json.loads((self.root / 'monitor-state.json').read_text())
        self.assertEqual(2, saved['restarts']['prod/core']['attempts'])

    def test_failed_docker_does_not_leak_stderr(self):
        def docker(*args):
            raise subprocess.CalledProcessError(1, args, stderr='SECRET')
        self.assertEqual((['snapshot'], False, None), m.inspect_service('prod', 'core', self.now, docker))

    def test_startup_grace_does_not_reset_restart_budget_without_real_progress(self):
        self.state['restarts'] = {'prod/core': {'attempts': 2, 'last_at': self.now - 600}}
        self.snapshots['core'].update(started_at=self.now - 500, last_success_at=None)
        self.assertEqual({}, self.check())
        self.assertEqual(2, self.state['restarts']['prod/core']['attempts'])
        self.snapshots['core']['last_success_at'] = self.now
        self.assertEqual({}, self.check())
        self.assertEqual(0, self.state['restarts']['prod/core']['attempts'])

    def test_notifications_deduplicate_recover_and_retry_failed_delivery(self):
        sent, pings = [], []
        sender = lambda text, env: sent.append(text)
        ping = lambda url, body: pings.append((url, body))
        problem = {'prod/core': ['scheduler']}
        for problems in (problem, problem, {}, {}):
            m.report(self.root, self.state, problems, self.env, sender, ping)
        self.assertEqual(2, len(sent))
        self.assertEqual('https://pulse.test/fail', pings[0][0])
        self.assertEqual('https://pulse.test/ok', pings[-1][0])

        def failed_sender(text, env):
            raise OSError('SECRET')
        m.report(self.root, self.state, problem, self.env, failed_sender, ping)
        self.assertEqual({}, self.state['notified'])
        m.report(self.root, self.state, problem, self.env, sender, ping)
        self.assertEqual(problem, self.state['notified'])

    def test_disk_threshold_and_backup_opt_in(self):
        self.assertNotIn('backup', self.check())
        usage = namedtuple('Usage', 'total used free')(100, 80, 20)
        self.env['THESEUS_MONITOR_BACKUP_SUCCESS_FILE'] = str(self.root / 'backup-success')
        problems = m.check(self.root, {}, self.now, self.docker, lambda _: usage, self.env)
        self.assertEqual(['disk'], problems['disk:/'])
        self.assertEqual(['backup'], problems['backup'])
        (self.root / 'backup-success').write_text(str(self.now))
        self.assertNotIn('backup', self.check())

    def test_malformed_snapshot_is_an_alarm_without_restart(self):
        self.snapshots['core'] = {'version': 12}
        self.assertEqual(['snapshot'], self.check()['prod/core'])
        self.assertFalse(any(call[0] == 'restart' for call in self.calls))

    def test_partial_snapshot_never_authorizes_restart(self):
        self.snapshots['core']['last_success_at'] = self.now - 1200
        del self.snapshots['core']['overdue_count']
        self.assertEqual(['snapshot'], self.check()['prod/core'])
        self.assertFalse(any(call[0] == 'restart' for call in self.calls))

    def test_missing_pulse_is_not_treated_as_legitimate_startup(self):
        del self.snapshots['agent']['last_success_at']
        self.assertEqual(['snapshot'], self.check()['prod/agent'])
        self.assertFalse(any(call[0] == 'restart' for call in self.calls))

    def test_unknown_stand_state_does_not_authorize_restart(self):
        (self.root / 'test').mkdir()
        (self.root / 'test/state.json').write_text('{"status":"unknown"}')
        self.assertEqual(['stand_state'], self.check()['test'])
        self.assertFalse(any('theseus-test-' in str(call) for call in self.calls))

    def test_corrupt_restart_budget_is_rejected(self):
        for attempts in (-1, 3, True, '0'):
            with self.assertRaises(ValueError):
                m.validate_state({'notified': {}, 'restarts': {'prod/core': {'attempts': attempts, 'last_at': 0}}}, self.now)

    def test_timer_is_ten_minutes(self):
        timer = MODULE.with_name('theseus-monitor.timer').read_text()
        self.assertIn('OnBootSec=10min', timer)
        self.assertIn('OnUnitActiveSec=10min', timer)

    def test_post_rejects_non_https_before_sending(self):
        with self.assertRaises(ValueError):
            m.post('http://example.test/secret', '')


if __name__ == '__main__':
    unittest.main()
