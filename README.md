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

### Scenario 3: UNION ALL + Indexes

Uses `_2` tables with indexes:

```sql
SELECT dg.id, dg.state FROM distribution_group_2 dg
JOIN distribution_group_matching_2 dgm ON dgm.distribution_group_id = dg.id
JOIN skill_2 s ON dgm.type = 'SKILL_CODE' AND dgm.pointer = s.code
WHERE dg.state = 'WAITING'

UNION ALL

SELECT dg.id, dg.state FROM distribution_group_2 dg
JOIN distribution_group_matching_2 dgm ON dgm.distribution_group_id = dg.id
JOIN account_2 a ON dgm.type = 'ACCOUNT_ID' AND dgm.pointer = CAST(a.id AS TEXT)
WHERE dg.state = 'WAITING'

UNION ALL

SELECT dg.id, dg.state FROM distribution_group_2 dg
JOIN distribution_group_matching_2 dgm ON dgm.distribution_group_id = dg.id
JOIN account_group_2 ag ON dgm.type = 'ACCOUNT_GROUP_ID' AND dgm.pointer = CAST(ag.id AS TEXT)
WHERE dg.state = 'WAITING'

ORDER BY id
LIMIT 100 OFFSET 0;
```

## Performance Results (5M Records)

| Scenario | Avg Time | Description |
|----------|----------|-------------|
| Scenario 1: LEFT JOIN + GROUP BY | ~420 ms | No indexes |
| Scenario 2: UNION ALL | ~2,600 ms | No indexes |
| Scenario 3: UNION ALL + Index | ~2,800 ms | With indexes |

**Key Finding**: At 5M records scale, LEFT JOIN + GROUP BY is **~6x faster** than UNION ALL approaches.

![Query Performance Chart](build/query-performance-chart.png)

## EXPLAIN ANALYZE Results

### Scenario 1: LEFT JOIN + GROUP BY (~420 ms avg)

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

### Scenario 2: UNION ALL (~2,600 ms avg)

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

## Key Differences

| Factor | Scenario 1 (LEFT JOIN) | Scenario 2/3 (UNION ALL) |
|--------|------------------------|--------------------------|
| Table scans | 1x each table | 3x distribution_group, 3x matching |
| Can use LIMIT early | Yes | No (must sort first) |
| Parallelism | Limited | Good |
| Index usage | Uses PK index | Hash joins (indexes don't help much) |

## Why LEFT JOIN Scales Better

**At 5M rows**, LEFT JOIN + GROUP BY is ~6x faster because:

1. **Early LIMIT pushdown**: Stops after finding 100 rows
2. **Single table scan**: Each table scanned once vs 3x for UNION ALL
3. **Merge Join efficiency**: Sorted data from indexes enables efficient joining
4. **No post-sort required**: Results already ordered by primary key

**UNION ALL limitations at scale**:
- Must scan `distribution_group` 3 times (15M row reads total)
- Must scan `distribution_group_matching` 3 times with filters
- Cannot apply LIMIT until after collecting all results + sorting
- Indexes on `_2` tables provide minimal benefit due to UNION structure

## Conclusion

For paginated queries with `ORDER BY ... LIMIT ... OFFSET`, LEFT JOIN + GROUP BY outperforms UNION ALL at larger data scales due to early termination capabilities and reduced I/O.
