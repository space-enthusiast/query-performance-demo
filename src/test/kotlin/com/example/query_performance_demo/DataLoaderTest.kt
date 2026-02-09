package com.example.query_performance_demo

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@SpringBootTest
class DataLoaderTest {

    @Autowired
    lateinit var dataLoader: DataLoader

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Test
    fun `load test data and verify counts`() {
        // Load data
        dataLoader.loadData()

        // Verify accounts
        val accountCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM account", Long::class.java
        ) ?: 0
        assertTrue(accountCount >= 100, "Expected at least 100 accounts, got $accountCount")

        // Verify account groups
        val groupCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM account_group", Long::class.java
        ) ?: 0
        assertTrue(groupCount >= 20, "Expected at least 20 account groups, got $groupCount")

        // Verify account to group links
        val accountGroupLinkCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM account_to_account_group", Long::class.java
        ) ?: 0
        assertTrue(accountGroupLinkCount >= 100, "Expected at least 100 account-group links, got $accountGroupLinkCount")

        // Verify skills
        val skillCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM skill", Long::class.java
        ) ?: 0
        assertTrue(skillCount >= 50, "Expected at least 50 skills, got $skillCount")

        // Verify account skills
        val accountSkillCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM account_skill", Long::class.java
        ) ?: 0
        assertTrue(accountSkillCount >= 100, "Expected at least 100 account-skill links, got $accountSkillCount")

        // Verify tasks
        val taskCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM task", Long::class.java
        ) ?: 0
        assertEquals(1_000_000, taskCount, "Expected 1,000,000 tasks")

        // Verify distribution groups
        val dgCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM distribution_group", Long::class.java
        ) ?: 0
        assertEquals(1_000_000, dgCount, "Expected 1,000,000 distribution groups")

        // Verify all DGs are WAITING
        val waitingCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM distribution_group WHERE state = 'WAITING'", Long::class.java
        ) ?: 0
        assertEquals(dgCount, waitingCount, "All distribution groups should be WAITING")

        // Verify distribution group tasks
        val dgTaskCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM distribution_group_task", Long::class.java
        ) ?: 0
        assertEquals(1_000_000, dgTaskCount, "Expected 1,000,000 distribution group tasks")

        // Verify distribution group matchings
        val dgMatchingCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM distribution_group_matching", Long::class.java
        ) ?: 0
        assertEquals(1_000_000, dgMatchingCount, "Expected 1,000,000 distribution group matchings")

        // Verify matching types distribution
        val accountIdMatchCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM distribution_group_matching WHERE type = 'ACCOUNT_ID'", Long::class.java
        ) ?: 0
        val groupIdMatchCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM distribution_group_matching WHERE type = 'ACCOUNT_GROUP_ID'", Long::class.java
        ) ?: 0
        val skillCodeMatchCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM distribution_group_matching WHERE type = 'SKILL_CODE'", Long::class.java
        ) ?: 0

        println("=== Data Summary ===")
        println("Accounts: $accountCount")
        println("Account Groups: $groupCount")
        println("Account-Group Links: $accountGroupLinkCount")
        println("Skills: $skillCount")
        println("Account-Skill Links: $accountSkillCount")
        println("Tasks: $taskCount")
        println("Distribution Groups: $dgCount (all WAITING)")
        println("DG-Task Links: $dgTaskCount")
        println("DG Matchings:")
        println("  - ACCOUNT_ID: $accountIdMatchCount")
        println("  - ACCOUNT_GROUP_ID: $groupIdMatchCount")
        println("  - SKILL_CODE: $skillCodeMatchCount")
    }
}
