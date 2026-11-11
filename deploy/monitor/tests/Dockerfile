# Проверка unit-файлов и монитора в Linux без Docker socket и внешних оповещений.
FROM ubuntu:22.04
RUN apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
    python3 systemd ca-certificates && rm -rf /var/lib/apt/lists/*
COPY deploy/monitor /source/monitor
RUN mkdir -p /opt/theseus/monitor && touch /opt/theseus/monitor.env
CMD ["sh", "-c", "systemd-analyze verify /source/monitor/theseus-monitor.service /source/monitor/theseus-monitor.timer && python3 -m unittest discover -s /source/monitor/tests -v"]
