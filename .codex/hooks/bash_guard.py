#!/usr/bin/env python3
"""
PreToolUse-хук для инструмента Bash.

Блокирует:
  * деструктивные команды (rm -r вне build/ и временных каталогов, git reset --hard,
    git push --force, docker compose down -v, DROP/TRUNCATE через psql, flyway clean и т. п.);
  * запуск внешних процессов Claude (claude, npx @anthropic-ai/claude-code, ...);
  * запись в файлы проекта через shell: редиректы >, >>, &>, а также tee, sed -i, perl -i.

Команда разбирается лексером, близким к bash: учитываются кавычки, экранирование и
комментарии, тела heredoc вырезаются. Поэтому упоминание опасной команды внутри строки,
grep-паттерна или документа не считается её вызовом. То, что shell действительно исполнит, —
$(...), `...`, <(...), bash -c '...', eval, heredoc, переданный интерпретатору shell, —
проверяется рекурсивно.

Политика fail-closed: команда, которую не удалось разобрать, блокируется.
Python 3.9+ без внешних зависимостей.
"""
import json
import os
import re
import sys

# Каталоги внутри проекта, куда shell может писать и где можно удалять рекурсивно.
EXEMPT_DIRS = {"build", ".gradle", ".kotlin", "out"}
DEVICE_TARGETS = {"/dev/null", "/dev/stdout", "/dev/stderr", "/dev/tty"}
TEMP_ROOTS = ("/tmp", "/private/tmp", "/var/folders", "/private/var/folders")
MAX_DEPTH = 8

SHELLS = {"bash", "sh", "zsh", "dash", "ksh"}
# Служебные слова, после которых начинается настоящая команда.
LEADING_KEYWORDS = {"!", "{", "}", "then", "do", "else", "elif", "if", "while", "until", "time"}
# Конструкции, которые сами не запускают программу.
NON_COMMANDS = {"for", "case", "select", "function", "in", "fi", "done", "esac", "[[", "]]", "[", "test"}

# Длинные операторы — раньше коротких.
OPERATORS = ["&>>", "<<<", "<<-", "&&", "||", ";;", "|&", ">>", ">|", "<<", "<&", ">&", "&>",
             ";", "|", "&", "(", ")", "<", ">"]
REDIRECT_OPS = {op for op in OPERATORS if "<" in op or ">" in op}
WRITE_OPS = {">", ">>", ">|", "&>", "&>>", ">&"}

ASSIGNMENT = re.compile(r"^[A-Za-z_][A-Za-z0-9_]*(\[[^]]*\])?\+?=")
SQL_DESTRUCTIVE = re.compile(r"\b(drop\s+(database|schema|table|owned|role|user)|truncate)\b", re.IGNORECASE)


class ParseError(Exception):
    pass


class Word:
    """Слово команды: text — значение после снятия кавычек; dynamic — содержит раскрытие $... или `...`."""
    __slots__ = ("text", "quoted", "dynamic")

    def __init__(self, text, quoted=False, dynamic=False):
        self.text = text
        self.quoted = quoted
        self.dynamic = dynamic

    def __repr__(self):
        return "Word(%r)" % self.text


class Command:
    __slots__ = ("words", "redirects", "heredocs")

    def __init__(self):
        self.words = []
        self.redirects = []   # [(оператор, Word цели)]
        self.heredocs = []    # тела heredoc (данные, если команда — не интерпретатор shell)


def decode_ansi_c(raw):
    """Раскрывает экранирование внутри $'...'."""
    simple = {"n": "\n", "t": "\t", "r": "\r", "a": "\a", "b": "\b", "e": "\x1b", "E": "\x1b",
              "f": "\f", "v": "\v", "\\": "\\", "'": "'", '"': '"', "?": "?"}

    def repl(m):
        s = m.group(0)
        if s[1] in "xX":
            return chr(int(s[2:], 16))
        if s[1] in "uU":
            return chr(int(s[2:], 16))
        if s[1].isdigit():
            return chr(int(s[1:], 8))
        return simple.get(s[1], s)

    return re.sub(r"\\(x[0-9A-Fa-f]{1,2}|u[0-9A-Fa-f]{1,4}|U[0-9A-Fa-f]{1,8}|[0-7]{1,3}|.)", repl, raw)


