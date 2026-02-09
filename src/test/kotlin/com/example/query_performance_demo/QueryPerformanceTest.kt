package com.example.query_performance_demo

import org.jfree.chart.ChartFactory
import org.jfree.chart.ChartUtils
import org.jfree.chart.plot.PlotOrientation
import org.jfree.data.category.DefaultCategoryDataset
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

        // Query 1: Single query with LEFT JOINs and GROUP BY
        val SLOW_QUERY = """
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

        // Query 2: Separate queries with UNION ALL
        val FAST_QUERY = """
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
    }

    @Test
    fun `compare query performance`() {
        println("\n" + "=".repeat(80))
        println("QUERY PERFORMANCE COMPARISON")
        println("=".repeat(80))

        // Print Query 1
        println("\n>>> QUERY 1 (LEFT JOIN + GROUP BY):")
        println("-".repeat(80))
        println(SLOW_QUERY)
        println("-".repeat(80))

        // Run Query 1 multiple times and average
        val slowTimes = mutableListOf<Long>()
        repeat(5) {
            val start = System.currentTimeMillis()
            val result1 = jdbcTemplate.queryForList(SLOW_QUERY, PAGE_SIZE, OFFSET)
            val elapsed = System.currentTimeMillis() - start
            slowTimes.add(elapsed)
            if (it == 0) println("Result count: ${result1.size}")
        }
        println("Execution times: $slowTimes ms")
        println("Average: ${slowTimes.average()} ms")

        // Print Query 2
        println("\n>>> QUERY 2 (UNION ALL):")
        println("-".repeat(80))
        println(FAST_QUERY)
        println("-".repeat(80))

        // Run Query 2 multiple times and average
        val fastTimes = mutableListOf<Long>()
        repeat(5) {
            val start = System.currentTimeMillis()
            val result2 = jdbcTemplate.queryForList(FAST_QUERY, PAGE_SIZE, OFFSET)
            val elapsed = System.currentTimeMillis() - start
            fastTimes.add(elapsed)
            if (it == 0) println("Result count: ${result2.size}")
        }
        println("Execution times: $fastTimes ms")
        println("Average: ${fastTimes.average()} ms")

        println("\n" + "=".repeat(80))
        println("SUMMARY")
        println("=".repeat(80))
        println("Query 1 (LEFT JOIN + GROUP BY) avg: ${slowTimes.average()} ms")
        println("Query 2 (UNION ALL) avg: ${fastTimes.average()} ms")
        println("Difference: ${slowTimes.average() - fastTimes.average()} ms")
        println("=".repeat(80) + "\n")

        // Generate chart with JFreeChart
        generatePerformanceChart(slowTimes, fastTimes)
    }

    private fun generatePerformanceChart(slowTimes: List<Long>, fastTimes: List<Long>) {
        val dataset = DefaultCategoryDataset().apply {
            slowTimes.forEachIndexed { index, time ->
                addValue(time, "LEFT JOIN + GROUP BY", "Run ${index + 1}")
            }
            fastTimes.forEachIndexed { index, time ->
                addValue(time, "UNION ALL", "Run ${index + 1}")
            }
        }

        val chart = ChartFactory.createLineChart(
            "Query Performance Comparison",  // title
            "Execution Run",                 // x-axis label
            "Time (ms)",                     // y-axis label
            dataset,
            PlotOrientation.VERTICAL,
            true,                            // legend
            true,                            // tooltips
            false                            // urls
        )

        // Save chart as PNG
        val outputFile = File("build/query-performance-chart.png")
        ChartUtils.saveChartAsPNG(outputFile, chart, 800, 600)
        println("Chart saved to: ${outputFile.absolutePath}")
    }
}
