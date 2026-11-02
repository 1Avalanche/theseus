"""Тесты хука bash_guard. Запуск: python3 -m unittest discover -s .claude/hooks/tests"""
import json
import os
import subprocess
import sys
import tempfile
import unittest

HOOKS_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, HOOKS_DIR)

import bash_guard  # noqa: E402


class GuardTestCase(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls._tmp = tempfile.TemporaryDirectory()
        cls.project = os.path.realpath(os.path.join(cls._tmp.name, "theseus"))
        os.makedirs(os.path.join(cls.project, "src", "main", "kotlin"))
        os.makedirs(os.path.join(cls.project, "core", "build"))

    @classmethod
    def tearDownClass(cls):
        cls._tmp.cleanup()

    def reasons(self, command):
        return bash_guard.analyze(command, self.project, self.project)

    def assertBlocked(self, command):
        self.assertTrue(self.reasons(command), "должна быть заблокирована: %r" % command)

    def assertAllowed(self, command):
        self.assertEqual([], self.reasons(command), "должна быть разрешена: %r" % command)


class MentionsAreNotCallsTest(GuardTestCase):
    def test_dangerous_text_inside_quotes_is_allowed(self):
        self.assertAllowed('echo "rm -rf /"')
        self.assertAllowed("echo 'git reset --hard'")
        self.assertAllowed('git commit -m "не вызывать claude и git push --force"')
        self.assertAllowed("echo '$(claude -p hi)'")

    def test_grep_pattern_is_allowed(self):
        self.assertAllowed('grep -rn "rm -rf" .')
        self.assertAllowed("rg 'claude -p' docs/")
        self.assertAllowed('git log --grep="reset --hard"')

    def test_heredoc_body_is_data(self):
        self.assertAllowed("cat <<'EOF' | grep push\ngit push --force\nrm -rf /\nEOF")
        self.assertAllowed("cat <<EOF > /tmp/note.txt\nclaude -p hi\nEOF")
        self.assertAllowed("cat <<-EOF\n\trm -rf ~\n\tEOF\necho done")

    def test_commit_message_heredoc_inside_substitution(self):
        self.assertAllowed('git commit -m "$(cat <<\'EOF\'\nfeat: 1) убрать rm -rf, it\'s ok\nEOF\n)"')

    def test_comments_are_ignored(self):
        self.assertAllowed("ls -la # rm -rf /")
        self.assertAllowed("# claude -p hi\necho ok")

    def test_safe_commands(self):
        self.assertAllowed("./gradlew build")
        self.assertAllowed("git status && git diff --stat")
        self.assertAllowed("ls 2>&1 | head")
        self.assertAllowed("rm notes.txt")
        self.assertAllowed("command -v claude")
        self.assertAllowed("which claude")
        self.assertAllowed("docker compose up -d")
        self.assertAllowed("docker compose exec db psql -c 'select 1'")
        self.assertAllowed("git restore --staged src/main/kotlin/Main.kt")
        self.assertAllowed("git push -u origin feature")
        self.assertAllowed("git clean -n -d")
        self.assertAllowed("FOO=1 ./gradlew test")


class DestructiveTest(GuardTestCase):
    def test_rm_recursive(self):
        self.assertBlocked("rm -rf /")
        self.assertBlocked("rm -rf *")
        self.assertBlocked("rm -fr src")
        self.assertBlocked("rm -r -f ~/Documents")
        self.assertBlocked("rm --recursive .")
        self.assertBlocked("rm -rf /tmp/*")
        self.assertBlocked("cd src && rm -rf main")
        self.assertBlocked("rm -rf $SOMEDIR")

    def test_rm_recursive_in_disposable_dirs_is_allowed(self):
        self.assertAllowed("rm -rf build core/build")
        self.assertAllowed("rm -rf build/*")
        self.assertAllowed("rm -rf .gradle")
        self.assertAllowed("rm -rf /tmp/theseus-test")

    def test_rm_tricks(self):
        self.assertBlocked("r'm' -rf src")
        self.assertBlocked('"rm" -rf src')
        self.assertBlocked("\\rm -rf src")
        self.assertBlocked("$'\\x72m' -rf src")
        self.assertBlocked('X="1" rm -rf src')
        self.assertBlocked("CMD=rm; $CMD -rf src")

    def test_git(self):
        self.assertBlocked("git reset --hard HEAD~1")
        self.assertBlocked("git -C core reset --hard")
        self.assertBlocked("git push -f")
        self.assertBlocked("git push --force-with-lease")
        self.assertBlocked("git push origin +main")
        self.assertBlocked("git push origin --delete feature")
        self.assertBlocked("git clean -fdx")
        self.assertBlocked("git checkout -- .")
        self.assertBlocked("git checkout .")
        self.assertBlocked("git restore src/main/kotlin/Main.kt")
        self.assertBlocked("git branch -D feature")
        self.assertBlocked("git stash drop")
        self.assertBlocked("git filter-branch --tree-filter x")

    def test_docker_and_db(self):
        self.assertBlocked("docker compose down -v")
        self.assertBlocked("docker compose -f compose.yml down --volumes")
        self.assertBlocked("docker-compose down -v")
        self.assertBlocked("docker volume rm theseus_pg")
        self.assertBlocked("docker system prune -a")
        self.assertBlocked("psql -c 'DROP TABLE reminders'")
        self.assertBlocked('docker compose exec db psql -U app -c "truncate users"')
        self.assertBlocked("dropdb theseus")
        self.assertBlocked("./gradlew flywayClean")
        self.assertBlocked("flyway clean")

    def test_system(self):
        self.assertBlocked("sudo ls")
        self.assertBlocked("dd if=/dev/zero of=/dev/disk2")
        self.assertBlocked("diskutil eraseDisk APFS X disk2")
        self.assertBlocked("kill -9 -1")
        self.assertBlocked("find . -name '*.kt' -delete")
        self.assertBlocked("find . -type f -exec rm {} \\;")
        self.assertBlocked("ls | xargs rm -rf")

    def test_nested_execution(self):
        self.assertBlocked('bash -c "rm -rf src"')
        self.assertBlocked("sh -lc 'git reset --hard'")
        self.assertBlocked("eval 'rm -rf src'")
        self.assertBlocked("echo $(rm -rf src)")
        self.assertBlocked('echo "$(git push -f)"')
        self.assertBlocked("echo `rm -rf src`")
        self.assertBlocked("diff <(rm -rf src) <(ls)")
        self.assertBlocked("bash <<'EOF'\nrm -rf src\nEOF")
        self.assertBlocked("bash <<< 'rm -rf src'")
        self.assertBlocked("cat <<EOF\n$(rm -rf src)\nEOF")
        self.assertBlocked("timeout 5 env A=1 nohup rm -rf src")


class ClaudeProcessTest(GuardTestCase):
    def test_blocks_claude(self):
        self.assertBlocked("claude -p 'привет'")
        self.assertBlocked("/usr/local/bin/claude --resume")
        self.assertBlocked("npx @anthropic-ai/claude-code")
        self.assertBlocked("npx -y @anthropic-ai/claude-code -p x")
        self.assertBlocked("pnpm dlx @anthropic-ai/claude-code")
        self.assertBlocked("node ~/.npm/claude-code/cli.js")
        self.assertBlocked("env FOO=1 claude")
        self.assertBlocked("timeout 30 claude -p x")
        self.assertBlocked('sh -c "claude -p x"')
        self.assertBlocked("echo $(claude -p x)")
        self.assertBlocked("echo hi | claude -p")

    def test_mentions_are_allowed(self):
        self.assertAllowed("npm install -g @anthropic-ai/claude-code")
        self.assertAllowed("cat CLAUDE.md")
        self.assertAllowed("ls .claude/rules")


class ShellWritesTest(GuardTestCase):
    def test_redirects_into_project_are_blocked(self):
        self.assertBlocked("echo x > src/main/kotlin/Main.kt")
        self.assertBlocked("echo x >> CLAUDE.md")
        self.assertBlocked("printf x >| docs/DECISIONS.md")
        self.assertBlocked("cmd &> src/log.txt")
        self.assertBlocked("cat > src/A.kt <<'EOF'\nclass A\nEOF")
        self.assertBlocked("cat <<EOF > src/A.kt\nclass A\nEOF")
        self.assertBlocked("echo x 1> .claude/settings.json")
        self.assertBlocked("echo x > $OUT")
        self.assertBlocked('echo x > "$CLAUDE_PROJECT_DIR/src/A.kt"'.replace(
            "$CLAUDE_PROJECT_DIR", self.project))

    def test_cd_does_not_hide_project_paths(self):
        self.assertBlocked("cd /tmp; echo x > src/A.kt")
        self.assertBlocked("(cd /tmp) && echo x > src/A.kt")

    def test_allowed_redirects(self):
        self.assertAllowed("ls > /dev/null 2>&1")
        self.assertAllowed("echo x 2>/dev/null")
        self.assertAllowed("./gradlew build > build/log.txt")
        self.assertAllowed("echo x > core/build/out.txt")
        self.assertAllowed("echo x > /tmp/out.txt")
        self.assertAllowed('echo x > "$TMPDIR/out.txt"')
        self.assertAllowed("sort < src/main/kotlin/Main.kt")

    def test_tee_sed_perl(self):
        self.assertBlocked("echo x | tee src/A.kt")
        self.assertBlocked("echo x | tee -a /tmp/x CLAUDE.md")
        self.assertAllowed("echo x | tee /tmp/x")
        self.assertBlocked("sed -i '' 's/a/b/' src/A.kt")
        self.assertBlocked("sed -i.bak -e 's/a/b/' src/A.kt")
        self.assertBlocked("sed --in-place 's/a/b/' CLAUDE.md")
        self.assertAllowed("sed -n 's/a/b/p' src/A.kt")
        self.assertAllowed("sed -i '' 's/a/b/' /tmp/x")
        self.assertBlocked("perl -pi -e 's/a/b/' src/A.kt")
        self.assertAllowed("perl -ne 'print' src/A.kt")


class FailClosedTest(GuardTestCase):
    def test_unparseable_command_is_blocked(self):
        self.assertBlocked("echo 'незакрытая")
        self.assertBlocked('echo "$(ls')

    def test_hook_protocol(self):
        script = os.path.join(HOOKS_DIR, "bash_guard.py")
        env = dict(os.environ, CLAUDE_PROJECT_DIR=self.project)

        def run(payload):
            out = subprocess.run([sys.executable, script], input=json.dumps(payload),
                                 capture_output=True, text=True, env=env, check=True)
            return out.stdout.strip()

        denied = json.loads(run({"tool_name": "Bash", "cwd": self.project,
                                 "tool_input": {"command": "git reset --hard"}}))
        self.assertEqual("deny", denied["hookSpecificOutput"]["permissionDecision"])
        self.assertEqual("", run({"tool_name": "Bash", "cwd": self.project,
                                  "tool_input": {"command": "git status"}}))
        self.assertEqual("", run({"tool_name": "Read", "tool_input": {}}))
        broken = subprocess.run([sys.executable, script], input="не json",
                                capture_output=True, text=True, env=env)
        self.assertIn('"deny"', broken.stdout)


if __name__ == "__main__":
    unittest.main()