class Lexer:
    """Разбивает скрипт на простые команды; собирает исполняемые вложения в subscripts."""

    def __init__(self, src):
        self.s = src
        self.n = len(src)
        self.i = 0
        self.commands = []
        self.subscripts = []
        self.cur = Command()
        self.buf = None
        self.quoted = False
        self.dynamic = False
        self.pending_redirect = None
        self.pending_heredocs = []

    def parse(self):
        s = self.s
        while self.i < self.n:
            c = s[self.i]
            if c in " \t":
                self._end_word()
                self.i += 1
            elif c == "\n":
                self._end_command()
                self.i += 1
                self._read_heredoc_bodies()
            elif c == "#" and self.buf is None:
                j = s.find("\n", self.i)
                self.i = self.n if j < 0 else j
            elif c == "\\":
                if s.startswith("\n", self.i + 1):
                    self.i += 2
                else:
                    self._add(s[self.i + 1:self.i + 2], quoted=True)
                    self.i += 2
            elif c == "'":
                j = s.find("'", self.i + 1)
                if j < 0:
                    raise ParseError("незакрытая одинарная кавычка")
                self._add(s[self.i + 1:j], quoted=True)
                self.i = j + 1
            elif c == '"':
                self.i = self._read_double_quoted(self.i + 1)
            elif c == "`":
                j = self._backtick_end(self.i + 1)
                self.subscripts.append(s[self.i + 1:j])
                self._add("`…`", dynamic=True)
                self.i = j + 1
            elif c == "$":
                self.i = self._read_dollar(self.i)
            elif c in "<>" and s.startswith("(", self.i + 1) and self.buf is None:
                j = self._paren_end(self.i + 2)
                self.subscripts.append(s[self.i + 2:j])
                self._add("<(…)", dynamic=True)
                self.i = j + 1
            else:
                op = self._operator_at(self.i)
                if op:
                    self._operator(op)
                    self.i += len(op)
                else:
                    self._add(c)
                    self.i += 1
        self._end_command()
        self._read_heredoc_bodies()
        return self.commands, self.subscripts

    # --- слова и команды ---

    def _add(self, text, quoted=False, dynamic=False):
        if self.buf is None:
            self.buf = []
        self.buf.append(text)
        self.quoted = self.quoted or quoted
        self.dynamic = self.dynamic or dynamic

    def _end_word(self):
        if self.buf is None:
            return
        word = Word("".join(self.buf), self.quoted, self.dynamic)
        self.buf = None
        self.quoted = self.dynamic = False
        if self.pending_redirect:
            op = self.pending_redirect
            self.pending_redirect = None
            self.cur.redirects.append((op, word))
            if op in ("<<", "<<-"):
                self.pending_heredocs.append((word.text, op == "<<-", word.quoted, self.cur))
        else:
            self.cur.words.append(word)

    def _end_command(self):
        self._end_word()
        if self.pending_redirect:
            raise ParseError("у редиректа %s нет цели" % self.pending_redirect)
        if self.cur.words or self.cur.redirects:
            self.commands.append(self.cur)
        self.cur = Command()

    def _operator_at(self, i):
        for op in OPERATORS:
            if self.s.startswith(op, i):
                return op
        return None

    def _operator(self, op):
        if op in REDIRECT_OPS:
            if (self.buf is not None and not self.quoted and not self.dynamic
                    and "".join(self.buf).isdigit()):
                self.buf = None  # номер дескриптора: 2>, 1>>
            else:
                self._end_word()
            if self.pending_redirect:
                raise ParseError("два редиректа подряд")
            self.pending_redirect = op
        else:
            self._end_command()

    def _read_heredoc_bodies(self):
        pending, self.pending_heredocs = self.pending_heredocs, []
        for delim, strip_tabs, quoted, cmd in pending:
            lines = []
            while self.i < self.n:
                j = self.s.find("\n", self.i)
                end = self.n if j < 0 else j
                line = self.s[self.i:end]
                self.i = self.n if j < 0 else j + 1
                if (line.lstrip("\t") if strip_tabs else line) == delim:
                    break
                lines.append(line)
            body = "\n".join(lines)
            cmd.heredocs.append(body)
            if not quoted:
                # В heredoc без кавычек у разделителя shell исполняет $(...) и `...`.
                self.subscripts.extend(scan_expansions(body))

    # --- кавычки и раскрытия ---

    def _read_double_quoted(self, i):
        s = self.s
        self._add("", quoted=True)
        while i < self.n:
            c = s[i]
            if c == '"':
                return i + 1
            if c == "\\" and i + 1 < self.n and s[i + 1] in '$`"\\\n':
                if s[i + 1] != "\n":
                    self._add(s[i + 1])
                i += 2
            elif c == "$":
                i = self._read_dollar(i, in_dquote=True)
            elif c == "`":
                j = self._backtick_end(i + 1)
                self.subscripts.append(s[i + 1:j])
                self._add("`…`", dynamic=True)
                i = j + 1
            else:
                self._add(c)
                i += 1
        raise ParseError("незакрытая двойная кавычка")

    def _read_dollar(self, i, in_dquote=False):
        s = self.s
        if s.startswith("((", i + 1):
            j = self._paren_end(i + 2)
            self._add("$((…))", dynamic=True)
            return j + 1
        if s.startswith("(", i + 1):
            j = self._paren_end(i + 2)
            self.subscripts.append(s[i + 2:j])
            self._add("$(…)", dynamic=True)
            return j + 1
        if s.startswith("{", i + 1):
            j = s.find("}", i + 2)
            if j < 0:
                raise ParseError("незакрытая ${")
            self._add(s[i:j + 1], dynamic=True)
            return j + 1
        if s.startswith("'", i + 1) and not in_dquote:
            j = i + 2
            while j < self.n and s[j] != "'":
                j += 2 if s[j] == "\\" else 1
            if j >= self.n:
                raise ParseError("незакрытая $'")
            self._add(decode_ansi_c(s[i + 2:j]), quoted=True)
            return j + 1
        m = re.match(r"[A-Za-z_][A-Za-z0-9_]*|[0-9@*#?$!-]", s[i + 1:])
        if m:
            self._add("$" + m.group(0), dynamic=True)
            return i + 1 + len(m.group(0))
        self._add("$")
        return i + 1

    def _heredoc_delimiter(self, i):
        """Разбирает разделитель после '<<' на позиции i: (разделитель, убирать_табы, позиция после)."""
        s = self.s
        strip_tabs = s.startswith("-", i)
        if strip_tabs:
            i += 1
        while i < self.n and s[i] in " \t":
            i += 1
        out = []
        while i < self.n and s[i] not in " \t\n;|&<>()":
            c = s[i]
            if c in "'\"":
                j = s.find(c, i + 1)
                if j < 0:
                    raise ParseError("незакрытая кавычка в разделителе heredoc")
                out.append(s[i + 1:j])
                i = j + 1
            elif c == "\\":
                out.append(s[i + 1:i + 2])
                i += 2
            else:
                out.append(c)
                i += 1
        return "".join(out), strip_tabs, i

    def _skip_heredoc_bodies(self, i, pending):
        """i — начало строки после команды с heredoc; возвращает позицию после всех тел."""
        s = self.s
        for delim, strip_tabs in pending:
            while i < self.n:
                j = s.find("\n", i)
                end = self.n if j < 0 else j
                line = s[i:end]
                i = self.n if j < 0 else j + 1
                if (line.lstrip("\t") if strip_tabs else line) == delim:
                    break
        return i

    def _paren_end(self, i):
        """Индекс ')' парной к '(' прямо перед позицией i. Тела heredoc внутри пропускаются."""
        s = self.s
        depth = 1
        heredocs = []
        while i < self.n:
            c = s[i]
            if c == "\\":
                i += 2
                continue
            if c == "<" and s.startswith("<<", i) and not s.startswith("<<<", i):
                delim, strip_tabs, i = self._heredoc_delimiter(i + 2)
                heredocs.append((delim, strip_tabs))
                continue
            if c == "\n" and heredocs:
                i = self._skip_heredoc_bodies(i + 1, heredocs)
                heredocs = []
                continue
            if c == "'":
                j = s.find("'", i + 1)
                if j < 0:
                    raise ParseError("незакрытая одинарная кавычка")
                i = j + 1
                continue
            if c == '"':
                i = self._skip_double_quoted(i + 1)
                continue
            if c == "`":
                i = self._backtick_end(i + 1) + 1
                continue
            if c == "(":
                depth += 1
            elif c == ")":
                depth -= 1
                if depth == 0:
                    return i
            i += 1
        raise ParseError("незакрытая скобка")

    def _skip_double_quoted(self, i):
        s = self.s
        while i < self.n:
            c = s[i]
            if c == "\\":
                i += 2
            elif c == '"':
                return i + 1
            elif c == "$" and s.startswith("(", i + 1):
                i = self._paren_end(i + 2) + 1
            elif c == "`":
                i = self._backtick_end(i + 1) + 1
            else:
                i += 1
        raise ParseError("незакрытая двойная кавычка")

    def _backtick_end(self, i):
        s = self.s
        while i < self.n:
            if s[i] == "\\":
                i += 2
            elif s[i] == "`":
                return i
            else:
                i += 1
        raise ParseError("незакрытая обратная кавычка")


