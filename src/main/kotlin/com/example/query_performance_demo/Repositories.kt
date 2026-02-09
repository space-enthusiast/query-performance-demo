package com.example.query_performance_demo

import org.springframework.data.jpa.repository.JpaRepository

interface AccountRepository : JpaRepository<Account, Long>
interface AccountGroupRepository : JpaRepository<AccountGroup, Long>
interface AccountToAccountGroupRepository : JpaRepository<AccountToAccountGroup, Long>
interface SkillRepository : JpaRepository<Skill, Long>
interface AccountSkillRepository : JpaRepository<AccountSkill, Long>
interface TaskRepository : JpaRepository<Task, Long>
interface DistributionGroupRepository : JpaRepository<DistributionGroup, Long>
interface DistributionGroupTaskRepository : JpaRepository<DistributionGroupTask, Long>
interface DistributionGroupMatchingRepository : JpaRepository<DistributionGroupMatching, Long>
