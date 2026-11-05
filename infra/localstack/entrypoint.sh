#!/bin/sh
set -u

# Community LocalStack does not persist S3 state. Save objects before stopping
# its runtime and restore them in the ready hook; this is for local development.
printf 'starting\n' > /tmp/fixflow-s3-ready
docker-entrypoint.sh "$@" &
runtime_pid=$!

shutdown() {
    trap '' TERM INT
    if grep -qx ready /tmp/fixflow-s3-ready; then
        mkdir -p /var/lib/localstack/fixflow-attachments
        awslocal s3 sync "s3://${S3_BUCKET:-fixflow-attachments}" \
            /var/lib/localstack/fixflow-attachments --delete --only-show-errors ||
            printf 'Falha ao salvar o snapshot local dos anexos.\n' >&2
    fi
    kill -TERM "$runtime_pid" 2>/dev/null || true
    wait "$runtime_pid"
    exit "$?"
}
trap shutdown TERM INT
wait "$runtime_pid"