def scan_expansions(text):
    """Достаёт $(...) и `...` из текста, где кавычки не действуют (тело heredoc без кавычек)."""
    lexer = Lexer(text)
    found = []
    i = 0
    while i < len(text):
        c = text[i]
        if c == "\\":
            i += 2
        elif c == "$" and text.startswith("(", i + 1) and not text.startswith("((", i + 1):
            j = lexer._paren_end(i + 2)
            found.append(text[i + 2:j])
            i = j + 1
        elif c == "`":
            j = lexer._backtick_end(i + 1)
            found.append(text[i + 1:j])
            i = j + 1
        else:
            i += 1
    return found


# --- обёртки, которые запускают другую команду ---

def skip_options(words, with_arg=(), positional=0):
    i = 0
    while i < len(words):
        t = words[i].text
        if t == "--":
            i += 1
            break
        if not t.startswith("-") or t == "-":
            break
        i += 2 if t in with_arg else 1
    return words[i + positional:]


def unwrap_command(words):
    # command -v / -V только ищет программу, а не запускает её.
    if any(w.text in ("-v", "-V") for w in words[:1]):
        return []
    return skip_options(words)


WRAPPERS = {
    "env": lambda w: skip_options(w, {"-u", "--unset", "-C", "--chdir"}),
    "nohup": skip_options,
    "command": unwrap_command,
    "builtin": skip_options,
    "exec": lambda w: skip_options(w, {"-a"}),
    "nice": lambda w: skip_options(w, {"-n"}),
    "stdbuf": skip_options,
    "caffeinate": lambda w: skip_options(w, {"-t", "-w"}),
    "timeout": lambda w: skip_options(w, {"-s", "--signal", "-k", "--kill-after"}, positional=1),
    "gtimeout": lambda w: skip_options(w, {"-s", "--signal", "-k", "--kill-after"}, positional=1),
    "xargs": lambda w: skip_options(w, {"-I", "-L", "-n", "-P", "-s", "-d", "-E", "-a",
                                        "--max-args", "--max-procs", "--delimiter", "--arg-file"}),
    "watch": lambda w: skip_options(w, {"-n", "--interval"}),
}


