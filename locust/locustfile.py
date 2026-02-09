"""
Load Test for Query Performance Demo

This test demonstrates how slow queries (2.5s) can cause system-wide performance degradation
by exhausting the database connection pool and Tomcat thread pool.

Usage:
    # Install locust
    pip install locust

    # Run load test (Web UI)
    cd locust
    locust -f locustfile.py --host=http://localhost:8080

    # Run headless (CLI)
    locust -f locustfile.py --host=http://localhost:8080 --headless -u 100 -r 10 -t 60s

Test Scenarios:
    1. SlowQueryUser - Hits /api/distribution-groups/slow (2.5s query)
    2. FastQueryUser - Hits /api/distribution-groups/fast (2ms query)
    3. MixedUser - 20% slow, 80% fast (realistic scenario)
"""

from locust import HttpUser, task, between, events
import time
import logging

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)


class SlowQueryUser(HttpUser):
    """
    User that only hits the slow endpoint.
    Simulates worst-case scenario where users trigger expensive queries.

    Expected behavior under load:
    - DB connection pool (50) exhausted quickly
    - Each request holds connection for ~2.5s
    - Max throughput: 50 connections / 2.5s = 20 req/s
    - Beyond that: requests queue up and timeout
    """
    weight = 1
    wait_time = between(0.1, 0.5)  # Aggressive request rate

    @task
    def slow_query(self):
        with self.client.get(
            "/api/distribution-groups/slow",
            name="/slow",
            catch_response=True
        ) as response:
            if response.status_code == 200:
                data = response.json()
                if data.get("queryTimeMs", 0) > 5000:
                    response.failure(f"Query too slow: {data['queryTimeMs']}ms")
            elif response.status_code == 0:
                response.failure("Connection timeout - pool exhausted")
            else:
                response.failure(f"HTTP {response.status_code}")


class FastQueryUser(HttpUser):
    """
    User that only hits the fast endpoint.
    Demonstrates how optimized queries handle high load.

    Expected behavior:
    - Each request takes ~2ms
    - Connection returned to pool immediately
    - Can handle thousands of requests per second
    """
    weight = 1
    wait_time = between(0.1, 0.5)

    @task
    def fast_query(self):
        with self.client.get(
            "/api/distribution-groups/fast",
            name="/fast",
            catch_response=True
        ) as response:
            if response.status_code == 200:
                data = response.json()
                if data.get("queryTimeMs", 0) > 100:
                    response.failure(f"Unexpectedly slow: {data['queryTimeMs']}ms")
            else:
                response.failure(f"HTTP {response.status_code}")


class MixedUser(HttpUser):
    """
    Realistic user behavior: 20% slow queries, 80% fast queries.

    This demonstrates how a small percentage of slow queries
    can degrade the entire system:

    - Slow queries hold connections longer
    - Fast queries start waiting for connections
    - Response times increase across the board
    - Eventually, even fast queries timeout
    """
    weight = 3  # Most common user type
    wait_time = between(0.5, 2)

    @task(2)  # 20% of requests
    def slow_query(self):
        self.client.get("/api/distribution-groups/slow", name="/slow (mixed)")

    @task(8)  # 80% of requests
    def fast_query(self):
        self.client.get("/api/distribution-groups/fast", name="/fast (mixed)")


class HealthCheckUser(HttpUser):
    """
    Monitors system health during load test.
    If health checks start failing, the system is overloaded.
    """
    weight = 1
    wait_time = between(1, 3)

    @task
    def health_check(self):
        with self.client.get(
            "/api/distribution-groups/health",
            name="/health",
            catch_response=True
        ) as response:
            if response.status_code == 200:
                data = response.json()
                if data.get("responseTimeMs", 0) > 1000:
                    response.failure(f"Health check slow: {data['responseTimeMs']}ms - system degraded")
            else:
                response.failure("Health check failed - system may be down")


# ==============================================================================
# Scenario-specific test classes
# ==============================================================================

class ScenarioSlowOnly(HttpUser):
    """
    Scenario: All users hit slow endpoint

    Run with:
        locust -f locustfile.py --host=http://localhost:8080 ScenarioSlowOnly

    Expected: System collapses at ~20-50 concurrent users
    """
    wait_time = between(0.1, 0.5)

    @task
    def slow_query(self):
        self.client.get("/api/distribution-groups/slow")


class ScenarioFastOnly(HttpUser):
    """
    Scenario: All users hit fast endpoint

    Run with:
        locust -f locustfile.py --host=http://localhost:8080 ScenarioFastOnly

    Expected: System handles hundreds of concurrent users easily
    """
    wait_time = between(0.1, 0.5)

    @task
    def fast_query(self):
        self.client.get("/api/distribution-groups/fast")


class ScenarioMixed(HttpUser):
    """
    Scenario: Realistic mix (20% slow, 80% fast)

    Run with:
        locust -f locustfile.py --host=http://localhost:8080 ScenarioMixed

    Expected: Fast queries degrade as slow queries consume connections
    """
    wait_time = between(0.5, 1)

    @task(2)
    def slow_query(self):
        self.client.get("/api/distribution-groups/slow", name="/slow")

    @task(8)
    def fast_query(self):
        self.client.get("/api/distribution-groups/fast", name="/fast")


# ==============================================================================
# Event hooks for logging
# ==============================================================================

@events.test_start.add_listener
def on_test_start(environment, **kwargs):
    logger.info("=" * 60)
    logger.info("Load test started")
    logger.info("Target: %s", environment.host)
    logger.info("=" * 60)
    logger.info("")
    logger.info("Connection Pool Settings (from application.yaml):")
    logger.info("  - HikariCP max-pool-size: 50")
    logger.info("  - Tomcat max-threads: 200")
    logger.info("")
    logger.info("Expected behavior:")
    logger.info("  - Slow endpoint: ~2.5s per request")
    logger.info("  - Fast endpoint: ~2ms per request")
    logger.info("  - Max slow queries/sec: 50 / 2.5 = 20 req/s")
    logger.info("=" * 60)


@events.test_stop.add_listener
def on_test_stop(environment, **kwargs):
    logger.info("=" * 60)
    logger.info("Load test completed")
    logger.info("=" * 60)
