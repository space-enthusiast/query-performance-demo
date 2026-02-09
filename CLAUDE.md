# Claude Session Notes

## Query Performance Optimization Session (2026-02-09)

### Completed Work

Implemented Query Performance Optimization plan comparing 3 scenarios with 5M records:

1. **Entities.kt** - Created two table sets:
   - Original tables (no indexes): `account`, `account_group`, `skill`, `distribution_group`, `distribution_group_matching`
   - Indexed tables (_2 suffix): `account_2`, `account_group_2`, `skill_2`, `distribution_group_2`, `distribution_group_matching_2`
   - Indexes added: `idx_skill2_code`, `idx_dg2_state_id`, `idx_dgm2_dg_id_type_pointer`

2. **DataLoader.kt** - Updated for 5M records:
   - `DG_COUNT = 5,000,000`
   - `TASK_COUNT = 5,000,000`
   - Added data loading for both table sets

3. **QueryPerformanceTest.kt** - 3 test scenarios:
   - Scenario 1: UNION ALL (No indexes) - ~2,543 ms
   - Scenario 2: LEFT JOIN + GROUP BY (No indexes) - ~358 ms
   - Scenario 3: LEFT JOIN + GROUP BY + Index (_2 tables) - ~1.8 ms

### Execution Results
- Data loading: ~240 seconds for 5M records
- Test execution: ~5 minutes
- Chart generated: `build/query-performance-chart.png`
- Key finding: Total improvement **~1,413x** (Scenario 1 → 3)

### Commands
```bash
# Load data
./gradlew bootRun

# Run performance test
./gradlew test --tests "QueryPerformanceTest"
```

### Completed
- README updated with:
  - 5M records test data
  - 3 scenarios with SQL queries
  - Performance results table
  - Chart reference
  - Updated EXPLAIN ANALYZE sections
  - Key differences and conclusion
