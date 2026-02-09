package com.example.query_performance_demo

import org.jfree.chart.ChartFactory
import org.jfree.chart.ChartUtils
import org.jfree.data.statistics.DefaultBoxAndWhiskerCategoryDataset
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import java.io.File

@SpringBootTest
class QueryPerformanceTest {

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    companion object {
        const val PAGE_SIZE = 100
        const val OFFSET = 0
        const val ITERATIONS = 20

        // ============================================================================
        // Scenario 1: LEFT JOIN + GROUP BY (Set 1 - No indexes)
        // ============================================================================
        val QUERY_LEFT_JOIN = """
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
            LIMIT ? OFFSET ?
        """.trimIndent()

        // ============================================================================
        // Scenario 2: UNION ALL (Set 1 - No indexes)
        // ============================================================================
        val QUERY_UNION_ALL = """
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

        // ============================================================================
        // Scenario 3: UNION ALL + Index (Set 2 - With indexes)
        // ============================================================================
        val QUERY_UNION_ALL_INDEXED = """
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
            LIMIT ? OFFSET ?
        """.trimIndent()
    }

    @Test
    fun `compare query performance - 3 scenarios`() {
        println("\n" + "=".repeat(80))
        println("QUERY PERFORMANCE COMPARISON (5M Records)")
        println("=".repeat(80))

        // Scenario 1: LEFT JOIN + GROUP BY (No indexes)
        println("\n>>> SCENARIO 1: LEFT JOIN + GROUP BY (No indexes)")
        println("-".repeat(80))
        println(QUERY_LEFT_JOIN)
        println("-".repeat(80))

        val leftJoinTimes = measureQueryPerformance(QUERY_LEFT_JOIN, "Scenario 1")

        // Scenario 2: UNION ALL (No indexes)
        println("\n>>> SCENARIO 2: UNION ALL (No indexes)")
        println("-".repeat(80))
        println(QUERY_UNION_ALL)
        println("-".repeat(80))

        val unionAllTimes = measureQueryPerformance(QUERY_UNION_ALL, "Scenario 2")

        // Scenario 3: UNION ALL + Index
        println("\n>>> SCENARIO 3: UNION ALL + Index")
        println("-".repeat(80))
        println(QUERY_UNION_ALL_INDEXED)
        println("-".repeat(80))

        val unionAllIndexedTimes = measureQueryPerformance(QUERY_UNION_ALL_INDEXED, "Scenario 3")

        // Summary
        println("\n" + "=".repeat(80))
        println("SUMMARY")
        println("=".repeat(80))
        println("Scenario 1 (LEFT JOIN + GROUP BY, No indexes) avg: ${leftJoinTimes.average()} ms")
        println("Scenario 2 (UNION ALL, No indexes) avg: ${unionAllTimes.average()} ms")
        println("Scenario 3 (UNION ALL + Index) avg: ${unionAllIndexedTimes.average()} ms")
        println("-".repeat(80))

        val improvement1to2 = leftJoinTimes.average() / unionAllTimes.average()
        val improvement2to3 = unionAllTimes.average() / unionAllIndexedTimes.average()
        val improvement1to3 = leftJoinTimes.average() / unionAllIndexedTimes.average()

        println("Improvement (Scenario 1 → 2): %.2fx faster".format(improvement1to2))
        println("Improvement (Scenario 2 → 3): %.2fx faster".format(improvement2to3))
        println("Total Improvement (Scenario 1 → 3): %.2fx faster".format(improvement1to3))
        println("=".repeat(80) + "\n")

        // Generate chart
        generatePerformanceChart(leftJoinTimes, unionAllTimes, unionAllIndexedTimes)
    }

    private fun measureQueryPerformance(query: String, scenario: String): List<Long> {
        val times = mutableListOf<Long>()
        repeat(ITERATIONS) { i ->
            val start = System.currentTimeMillis()
            val result = jdbcTemplate.queryForList(query, PAGE_SIZE, OFFSET)
            val elapsed = System.currentTimeMillis() - start
            times.add(elapsed)
            if (i == 0) println("Result count: ${result.size}")
        }
        println("Execution times ($scenario): $times ms")
        println("Average: ${times.average()} ms")
        return times
    }

    private fun generatePerformanceChart(
        leftJoinTimes: List<Long>,
        unionAllTimes: List<Long>,
        unionAllIndexedTimes: List<Long>
    ) {
        val dataset = DefaultBoxAndWhiskerCategoryDataset().apply {
            add(leftJoinTimes.map { it.toDouble() }, "Query Type", "LEFT JOIN\n(No Index)")
            add(unionAllTimes.map { it.toDouble() }, "Query Type", "UNION ALL\n(No Index)")
            add(unionAllIndexedTimes.map { it.toDouble() }, "Query Type", "UNION ALL\n(+ Index)")
        }

        val chart = ChartFactory.createBoxAndWhiskerChart(
            "Query Performance Comparison (5M Records)",  // title
            "Query Strategy",                             // x-axis label
            "Time (ms)",                                  // y-axis label
            dataset,
            true                                          // legend
        )

        // Save chart as PNG
        val outputFile = File("build/query-performance-chart.png")
        outputFile.parentFile.mkdirs()
        ChartUtils.saveChartAsPNG(outputFile, chart, 800, 600)
        println("Chart saved to: ${outputFile.absolutePath}")
    }
}
