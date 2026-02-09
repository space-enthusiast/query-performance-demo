#!/bin/bash

# =============================================================================
# Load Test Script for Query Performance Demo
# =============================================================================
#
# Usage:
#   ./load_test.sh [scenario]
#
# Scenarios:
#   slow    - Test slow endpoint only (system collapse)
#   fast    - Test fast endpoint only (optimized)
#   mixed   - Test mixed traffic (20% slow, 80% fast)
#   all     - Run all scenarios sequentially
#   web     - Start Locust Web UI
#
# Results saved to: build/load-test-results/
# =============================================================================

set -e

# Configuration
HOST="http://localhost:8080"
RESULTS_DIR="build/load-test-results"
LOCUST_DIR="locust"
TIMESTAMP=$(date +%Y%m%d_%H%M%S)

# Test parameters
USERS=100           # Number of concurrent users
SPAWN_RATE=10       # Users spawned per second
DURATION="60s"      # Test duration

# Data check
EXPECTED_ROWS=5000000
DATA_CHECK_INTERVAL=5
DATA_CHECK_TIMEOUT=300  # 5 minutes max wait

# Server
SERVER_PID=""
SERVER_LOG="build/server.log"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

print_header() {
    echo ""
    echo -e "${BLUE}=============================================================================${NC}"
    echo -e "${BLUE}  $1${NC}"
    echo -e "${BLUE}=============================================================================${NC}"
    echo ""
}

print_info() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

print_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

print_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

cleanup() {
    if [ -n "$SERVER_PID" ] && kill -0 "$SERVER_PID" 2>/dev/null; then
        print_info "Stopping server (PID: $SERVER_PID)..."
        kill "$SERVER_PID" 2>/dev/null || true
        wait "$SERVER_PID" 2>/dev/null || true
        print_info "Server stopped"
    fi
}

trap cleanup EXIT

start_server() {
    print_header "Starting Spring Boot Server"

    # Check if server is already running
    if curl -s --max-time 2 "${HOST}/api/distribution-groups/health" > /dev/null 2>&1; then
        print_info "Server is already running at ${HOST}"
        return 0
    fi

    # Start server in background
    print_info "Starting server..."
    mkdir -p build
    ./gradlew bootRun > "$SERVER_LOG" 2>&1 &
    SERVER_PID=$!
    print_info "Server starting with PID: $SERVER_PID"
    print_info "Log file: $SERVER_LOG"

    # Wait for server to be ready
    print_info "Waiting for server to be ready..."
    local max_wait=120
    local waited=0
    while [ $waited -lt $max_wait ]; do
        if curl -s --max-time 2 "${HOST}/api/distribution-groups/health" > /dev/null 2>&1; then
            print_info "Server is ready!"
            return 0
        fi
        sleep 2
        waited=$((waited + 2))
        echo -n "."
    done
    echo ""
    print_error "Server failed to start within ${max_wait} seconds"
    print_error "Check log: $SERVER_LOG"
    exit 1
}

wait_for_data() {
    print_header "Waiting for Data Loading"

    print_info "Expected rows: ${EXPECTED_ROWS}"
    print_info "Checking every ${DATA_CHECK_INTERVAL} seconds..."

    local waited=0
    while [ $waited -lt $DATA_CHECK_TIMEOUT ]; do
        local count=$(psql -d query_performance_demo -t -c "SELECT COUNT(*) FROM distribution_group;" 2>/dev/null | tr -d ' ')

        if [ -z "$count" ]; then
            print_warn "Cannot connect to database, retrying..."
        else
            echo -e "[$(date +%H:%M:%S)] distribution_group: ${count} / ${EXPECTED_ROWS} rows"

            if [ "$count" -ge "$EXPECTED_ROWS" ]; then
                echo ""
                print_info "Data loading complete!"
                return 0
            fi
        fi

        sleep $DATA_CHECK_INTERVAL
        waited=$((waited + DATA_CHECK_INTERVAL))
    done

    print_error "Data loading timeout after ${DATA_CHECK_TIMEOUT} seconds"
    exit 1
}

check_prerequisites() {
    print_header "Checking Prerequisites"

    # Check if locust is installed
    if ! command -v locust &> /dev/null; then
        print_error "Locust is not installed. Install with: pip install locust"
        exit 1
    fi
    print_info "Locust installed: $(locust --version)"

    # Check if psql is available
    if ! command -v psql &> /dev/null; then
        print_error "psql is not installed"
        exit 1
    fi
    print_info "psql available"

    # Create results directory
    mkdir -p "${RESULTS_DIR}"
    print_info "Results will be saved to: ${RESULTS_DIR}/"
}