def short_flags(args):
    """Буквы всех коротких опций: ['-fd', '-x'] -> 'fdx'."""
    return "".join(a[1:] for a in args if a.startswith("-") and not a.startswith("--") and len(a) > 1)


def is_claude(name, args):
    def mentions(a):
        return a == "claude" or "claude-code" in a or a.startswith("@anthropic-ai/claude")

    if name in ("claude", "claude-code"):
        return True
    if name in ("npx", "bunx", "pnpx"):
        return any(mentions(a) for a in args)
    if name in ("npm", "pnpm", "yarn", "bun") and args[:1] and args[0] in ("exec", "x", "dlx"):
        return any(mentions(a) for a in args)
    if name in ("node", "deno"):
        return any("claude-code" in a for a in args)
    return False


def git_reason(args):
    i = 0
    while i < len(args) and args[i].startswith("-"):
        i += 2 if args[i] in ("-C", "-c", "--git-dir", "--work-tree", "--namespace") else 1
    if i >= len(args):
        return None
    sub, rest = args[i], args[i + 1:]
    flags = short_flags(rest)
    if sub == "reset" and "--hard" in rest:
        return "git reset --hard"
    if sub == "clean" and ("f" in flags or "--force" in rest) and not ("n" in flags or "--dry-run" in rest):
        return "git clean -f"
    if sub == "push":
        if ("f" in flags or "d" in flags
                or any(a.startswith(("--force", "--delete", "--mirror", "--prune")) for a in rest)
                or any(a.startswith(("+", ":")) for a in rest)):
            return "git push с перезаписью или удалением на удалённом репозитории"
    if sub == "checkout" and ("f" in flags or "--force" in rest or "." in rest
                              or ("--" in rest and rest.index("--") < len(rest) - 1)):
        return "git checkout, отбрасывающий незакоммиченные изменения"
    if sub == "restore":
        staged_only = ("--staged" in rest or "S" in flags) and not ("--worktree" in rest or "W" in flags)
        if not staged_only:
            return "git restore рабочей копии (отбрасывает изменения)"
    if sub == "switch" and ("f" in flags or "--force" in rest or "--discard-changes" in rest):
        return "git switch --discard-changes"
    if sub == "branch" and ("D" in flags or (("d" in flags or "--delete" in rest)
                                             and ("f" in flags or "--force" in rest))):
        return "git branch -D"
    if sub == "stash" and rest[:1] and rest[0] in ("drop", "clear"):
        return "git stash drop/clear"
    if sub in ("filter-branch", "filter-repo"):
        return "git " + sub
    if sub == "reflog" and rest[:1] and rest[0] in ("expire", "delete"):
        return "git reflog " + rest[0]
    if sub == "gc" and any(a.startswith("--prune") for a in rest):
        return "git gc --prune"
    if sub == "update-ref" and "-d" in rest:
        return "git update-ref -d"
    if sub == "rm" and ("f" in flags or "--force" in rest):
        return "git rm --force"
    return None


