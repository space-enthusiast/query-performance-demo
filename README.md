# Query Performance Demo

Demo project for query performance analysis comparing LEFT JOIN vs UNION ALL strategies.

## Setup

1. Create PostgreSQL database:
```bash
psql -d postgres -c "CREATE DATABASE query_performance_demo;"
```

2. Run the application (auto-creates tables and loads 5M test records):
```bash
./gradlew bootRun
```

3. Run performance tests:
```bash
./gradlew test --tests "QueryPerformanceTest" --info
```

## Test Data

| Table | Count |
|-------|-------|
| distribution_group / distribution_group_2 | 5,000,000 |
| distribution_group_matching / distribution_group_matching_2 | 5,000,000 |
| task | 5,000,000 |
| account / account_2 | 100 |
| account_group / account_group_2 | 20 |
| skill / skill_2 | 50 |

**Note**: Tables with `_2` suffix have additional indexes for Scenario 3:
- `idx_skill2_code` on `skill_2(code)`
- `idx_dg2_state_id` on `distribution_group_2(state, id)`
- `idx_dgm2_dg_id_type_pointer` on `distribution_group_matching_2(distribution_group_id, type, pointer)`

## Query Comparison

### Scenario 1: LEFT JOIN + GROUP BY (No indexes)

```sql
SELECT dg.id, dg.state
FROM distribution_group dg
JOIN distribution_group_matching dgm ON dgm.distribution_group_id = dg.id
LEFT JOIN skill s ON dgm.type = 'SKILL_CODE' AND dgm.pointer = s.code
LEFT JOIN account a ON dgm.type = 'ACCOUNT_ID' AND dgm.pointer = CAST(a.id AS TEXT)
LEFT JOIN account_group ag ON dgm.type = 'ACCOUNT_GROUP_ID' AND dgm.pointer = CAST(ag.id AS TEXT)
WHERE dg.state = 'WAITING'
  AND (s.id IS NOT NULL OR a.id IS NOT NULL OR ag.id IS NOT NULL)
GROUP BY dg.id, dg.state
ORDER BY dg.id
LIMIT 100 OFFSET 0;
```

### Scenario 2: UNION ALL (No indexes)

```sql
SELECT dg.id, dg.state FROM distribution_group dg
JOIN distribution_group_matching dgm ON dgm.distribution_group_id = dg.id
JOIN skill s ON dgm.type = 'SKILL_CODE' AND dgm.pointer = s.code
WHERE dg.state = 'WAITING'

UNION ALL

SELECT dg.id, dg.state FROM distribution_group dg
JOIN distribution_group_matching dgm ON dgm.distribution_group_id = dg.id
JOIN account a ON dgm.type = 'ACCOUNT_ID' AND dgm.pointer = CAST(a.id AS TEXT)
WHERE dg.state = 'WAITING'

UNION ALL

SELECT dg.id, dg.state FROM distribution_group dg
JOIN distribution_group_matching dgm ON dgm.distribution_group_id = dg.id
JOIN account_group ag ON dgm.type = 'ACCOUNT_GROUP_ID' AND dgm.pointer = CAST(ag.id AS TEXT)
WHERE dg.state = 'WAITING'

ORDER BY id
LIMIT 100 OFFSET 0;
```

### Scenario 3: LEFT JOIN + GROUP BY + Indexes

Uses `_2` tables with indexes:

```sql
SELECT dg.id, dg.state
FROM distribution_group_2 dg
JOIN distribution_group_matching_2 dgm ON dgm.distribution_group_id = dg.id
LEFT JOIN skill_2 s ON dgm.type = 'SKILL_CODE' AND dgm.pointer = s.code
LEFT JOIN account_2 a ON dgm.type = 'ACCOUNT_ID' AND dgm.pointer = CAST(a.id AS TEXT)
LEFT JOIN account_group_2 ag ON dgm.type = 'ACCOUNT_GROUP_ID' AND dgm.pointer = CAST(ag.id AS TEXT)
WHERE dg.state = 'WAITING'
  AND (s.id IS NOT NULL OR a.id IS NOT NULL OR ag.id IS NOT NULL)
GROUP BY dg.id, dg.state
ORDER BY dg.id
LIMIT 100 OFFSET 0;
```

## Performance Results (5M Records)

| Scenario | Avg Time | Description |
|----------|----------|-------------|
| Scenario 1: LEFT JOIN + GROUP BY | ~343 ms | No indexes |
| Scenario 2: UNION ALL | ~2,414 ms | No indexes |
| Scenario 3: LEFT JOIN + GROUP BY + Index | ~1.7 ms | With indexes |

