# Load Test - Query Performance Impact

2.5초 쿼리가 시스템 전체 성능에 미치는 영향을 테스트합니다.

## 왜 2.5초 쿼리가 문제인가?

### Connection Pool 수학

```
HikariCP max-pool-size: 50
쿼리 실행 시간: 2.5초

최대 처리량 = 50 connections / 2.5s = 20 requests/second
```

**50명 이상이 동시에 slow 엔드포인트를 호출하면:**
1. Connection Pool 고갈
2. 새로운 요청들이 connection 대기
3. Connection timeout (30초) 후 에러 발생
4. Fast 쿼리도 connection을 얻지 못해 함께 느려짐

### Thread Pool 영향

```
Tomcat max-threads: 200
각 스레드가 2.5초 동안 블로킹

최대 처리량 = 200 threads / 2.5s = 80 requests/second
```

실제로는 DB Connection Pool (50개)이 먼저 병목이 됨.

## 설치

```bash
pip install locust
```

## 실행

### 1. Spring Boot 서버 시작

```bash
# 프로젝트 루트에서
./gradlew bootRun
```

### 2. Locust 실행

```bash
cd locust

# Web UI 모드 (http://localhost:8089)
locust -f locustfile.py --host=http://localhost:8080

# CLI 모드 (headless)
locust -f locustfile.py --host=http://localhost:8080 --headless -u 100 -r 10 -t 60s
```

## 테스트 시나리오

### Scenario 1: Slow Only (시스템 붕괴 테스트)

```bash
locust -f locustfile.py --host=http://localhost:8080 ScenarioSlowOnly --headless -u 100 -r 10 -t 60s
```

**예상 결과:**
- 0-20 users: 정상 동작 (20 req/s)
- 20-50 users: 응답시간 증가 시작
- 50+ users: Connection timeout 에러 발생

### Scenario 2: Fast Only (최적화된 쿼리)

```bash
locust -f locustfile.py --host=http://localhost:8080 ScenarioFastOnly --headless -u 500 -r 50 -t 60s
```

**예상 결과:**
- 500 users도 문제없이 처리
- 응답시간 ~10ms 유지
- Connection Pool 여유

### Scenario 3: Mixed (현실적 시나리오)

```bash
locust -f locustfile.py --host=http://localhost:8080 ScenarioMixed --headless -u 100 -r 10 -t 120s
```

**예상 결과:**
- Slow 쿼리(20%)가 connection을 점유
- Fast 쿼리(80%)도 connection 대기 시작
- 전체 시스템 응답시간 저하

## 관찰 포인트

### Locust 메트릭

| 지표 | Slow Only | Fast Only | Mixed |
|------|-----------|-----------|-------|
| RPS | ~20 | ~1000+ | ~100 |
| Median Response | 2500ms | 5ms | 500ms+ |
| 95% Response | 30000ms+ | 20ms | 5000ms+ |
| Failure Rate | 높음 | 0% | 중간 |

### HikariCP 모니터링

Spring Boot Actuator를 추가하면 connection pool 상태를 실시간으로 확인 가능:

```
GET http://localhost:8080/actuator/metrics/hikaricp.connections.active
GET http://localhost:8080/actuator/metrics/hikaricp.connections.pending
```

## 결론

| 쿼리 | 응답시간 | 50 connections으로 최대 처리량 |
|------|----------|------------------------------|
| UNION ALL (Slow) | 2.5s | 20 req/s |
| LEFT JOIN + Index (Fast) | 2ms | 25,000 req/s |

**2.5초 쿼리 하나가 시스템 전체를 마비시킬 수 있음.**

쿼리 최적화로 **1,250배** 처리량 향상 가능.