def first_positional(args, with_arg):
    i = 0
    while i < len(args) and args[i].startswith("-"):
        i += 2 if args[i] in with_arg else 1
    return i


def compose_reason(args):
    i = first_positional(args, {"-f", "--file", "-p", "--project-name", "--env-file",
                                "--profile", "--project-directory"})
    if i >= len(args):
        return None
    sub, rest = args[i], args[i + 1:]
    if sub in ("down", "rm") and ("v" in short_flags(rest) or "--volumes" in rest):
        return "docker compose %s -v (удаляет тома с данными)" % sub
    return None


def docker_reason(args):
    i = first_positional(args, {"--context", "-c", "-H", "--host", "--config", "-l", "--log-level"})
    if i >= len(args):
        return None
    sub, rest = args[i], args[i + 1:]
    if sub == "compose":
        return compose_reason(rest)
    if sub == "system" and "prune" in rest:
        return "docker system prune"
    if sub == "volume" and rest[:1] and rest[0] in ("rm", "remove", "prune"):
        return "docker volume " + rest[0]
    if sub == "rm" and ("v" in short_flags(rest) or "--volumes" in rest):
        return "docker rm -v (удаляет тома)"
    return None


def psql_reason(args):
    for idx, a in enumerate(args):
        sql = None
        if a in ("-c", "--command") and idx + 1 < len(args):
            sql = args[idx + 1]
        elif a.startswith("--command="):
            sql = a[len("--command="):]
        elif a.startswith("-c") and len(a) > 2:
            sql = a[2:]
        if sql and SQL_DESTRUCTIVE.search(sql):
            return "psql с DROP/TRUNCATE"
    return None


