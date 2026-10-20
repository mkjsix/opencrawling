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
# OpenCrawling - M-Files Repository Connector Integration Test Script
#
# Description:
#   Probes an M-Files Web Service (MFWS) when one is reachable (unauthenticated
#   GET /server/status, read-only) and runs the Maven test suite of
#   oc-mfiles-repository-connector (MockWebServer with MFWS response fixtures).
#   M-Files is a licensed product: this script never starts one and never
#   writes to one. For a read-only check against a real vault, see
#   'Read-only live probe' in oc-mfiles-repository-connector/README.md.
# ==============================================================================

set -euo pipefail

# Configuration
MFILES_URL="${MFILES_URL:-http://localhost/REST}"
TIMEOUT="${TIMEOUT:-10}"

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

echo -e "${YELLOW}=== Starting OpenCrawling M-Files Repository Connector Test Suite ===${NC}"

# Switch to project root directory
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR/.."
log_info "Working directory set to: $(pwd)"

# 1. Dependency Checks
log_info "Verifying required tools (curl, mvn)..."
command -v curl >/dev/null 2>&1 || { log_error "curl is required but not installed."; exit 1; }
command -v mvn >/dev/null 2>&1 || { log_error "Maven (mvn) is required but not installed."; exit 1; }

# 2. Check if a live M-Files Web Service is reachable (read-only, no login)
log_info "Probing M-Files Web Service at ${MFILES_URL}..."
STATUS_CODE=$(curl -s -o /dev/null -w "%{http_code}" --connect-timeout 3 --max-time "${TIMEOUT}" \
  -H "Accept: application/json" "${MFILES_URL}/server/status" 2>/dev/null || true)
if [ "${STATUS_CODE}" = "200" ]; then
  log_success "Live M-Files Web Service is reachable at ${MFILES_URL}!"
  log_info "For a read-only live crawl, see 'Read-only live probe' in oc-mfiles-repository-connector/README.md."
else
  log_warn "No M-Files Web Service is reachable at ${MFILES_URL} (HTTP ${STATUS_CODE:-none})."
  log_info "Will execute the isolated embedded mock HTTP server test suite."
fi

# 3. Run Maven Test Suite
log_info "Executing Maven test suite for oc-mfiles-repository-connector..."
mvn test -pl oc-mfiles-repository-connector -am

log_success "All M-Files Repository Connector tests passed successfully!"
