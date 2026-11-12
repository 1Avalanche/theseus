#!/usr/bin/env python3
"""Предпросмотр неизменяемого кандидата перед GitHub Environment approval."""

import json
import os
from pathlib import Path
import sys

import deploy_handler as h


def preview():
    repository = os.environ['GITHUB_REPOSITORY']
    owner_id = os.environ['GITHUB_REPOSITORY_OWNER_ID']
    h.require(repository == '1Avalanche/theseus' and
              os.environ['GITHUB_ACTOR_ID'] == owner_id and
              os.environ['GITHUB_TRIGGERING_ACTOR'] == repository.split('/')[0] and
              os.environ['GITHUB_REF'] == 'refs/heads/main' and
              os.environ['GITHUB_EVENT_NAME'] == 'workflow_dispatch',
              'Разрешение запускает только владелец исходного репозитория из main')
    raw_id = os.environ['BUILD_RUN_ID']
    h.require(raw_id.isascii() and raw_id.isdigit() and 0 < len(raw_id) <= 20 and int(raw_id) > 0,
              'Нужен числовой ID запуска Build release')
    stand = os.environ['STAND']
    h.require(stand in ('test', 'prod'), 'Неизвестный стенд')
    config = {'repository': repository, 'registry': 'ghcr.io/' + repository.split('/')[0].lower(),
              'config_version': '2'}
    identity = {'repository_id': int(os.environ['GITHUB_REPOSITORY_ID']), 'owner_id': int(owner_id)}
    release, request = h.load_candidate(config, identity, int(raw_id), os.environ['GH_TOKEN'])
    encoded = json.dumps(request, separators=(',', ':'))
    # Значения проверены; исходный ввод не попадает в команды или Markdown.
    with Path(os.environ['GITHUB_OUTPUT']).open('a') as stream:
        stream.write('request=' + encoded + '\n')
    summary = ('Разрешение релиза для **' + stand + '**\n\n' +
               'Сборка: https://github.com/' + repository + '/actions/runs/' + str(int(raw_id)) + '\n\n' +
               '```json\n' + json.dumps(release, indent=2) + '\n```\n\n' +
               'Artifact ID: ' + str(request['artifact_id']) + '\n\n' +
               'SHA-256 ZIP: `' + request['artifact_digest'] + '`\n\n' +
               'После approval сервер проверит этот артефакт повторно. Деплой запускается отдельно.\n')
    with Path(os.environ['GITHUB_STEP_SUMMARY']).open('a') as stream:
        stream.write(summary)


if __name__ == '__main__':
    try:
        preview()
    except Exception:
        print('Отказ: не удалось проверить кандидата доверенной сборки', file=sys.stderr)
        sys.exit(1)