def destructive_reason(name, args):
    if name == "git":
        return git_reason(args)
    if name == "docker":
        return docker_reason(args)
    if name == "docker-compose":
        return compose_reason(args)
    if name in ("dropdb", "dropuser"):
        return name
    if name in ("shutdown", "reboot", "halt", "poweroff"):
        return name
    if name.startswith(("mkfs", "newfs")) or name in ("fdisk", "sfdisk", "parted", "wipefs",
                                                       "shred", "srm", "truncate"):
        return name
    if name == "diskutil" and args[:1] and args[0].lower().startswith(
            ("erase", "partition", "zero", "random", "secureerase", "reformat")):
        return "diskutil " + args[0]
    if name == "dd" and any(a.startswith("of=") and a != "of=/dev/null" for a in args):
        return "dd of=…"
    if name == "kill" and "-1" in args[1:]:
        return "kill -1 (все процессы пользователя)"
    if name == "crontab" and "-r" in args:
        return "crontab -r"
    if name in ("gradle", "gradlew") and any("flywayclean" in a.lower() for a in args):
        return "flywayClean"
    return None


class Analyzer:
    def __init__(self, project_dir, cwd):
        self.project = os.path.realpath(project_dir)
        self.cwds = [os.path.realpath(cwd or project_dir)]
        tmp = [os.path.realpath(r) for r in TEMP_ROOTS]
        if os.environ.get("TMPDIR"):
            tmp.append(os.path.realpath(os.environ["TMPDIR"]))
        self.temp_roots = sorted(set(tmp))
        self.reassigned = set()
        self.reasons = []

    def deny(self, reason):
        self.reasons.append(reason)

    def check_script(self, script, depth=0):
        if depth > MAX_DEPTH:
            self.deny("слишком глубокая вложенность команд")
            return
        commands, subscripts = Lexer(script).parse()
        for sub in subscripts:
            self.check_script(sub, depth + 1)
        for cmd in commands:
            self._check_redirects(cmd.redirects)
            self._check_words(cmd.words, cmd, depth)

    # --- команды ---

    def _check_words(self, words, cmd, depth):
        words = self._unwrap(words)
        if not words:
            return
        head = words[0]
        name = os.path.basename(head.text)
        if head.dynamic and re.search(r"[$`…]", name):
            # $X/gradlew допустим, а $CMD или $(…) — нет: неизвестно, что запустится.
            self.deny("имя команды вычисляется динамически: %s" % head.text)
            return
        wargs = words[1:]
        args = [w.text for w in wargs]
        if name in NON_COMMANDS:
            return
        if name in ("cd", "pushd"):
            self._cd(wargs)
            return
        if name in SHELLS:
            self._check_shell(wargs, cmd, depth)
            return
        if name == "eval":
            self.check_script(" ".join(args), depth + 1)
            return
        if is_claude(name, args):
            self.deny("запуск внешнего процесса Claude: %s" % " ".join([head.text] + args)[:120])
            return
        reason = destructive_reason(name, args)
        if reason:
            self.deny("деструктивная команда: " + reason)
        if name == "rm":
            self._check_rm(wargs)
        if name == "find":
            self._check_find(wargs, depth)
        for idx, a in enumerate(args):
            base = os.path.basename(a)
            if base == "psql":
                reason = psql_reason(args[idx + 1:])
                if reason:
                    self.deny("деструктивная команда: " + reason)
            if base == "flyway" and "clean" in args[idx + 1:]:
                self.deny("деструктивная команда: flyway clean")
        if name == "psql":
            reason = psql_reason(args)
            if reason:
                self.deny("деструктивная команда: " + reason)
        if name == "flyway" and "clean" in args:
            self.deny("деструктивная команда: flyway clean")
        self._check_file_writers(name, wargs)

    def _unwrap(self, words):
        while words:
            i = 0
            while i < len(words) and (words[i].text in LEADING_KEYWORDS or ASSIGNMENT.match(words[i].text)):
                i += 1
            words = words[i:]
            if not words:
                return []
            name = os.path.basename(words[0].text)
            if name in ("sudo", "doas"):
                self.deny("%s запрещён" % name)
                return []
            if name in WRAPPERS and not words[0].dynamic:
                words = WRAPPERS[name](words[1:])
                continue
            return words
        return []

    def _check_shell(self, wargs, cmd, depth):
        i = 0
        while i < len(wargs):
            t = wargs[i].text
            if t == "--":
                i += 1
                break
            if t.startswith(("-", "+")) and len(t) > 1:
                if t in ("-o", "+o", "-O", "+O", "--rcfile", "--init-file"):
                    i += 2
                    continue
                if not t.startswith("--") and "c" in t[1:]:
                    if i + 1 < len(wargs):
                        self.check_script(wargs[i + 1].text, depth + 1)
                    return
                i += 1
                continue
            break
        if i < len(wargs):
            return  # запуск файла-скрипта: содержимое не проверяем
        # Скрипт приходит из stdin: тело heredoc или here-string исполняются.
        for body in cmd.heredocs:
            self.check_script(body, depth + 1)
        for op, w in cmd.redirects:
            if op == "<<<":
                self.check_script(w.text, depth + 1)

    def _check_find(self, wargs, depth):
        i = 0
        while i < len(wargs):
            t = wargs[i].text
            if t == "-delete":
                self.deny("деструктивная команда: find -delete")
            if t in ("-exec", "-execdir", "-ok", "-okdir"):
                j = i + 1
                inner = []
                while j < len(wargs) and wargs[j].text not in (";", "+"):
                    inner.append(wargs[j])
                    j += 1
                if inner and os.path.basename(inner[0].text) == "rm":
                    self.deny("деструктивная команда: find -exec rm")
                else:
                    self._check_words(inner, Command(), depth + 1)
                i = j
            i += 1

    def _check_rm(self, wargs):
        opts, targets, end = [], [], False
        for w in wargs:
            if not end and w.text == "--":
                end = True
            elif not end and w.text.startswith("-") and w.text != "-":
                opts.append(w.text)
            else:
                targets.append(w)
        flags = short_flags(opts)
        if not ("r" in flags or "R" in flags or "--recursive" in opts):
            return
        if targets and all(self._is_disposable(t) for t in targets):
            return
        self.deny("деструктивная команда: rm -r вне build/ и временных каталогов")

    def _cd(self, wargs):
        targets = [w for w in wargs if not w.text.startswith("-")]
        if targets and targets[0].dynamic:
            return
        dest = targets[0].text if targets else "~"
        new = [os.path.realpath(os.path.join(c, os.path.expanduser(dest))) for c in self.cwds]
        # Старые каталоги не забываем: cd мог выполниться в подоболочке.
        self.cwds = list(dict.fromkeys(self.cwds + new))

    # --- запись в файлы ---

    def _check_redirects(self, redirects):
        for op, w in redirects:
            if op not in WRITE_OPS:
                continue
            if op == ">&" and (w.text.isdigit() or w.text == "-"):
                continue  # дублирование дескриптора: 2>&1
            if self._is_protected(w):
                self.deny("запись в файл проекта через редирект %s %s — используй Edit/Write" % (op, w.text))

    def _check_file_writers(self, name, wargs):
        files = []
        if name == "tee":
            files = [w for w in wargs if not w.text.startswith("-")]
        elif name in ("sed", "gsed"):
            files = self._sed_inplace_files(wargs)
        elif name == "perl":
            files = self._perl_inplace_files(wargs)
        for w in files:
            if self._is_protected(w):
                self.deny("запись в файл проекта через %s %s — используй Edit/Write" % (name, w.text))

    @staticmethod
    def _sed_inplace_files(wargs):
        inplace, script_given, positionals = False, False, []
        i = 0
        while i < len(wargs):
            t = wargs[i].text
            if t == "--":
                positionals += wargs[i + 1:]
                break
            if t in ("-e", "--expression", "-f", "--file"):
                script_given = True
                i += 2
                continue
            if t.startswith(("--expression=", "--file=")):
                script_given = True
            elif t.startswith("--in-place"):
                inplace = True
            elif t.startswith("-") and not t.startswith("--") and len(t) > 1:
                flags = t[1:]
                if "i" in flags:
                    inplace = True
                    # BSD sed: суффикс у -i — отдельный аргумент ('' или .bak).
                    if flags == "i" and i + 1 < len(wargs) and (
                            wargs[i + 1].text == "" or wargs[i + 1].text.startswith(".")):
                        i += 1
                elif flags[-1] in "ef":
                    script_given = True
                    i += 2
                    continue
            else:
                positionals.append(wargs[i])
            i += 1
        if not inplace:
            return []
        return positionals if script_given else positionals[1:]

    @staticmethod
    def _perl_inplace_files(wargs):
        inplace, script_given, positionals = False, False, []
        i = 0
        while i < len(wargs):
            t = wargs[i].text
            if t.startswith("-") and not t.startswith("--") and len(t) > 1:
                if "i" in t[1:]:
                    inplace = True
                if t[-1] in "eE":
                    script_given = True
                    i += 2
                    continue
            else:
                positionals.append(wargs[i])
            i += 1
        if not inplace:
            return []
        return positionals if script_given else positionals[1:]

    # --- пути ---

    def _static_path(self, w):
        """Путь с раскрытыми переменными окружения или None, если до исполнения он неизвестен."""
        text = w.text
        if w.dynamic:
            names = re.findall(r"\$\{?([A-Za-z_][A-Za-z0-9_]*)", text)
            if any(n in self.reassigned for n in names):
                return None  # переменная переопределяется в самой команде
            text = os.path.expandvars(text)
            if re.search(r"[$`…]", text):
                return None
        return text

    def _resolve(self, text):
        text = os.path.expanduser(text)
        return [os.path.realpath(os.path.join(c, text)) for c in self.cwds]

    def _location(self, path):
        if path == self.project or path.startswith(self.project + os.sep):
            for part in os.path.relpath(path, self.project).split(os.sep):
                if part == "src":
                    break
                if part in EXEMPT_DIRS:
                    return "exempt"
            return "project"
        for root in self.temp_roots:
            if path == root:
                return "temp-root"
            if path.startswith(root + os.sep):
                return "temp"
        return "outside"

    def _is_protected(self, w):
        if w.text in DEVICE_TARGETS or w.text.startswith("/dev/fd/"):
            return False
        text = self._static_path(w)
        if text is None:
            return True  # путь неизвестен до исполнения — считаем опасным
        return any(self._location(p) == "project" for p in self._resolve(text))

    def _is_disposable(self, w):
        text = self._static_path(w)
        if text is None:
            return False
        glob = re.search(r"[*?\[]", text)
        if glob:
            # build/* -> каталог build; * -> корень проекта; /tmp/* -> сам /tmp (нельзя).
            prefix = text[:glob.start()]
            text = prefix if prefix.endswith("/") else (os.path.dirname(prefix) or ".")
        return {self._location(p) for p in self._resolve(text)} <= {"exempt", "temp"}