run_scenario() {
    local scenario_name=$1
    local scenario_class=$2
    local users=${3:-$USERS}
    local duration=${4:-$DURATION}

    print_header "Running Scenario: ${scenario_name}"

    local result_prefix="${RESULTS_DIR}/${scenario_name}_${TIMESTAMP}"

    print_info "Users: ${users}"
    print_info "Spawn Rate: ${SPAWN_RATE}/s"
    print_info "Duration: ${duration}"
    print_info "Results: ${result_prefix}_*.csv"
    echo ""

    cd "${LOCUST_DIR}"

    # Run locust (ignore exit code - failures are expected in stress tests)
    locust -f locustfile.py ${scenario_class} \
        --host="${HOST}" \
        --headless \
        --users "${users}" \
        --spawn-rate "${SPAWN_RATE}" \
        --run-time "${duration}" \
        --csv="../${result_prefix}" \
        --html="../${result_prefix}.html" \
        --only-summary || true

    cd ..

    echo ""
    print_info "Results saved:"
    print_info "  - CSV stats: ${result_prefix}_stats.csv"
    print_info "  - CSV history: ${result_prefix}_stats_history.csv"
    print_info "  - HTML report: ${result_prefix}.html"
    echo ""
}

scenario_slow() {
    run_scenario "slow_only" "ScenarioSlowOnly" 100 "60s"
}

scenario_fast() {
    run_scenario "fast_only" "ScenarioFastOnly" 500 "60s"
}

scenario_mixed() {
    run_scenario "mixed" "ScenarioMixed" 100 "120s"
}

run_all() {
    print_header "Running All Scenarios"

    print_warn "This will take approximately 5 minutes"
    echo ""

    # Scenario 1: Fast Only (baseline)
    scenario_fast

    # Wait between tests
    print_info "Waiting 10 seconds before next scenario..."
    sleep 10

    # Scenario 2: Slow Only (stress test)
    scenario_slow

    # Wait between tests
    print_info "Waiting 10 seconds before next scenario..."
    sleep 10

    # Scenario 3: Mixed (realistic)
    scenario_mixed

    print_header "All Scenarios Completed"
    print_info "Results saved in: ${RESULTS_DIR}/"
    echo ""
    ls -la "${RESULTS_DIR}/"
}

run_full_pipeline() {
    check_prerequisites
    start_server
    wait_for_data
    run_all
    print_header "Pipeline Complete"
    print_info "Server will be stopped on exit"
}

start_web_ui() {
    print_header "Starting Locust Web UI"

    print_info "Open browser at: http://localhost:8089"
    print_info "Press Ctrl+C to stop"
    echo ""

    cd "${LOCUST_DIR}"
    locust -f locustfile.py --host="${HOST}"
}

show_results() {
    print_header "Test Results Summary"

    if [ ! -d "${RESULTS_DIR}" ] || [ -z "$(ls -A ${RESULTS_DIR} 2>/dev/null)" ]; then
        print_warn "No results found. Run a test first."
        exit 0
    fi

    echo "Available results:"
    echo ""

    for html_file in "${RESULTS_DIR}"/*.html; do
        if [ -f "$html_file" ]; then
            echo "  - $(basename $html_file)"
        fi
    done

    echo ""
    print_info "Open HTML files in browser to view detailed reports"

    # Show latest stats summary if exists
    latest_stats=$(ls -t "${RESULTS_DIR}"/*_stats.csv 2>/dev/null | head -1)
    if [ -f "$latest_stats" ]; then
        echo ""
        print_info "Latest test summary ($(basename $latest_stats)):"
        echo ""
        column -t -s',' "$latest_stats" | head -20
    fi
}

show_usage() {
    echo "Usage: $0 [command]"
    echo ""
    echo "Commands:"
    echo "  run     - Full pipeline: start server -> wait data -> run all tests -> stop server"
    echo "  slow    - Test slow endpoint only (requires running server)"
    echo "  fast    - Test fast endpoint only (requires running server)"
    echo "  mixed   - Test mixed traffic (requires running server)"
    echo "  all     - Run all scenarios (requires running server)"
    echo "  web     - Start Locust Web UI (requires running server)"
    echo "  results - Show test results summary"
    echo ""
    echo "Examples:"
    echo "  $0 run      # Full automated pipeline (recommended)"
    echo "  $0 slow     # Run slow endpoint test only"
    echo "  $0 web      # Start interactive Web UI"
    echo ""
    echo "Results are saved to: ${RESULTS_DIR}/"
}

# Main
case "${1:-}" in
    run)
        run_full_pipeline
        ;;
    slow)
        check_prerequisites
        scenario_slow
        ;;
    fast)
        check_prerequisites
        scenario_fast
        ;;
    mixed)
        check_prerequisites
        scenario_mixed
        ;;
    all)
        check_prerequisites
        run_all
        ;;
    web)
        check_prerequisites
        start_web_ui
        ;;
    results)
        show_results
        ;;
    *)
        show_usage
        exit 0
        ;;
esac