**Key Findings**:
- LEFT JOIN + GROUP BY is **~7x faster** than UNION ALL (without indexes)
- Adding indexes to LEFT JOIN + GROUP BY provides **~201x improvement** (343 ms → 1.7 ms)

![Query Performance Chart](build/query-performance-chart.png)

## EXPLAIN ANALYZE Results

### Scenario 1: LEFT JOIN + GROUP BY (~343 ms avg)

```
Limit (actual time=66..71 ms, rows=100)
  -> Group (rows=100)
       -> Nested Loop Left Join (account_group)
             -> Nested Loop Left Join (account)
                   -> Nested Loop Left Join (skill)
                         -> Merge Join (dg + dgm)
                               +-- Gather Merge (dgm) <- Parallel scan + sort
                               +-- Index Scan (dg) <- Uses PRIMARY KEY index
```

Key points:
- `Index Scan on distribution_group_pkey`: Uses index, only reads 100 rows needed
- `Merge Join`: Efficiently joins sorted data
- `Nested Loop Left Join`: Small tables (100 accounts, 20 groups, 50 skills) - fast
- `Limit` pushed down: Stops early after finding 100 rows

### Scenario 2: UNION ALL (~2,414 ms avg)

```
Limit (actual time=501..508 ms, rows=100)
  -> Gather Merge (sort results from workers)
       -> Sort (ORDER BY id)
             -> Parallel Append (combines 3 sub-queries)
                   +-- Hash Join: dg + dgm + account_group (111k rows each worker)
                   +-- Hash Join: dg + dgm + account (166k rows each worker)
                   +-- Hash Join: dg + dgm + skill (333k rows)
```

Key points:
- `Parallel Seq Scan on distribution_group`: Scans 3 times (once per sub-query)
- `Parallel Seq Scan on distribution_group_matching`: Scans 3 times with filter
- `Rows Removed by Filter: 666667`: Each sub-query filters 2/3 of matching table
- `Sort` after append: Must collect all 1M rows before sorting
- `Limit` NOT pushed down: Can't limit until after UNION ALL + ORDER BY

### Scenario 3: LEFT JOIN + GROUP BY + Index (~1.7 ms avg)

```
Limit (actual time=0.5..0.8 ms, rows=100)
  -> Group (rows=100)
       -> Nested Loop Left Join (account_group_2)
             -> Nested Loop Left Join (account_2)
                   -> Nested Loop Left Join (skill_2)
                         -> Merge Join (dg + dgm)
                               +-- Index Scan (dgm) <- Uses idx_dgm2_dg_id_type_pointer
                               +-- Index Scan (dg) <- Uses idx_dg2_state_id
```

Key points:
- `idx_dg2_state_id`: Composite index on (state, id) enables efficient filtering + ordering
- `idx_dgm2_dg_id_type_pointer`: Covers all join conditions, eliminates sequential scans
- `idx_skill2_code`: Enables index lookup instead of scan for skill matching
- All operations use indexes: No sequential scans needed

## Key Differences

| Factor | Scenario 1 (LEFT JOIN) | Scenario 2 (UNION ALL) | Scenario 3 (LEFT JOIN + Index) |
|--------|------------------------|------------------------|--------------------------------|
| Table scans | 1x each table | 3x distribution_group, 3x matching | 0 (all index scans) |
| Can use LIMIT early | Yes | No (must sort first) | Yes |
| Parallelism | Limited | Good | Not needed |
| Index usage | Uses PK index only | Hash joins | Full composite indexes |
| Avg Time | ~343 ms | ~2,414 ms | ~1.7 ms |

## Why LEFT JOIN + Index is Fastest

**At 5M rows**, LEFT JOIN + GROUP BY + Index is ~201x faster than without indexes because:

1. **Composite indexes**: `idx_dg2_state_id(state, id)` covers both WHERE and ORDER BY
2. **Covering index for joins**: `idx_dgm2_dg_id_type_pointer` eliminates table lookups
3. **Index-only scans**: All required data retrieved from indexes
4. **Early LIMIT pushdown**: Stops after finding 100 rows with minimal I/O

**LEFT JOIN vs UNION ALL** (without indexes):
- LEFT JOIN is ~7x faster (343 ms vs 2,414 ms)
- Single table scan vs 3x scans for UNION ALL
- LIMIT can be applied early vs must sort all results first

**Impact of Indexes**:
- LEFT JOIN: 343 ms → 1.7 ms (**201x improvement**)
- Proper indexing provides orders of magnitude improvement

## Conclusion

For paginated queries with `ORDER BY ... LIMIT ... OFFSET`:
1. **LEFT JOIN + GROUP BY** is the better query strategy (vs UNION ALL)
2. **Proper indexes** provide the biggest performance gain (~200x improvement)
3. The combination of good query structure + indexes achieves sub-2ms response times on 5M records
