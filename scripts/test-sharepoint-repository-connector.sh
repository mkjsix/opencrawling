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

# ==============================================================================
# OpenCrawling - SharePoint Repository Connector Integration Test Script
#
# Description:
#   Starts the Graph mock (oc-sharepoint-repository-connector/graph-mock), an
#   Entra ID + Microsoft Graph emulator, on a local port and runs the Maven test
#   suite of oc-sharepoint-repository-connector with GRAPH_MOCK_URL set, so the
#   connector scenarios (SharePointMockServerTest) run over real HTTP next to the
#   MockWebServer unit tests. SharePoint Online is a cloud service: this script
#   never contacts a real tenant.
# ==============================================================================

set -euo pipefail

# Configuration
GRAPH_MOCK_PORT="${GRAPH_MOCK_PORT:-18180}"
GRAPH_MOCK_URL="http://localhost:${GRAPH_MOCK_PORT}"
TIMEOUT="${TIMEOUT:-30}"

# Terminal Formatting
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

log_info() { echo -e "${BLUE}[INFO]${NC} $1"; }
log_success() { echo -e "${GREEN}[SUCCESS]${NC} $1"; }
log_warn() { echo -e "${YELLOW}[WARN]${NC} $1"; }
log_error() { echo -e "${RED}[ERROR]${NC} $1"; }

echo -e "${YELLOW}=== Starting OpenCrawling SharePoint Repository Connector Test Suite ===${NC}"

# Switch to project root directory
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR/.."
log_info "Working directory set to: $(pwd)"

# 1. Dependency Checks
log_info "Verifying required tools (curl, python3, mvn)..."
command -v curl >/dev/null 2>&1 || { log_error "curl is required but not installed."; exit 1; }
command -v python3 >/dev/null 2>&1 || { log_error "python3 is required but not installed."; exit 1; }
command -v mvn >/dev/null 2>&1 || { log_error "Maven (mvn) is required but not installed."; exit 1; }

# 2. Start the Graph mock (stopped by its own PID on exit)
LOG_DIR="oc-sharepoint-repository-connector/target"
mkdir -p "${LOG_DIR}"
if curl -s -o /dev/null --max-time 2 "${GRAPH_MOCK_URL}/mock/stats" 2>/dev/null; then
  log_error "Something already answers on port ${GRAPH_MOCK_PORT}; set GRAPH_MOCK_PORT to a free port."
  exit 1
fi
log_info "Starting the Graph mock on ${GRAPH_MOCK_URL} (log: ${LOG_DIR}/graph-mock.log)..."
GRAPH_PORT="${GRAPH_MOCK_PORT}" GRAPH_QUIET=true python3 oc-sharepoint-repository-connector/graph-mock/graph_mock.py \
  >"${LOG_DIR}/graph-mock.log" 2>&1 &
MOCK_PID=$!
cleanup() {
  kill "${MOCK_PID}" 2>/dev/null || true
  wait "${MOCK_PID}" 2>/dev/null || true
}
trap cleanup EXIT

elapsed=0
until curl -sf -o /dev/null --max-time 2 "${GRAPH_MOCK_URL}/mock/stats" 2>/dev/null; do
  if ! kill -0 "${MOCK_PID}" 2>/dev/null || [ $elapsed -ge "$TIMEOUT" ]; then
    log_error "The Graph mock did not start."
    cat "${LOG_DIR}/graph-mock.log" || true
    exit 1
  fi
  sleep 1
  elapsed=$((elapsed + 1))
done
log_success "Graph mock is up: $(curl -s "${GRAPH_MOCK_URL}/mock/stats" | tr -d '\n' | cut -c1-120)"

# 3. Run Maven Test Suite
log_info "Executing Maven test suite for oc-sharepoint-repository-connector (GRAPH_MOCK_URL=${GRAPH_MOCK_URL})..."
GRAPH_MOCK_URL="${GRAPH_MOCK_URL}" mvn test -pl oc-sharepoint-repository-connector -am ${MAVEN_ARGS:-}

log_success "All SharePoint Repository Connector tests passed successfully!"