def analyze(command, project_dir, cwd=None):
    analyzer = Analyzer(project_dir, cwd or project_dir)
    # Переменные, которым команда сама присваивает значение: их значение из окружения хука неверно.
    analyzer.reassigned = set(re.findall(r"([A-Za-z_][A-Za-z0-9_]*)\+?=", command))
    analyzer.reassigned |= {m[-1] for m in re.findall(r"\b(for|read|select)\s+(-\S+\s+)*([A-Za-z_]\w*)", command)}
    try:
        analyzer.check_script(command)
    except ParseError as e:
        analyzer.deny("не удалось разобрать команду (%s) — упрости её" % e)
    return list(dict.fromkeys(analyzer.reasons))


def main():
    try:
        data = json.load(sys.stdin)
        if data.get("tool_name") != "Bash":
            return 0
        command = (data.get("tool_input") or {}).get("command") or ""
        project = (os.environ.get("CLAUDE_PROJECT_DIR")
                   or os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))
        reasons = analyze(command, project, data.get("cwd") or project)
    except Exception as e:  # fail-closed: сломанный хук не должен пропускать команды
        reasons = ["внутренняя ошибка хука bash_guard: %r" % e]
    if reasons:
        message = ("Команда заблокирована хуком bash_guard:\n"
                   + "\n".join("- " + r for r in reasons)
                   + "\nФайлы проекта меняй через Edit/Write. Деструктивные операции и запуск "
                     "других экземпляров Claude выполняет только владелец вручную.")
        print(json.dumps({"hookSpecificOutput": {
            "hookEventName": "PreToolUse",
            "permissionDecision": "deny",
            "permissionDecisionReason": message,
        }}, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    sys.exit(main())
