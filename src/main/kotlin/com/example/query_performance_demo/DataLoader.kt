package com.example.query_performance_demo

import org.springframework.boot.CommandLineRunner
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
class DataLoader(
    private val jdbcTemplate: JdbcTemplate
) : CommandLineRunner {

    companion object {
        const val ACCOUNT_COUNT = 100
        const val ACCOUNT_GROUP_COUNT = 20
        const val SKILL_COUNT = 50
        const val DG_COUNT = 1_000_000
        const val TASK_COUNT = 1_000_000
        const val BATCH_SIZE = 10_000
    }

    override fun run(vararg args: String) {
        loadData()
    }

    @Transactional
    fun loadData() {
        println("Starting data load...")
        val startTime = System.currentTimeMillis()

        // Clear all tables first (in correct order due to foreign keys)
        println("Clearing existing data...")
        jdbcTemplate.execute("TRUNCATE TABLE distribution_group_matching, distribution_group_task, distribution_group, task, account_skill, account_to_account_group, skill, account_group, account RESTART IDENTITY CASCADE")

        // 1. Create accounts
        println("Creating $ACCOUNT_COUNT accounts...")
        createAccounts()

        // 2. Create account groups
        println("Creating $ACCOUNT_GROUP_COUNT account groups...")
        createAccountGroups()

        // 3. Link accounts to account groups
        println("Linking accounts to account groups...")
        linkAccountsToGroups()

        // 4. Create skills
        println("Creating $SKILL_COUNT skills...")
        createSkills()

        // 5. Link accounts to skills
        println("Linking accounts to skills...")
        linkAccountsToSkills()

        // 6. Create tasks
        println("Creating $TASK_COUNT tasks...")
        createTasks()

        // 7. Create distribution groups (all WAITING)
        println("Creating $DG_COUNT distribution groups...")
        createDistributionGroups()

        // 8. Link distribution groups to tasks
        println("Linking distribution groups to tasks...")
        linkDistributionGroupsToTasks()

        // 9. Create distribution group matchings (link to account_id, account_group_id, skill_code)
        println("Creating distribution group matchings...")
        createDistributionGroupMatchings()

        val elapsed = (System.currentTimeMillis() - startTime) / 1000.0
        println("Data load completed in $elapsed seconds")
    }

    private fun createAccounts() {
        val sql = "INSERT INTO account (name) VALUES (?)"
        jdbcTemplate.batchUpdate(sql, (1..ACCOUNT_COUNT).map { arrayOf("Account_$it") })
    }

    private fun createAccountGroups() {
        val sql = "INSERT INTO account_group (name) VALUES (?)"
        jdbcTemplate.batchUpdate(sql, (1..ACCOUNT_GROUP_COUNT).map { arrayOf("Group_$it") })
    }

    private fun linkAccountsToGroups() {
        // Each account belongs to 1-3 random groups
        val sql = "INSERT INTO account_to_account_group (account_id, account_group_id) VALUES (?, ?)"
        val links = mutableListOf<Array<Any>>()

        for (accountId in 1..ACCOUNT_COUNT) {
            val numGroups = (1..3).random()
            val groupIds = (1..ACCOUNT_GROUP_COUNT).shuffled().take(numGroups)
            for (groupId in groupIds) {
                links.add(arrayOf(accountId, groupId))
            }
        }

        jdbcTemplate.batchUpdate(sql, links)
    }

    private fun createSkills() {
        val sql = "INSERT INTO skill (code, translation_type, source_language, target_language) VALUES (?, ?, ?, ?)"
        val languages = listOf("EN", "KO", "JA", "ZH", "ES", "FR", "DE", "PT", "IT", "RU")

        val skills = mutableListOf<Array<Any>>()
        var skillNum = 1
        for (source in languages) {
            for (target in languages) {
                if (source != target && skillNum <= SKILL_COUNT) {
                    skills.add(arrayOf("SKILL_${source}_${target}", "SUBTITLE", source, target))
                    skillNum++
                }
            }
        }

        jdbcTemplate.batchUpdate(sql, skills)
    }

    private fun linkAccountsToSkills() {
        // Each account has 1-5 random skills
        val sql = "INSERT INTO account_skill (account_id, skill_id, skill_code) VALUES (?, ?, ?)"
        val links = mutableListOf<Array<Any>>()

        // Get skill codes
        val skillCodes = jdbcTemplate.queryForList(
            "SELECT id, code FROM skill",
        ).associate { it["id"] as Long to it["code"] as String }

        for (accountId in 1..ACCOUNT_COUNT) {
            val numSkills = (1..5).random()
            val selectedSkills = skillCodes.entries.shuffled().take(numSkills)
            for ((skillId, skillCode) in selectedSkills) {
                links.add(arrayOf(accountId, skillId, skillCode))
            }
        }

        jdbcTemplate.batchUpdate(sql, links)
    }

    private fun createTasks() {
        val sql = "INSERT INTO task DEFAULT VALUES"

        for (batch in 0 until TASK_COUNT / BATCH_SIZE) {
            val statements = (1..BATCH_SIZE).map { sql }
            jdbcTemplate.batchUpdate(*statements.toTypedArray())

            if ((batch + 1) % 10 == 0) {
                println("  Created ${(batch + 1) * BATCH_SIZE} tasks...")
            }
        }
    }

    private fun createDistributionGroups() {
        val sql = "INSERT INTO distribution_group (state) VALUES (?)"

        for (batch in 0 until DG_COUNT / BATCH_SIZE) {
            val values = (1..BATCH_SIZE).map { arrayOf<Any>("WAITING") }
            jdbcTemplate.batchUpdate(sql, values)

            if ((batch + 1) % 10 == 0) {
                println("  Created ${(batch + 1) * BATCH_SIZE} distribution groups...")
            }
        }
    }

    private fun linkDistributionGroupsToTasks() {
        // Each DG links to 1 task (1:1 mapping for simplicity)
        val sql = "INSERT INTO distribution_group_task (distribution_group_id, task_id) VALUES (?, ?)"

        for (batch in 0 until DG_COUNT / BATCH_SIZE) {
            val startId = batch * BATCH_SIZE + 1
            val values = (0 until BATCH_SIZE).map { i ->
                arrayOf<Any>(startId + i, startId + i)
            }
            jdbcTemplate.batchUpdate(sql, values)

            if ((batch + 1) % 10 == 0) {
                println("  Linked ${(batch + 1) * BATCH_SIZE} distribution groups to tasks...")
            }
        }
    }

    private fun createDistributionGroupMatchings() {
        // Distribute DGs across different matching types:
        // - 1/3 linked by ACCOUNT_ID
        // - 1/3 linked by ACCOUNT_GROUP_ID
        // - 1/3 linked by SKILL_CODE

        val sql = "INSERT INTO distribution_group_matching (distribution_group_id, pointer, type) VALUES (?, ?, ?)"

        // Get skill codes for reference
        val skillCodes = jdbcTemplate.queryForList("SELECT code FROM skill").map { it["code"] as String }

        for (batch in 0 until DG_COUNT / BATCH_SIZE) {
            val startId = batch * BATCH_SIZE + 1
            val values = (0 until BATCH_SIZE).map { i ->
                val dgId = startId + i
                val matchType = when ((dgId % 3).toInt()) {
                    0 -> {
                        // ACCOUNT_ID matching
                        val accountId = ((dgId % ACCOUNT_COUNT) + 1)
                        arrayOf<Any>(dgId, accountId.toString(), "ACCOUNT_ID")
                    }
                    1 -> {
                        // ACCOUNT_GROUP_ID matching
                        val groupId = ((dgId % ACCOUNT_GROUP_COUNT) + 1)
                        arrayOf<Any>(dgId, groupId.toString(), "ACCOUNT_GROUP_ID")
                    }
                    else -> {
                        // SKILL_CODE matching
                        val skillCode = skillCodes[(dgId % skillCodes.size).toInt()]
                        arrayOf<Any>(dgId, skillCode, "SKILL_CODE")
                    }
                }
                matchType
            }
            jdbcTemplate.batchUpdate(sql, values)

            if ((batch + 1) % 10 == 0) {
                println("  Created ${(batch + 1) * BATCH_SIZE} distribution group matchings...")
            }
        }
    }
}
