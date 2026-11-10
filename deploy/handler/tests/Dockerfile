# Изолированный Linux-прогон SSH/sudo. Docker socket хоста не подключается.
FROM ubuntu:22.04
RUN apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
    python3 openssh-server openssh-client sudo ca-certificates && rm -rf /var/lib/apt/lists/*
COPY deploy /source/deploy
COPY .github/workflows /source/.github/workflows
ENV THESEUS_SSH_INTEGRATION=1
CMD ["python3", "-m", "unittest", "discover", "-s", "/source/deploy/handler/tests", "-v"]
