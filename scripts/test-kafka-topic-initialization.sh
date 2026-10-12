#!/usr/bin/env bash
#
# Copyright © 2026 the original author or authors (piergiorgio@apache.org)
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

# Real Kafka configuration/listener lifecycle test; no database or AI model is started.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR/.."
for command in docker java javac mvn python3 timeout; do
  command -v "$command" >/dev/null || { echo "Required command missing: $command" >&2; exit 1; }
done

EMBEDDING_JAR=oc-embedding-service/target/oc-embedding-service-1.0.0-SNAPSHOT.jar
# Build the dependency classpath, then compile the actual configuration sources below.
mvn -B -pl oc-embedding-service -am package -DskipTests -DskipITs

PROBE_DIR="$(mktemp -d "${TMPDIR:-/tmp}/oc-kafka-initialization.XXXXXX")"
CONTAINER="oc-kafka-initialization-$(basename "$PROBE_DIR" | tr '[:upper:]' '[:lower:]')"
CONTAINER_STARTED=false
cleanup() {
  local result=$?
  if [[ "$CONTAINER_STARTED" == true ]]; then
    if [[ $result -ne 0 ]]; then docker logs "$CONTAINER" >&2 || true; fi
    docker rm -f "$CONTAINER" >/dev/null || true
  fi
  rm -rf "$PROBE_DIR"
}
trap cleanup EXIT

python3 - "$EMBEDDING_JAR" "$PROBE_DIR" <<'PYTHON'
from pathlib import Path
import sys
from zipfile import ZipFile
root = Path(sys.argv[2])
(root / "lib").mkdir()
(root / "classes").mkdir()
with ZipFile(sys.argv[1]) as archive:
    for entry in archive.namelist():
        if entry.startswith("BOOT-INF/lib/") and entry.endswith(".jar"):
            (root / "lib" / Path(entry).name).write_bytes(archive.read(entry))
PYTHON
cat > "$PROBE_DIR/logback.xml" <<'XML'
<configuration>
  <appender name="STDOUT" class="ch.qos.logback.core.ConsoleAppender">
    <encoder><pattern>%level %logger{40} - %msg%n</pattern></encoder>
  </appender>
  <root level="ERROR"><appender-ref ref="STDOUT"/></root>
</configuration>
XML
javac --release 25 -cp "$PROBE_DIR/classes:$PROBE_DIR/lib/*" -d "$PROBE_DIR/classes" \
  oc-runtime/src/main/java/org/opencrawling/runtime/config/KafkaConfig.java \
  oc-embedding-service/src/main/java/org/opencrawling/embedding/config/KafkaConfig.java \
  scripts/kafka/KafkaTopicInitializationProbe.java
PORT="$(python3 - <<'PY'
import socket
with socket.socket() as sock:
    sock.bind(("127.0.0.1", 0))
    print(sock.getsockname()[1])
PY
)"
probe() {
  timeout 240 java -Dlogback.configurationFile="$PROBE_DIR/logback.xml" \
    -cp "$PROBE_DIR/classes:$PROBE_DIR/lib/*" KafkaTopicInitializationProbe "$1" "127.0.0.1:$PORT" "${@:2}"
}
# The first attempt must fail without starting listeners; the next attempt uses the same endpoint.
probe unavailable

start_broker() {
  docker run -d --rm --name "$CONTAINER" --hostname "$CONTAINER" -p "127.0.0.1:$PORT:9092" \
    -e KAFKA_NODE_ID=1 -e KAFKA_PROCESS_ROLES=broker,controller \
    -e "KAFKA_CONTROLLER_QUORUM_VOTERS=1@$CONTAINER:9093" \
    -e KAFKA_LISTENERS=INTERNAL://0.0.0.0:9094,EXTERNAL://0.0.0.0:9092,CONTROLLER://0.0.0.0:9093 \
    -e "KAFKA_ADVERTISED_LISTENERS=INTERNAL://$CONTAINER:9094,EXTERNAL://127.0.0.1:$PORT" \
    -e KAFKA_CONTROLLER_LISTENER_NAMES=CONTROLLER \
    -e KAFKA_LISTENER_SECURITY_PROTOCOL_MAP=INTERNAL:PLAINTEXT,EXTERNAL:PLAINTEXT,CONTROLLER:PLAINTEXT \
    -e KAFKA_INTER_BROKER_LISTENER_NAME=INTERNAL -e KAFKA_NUM_PARTITIONS=1 \
    -e KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR=1 -e KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR=1 \
    -e KAFKA_TRANSACTION_STATE_LOG_MIN_ISR=1 apache/kafka:3.7.0 >/dev/null
  CONTAINER_STARTED=true
  for attempt in $(seq 1 20); do
    if timeout 8 docker exec "$CONTAINER" /opt/kafka/bin/kafka-topics.sh \
        --bootstrap-server localhost:9094 --list >/dev/null 2>&1; then
      return
    fi
    sleep 1
  done
  echo "Kafka broker did not become ready within the bounded startup wait." >&2
  exit 1
}

# Fresh brokers avoid topic-deletion metadata races between independent scenarios.
for configuration in runtime embedding; do
  for scenario in absent one-partition; do
    start_broker
    probe "$scenario" "$configuration"
    docker rm -f "$CONTAINER" >/dev/null
    CONTAINER_STARTED=false
  done
done
echo "Kafka topic initialization tests passed."
