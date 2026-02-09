package com.example.query_performance_demo

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/distribution-groups")
class DistributionGroupController(
    private val jdbcTemplate: JdbcTemplate
) {
    companion object {
        // Slow query: UNION ALL (~2.5s)
        private val QUERY_SLOW = """
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
            LIMIT ? OFFSET ?
        """.trimIndent()

        // Fast query: LEFT JOIN + Index (~2ms)
        private val QUERY_FAST = """
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
            LIMIT ? OFFSET ?
        """.trimIndent()
    }

    data class QueryResult(
        val data: List<Map<String, Any?>>,
        val count: Int,
        val queryTimeMs: Long
    )

    /**
     * Slow endpoint - uses UNION ALL query (~2.5s)
     * This will exhaust DB connection pool under load
     */
    @GetMapping("/slow")
    fun getDistributionGroupsSlow(
        @RequestParam(defaultValue = "100") limit: Int,
        @RequestParam(defaultValue = "0") offset: Int
    ): QueryResult {
        val start = System.currentTimeMillis()
        val result = jdbcTemplate.queryForList(QUERY_SLOW, limit, offset)
        val elapsed = System.currentTimeMillis() - start
        return QueryResult(result, result.size, elapsed)
    }

    /**
     * Fast endpoint - uses LEFT JOIN + Index query (~2ms)
     * Handles high load efficiently
     */
    @GetMapping("/fast")
    fun getDistributionGroupsFast(
        @RequestParam(defaultValue = "100") limit: Int,
        @RequestParam(defaultValue = "0") offset: Int
    ): QueryResult {
        val start = System.currentTimeMillis()
        val result = jdbcTemplate.queryForList(QUERY_FAST, limit, offset)
        val elapsed = System.currentTimeMillis() - start
        return QueryResult(result, result.size, elapsed)
    }

    /**
     * Health check endpoint - simple query
     */
    @GetMapping("/health")
    fun health(): Map<String, Any> {
        val start = System.currentTimeMillis()
        val count = jdbcTemplate.queryForObject("SELECT 1", Int::class.java)
        return mapOf(
            "status" to "ok",
            "dbCheck" to (count == 1),
            "responseTimeMs" to (System.currentTimeMillis() - start)
        )
    }
}
