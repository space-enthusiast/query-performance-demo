package com.example.query_performance_demo

import jakarta.persistence.*

enum class DistributionGroupState {
    DONE,
    WAITING,
    ASSIGNED
}

enum class TranslationType {
    SUBTITLE
}

enum class MatchingType {
    ACCOUNT_ID,
    ACCOUNT_GROUP_ID,
    SKILL_CODE
}

@Entity
@Table(name = "account")
class Account(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(nullable = false)
    val name: String,

    @OneToMany(mappedBy = "account", cascade = [CascadeType.ALL], orphanRemoval = true)
    val accountGroups: MutableSet<AccountToAccountGroup> = mutableSetOf(),

    @OneToMany(mappedBy = "account", cascade = [CascadeType.ALL], orphanRemoval = true)
    val accountSkills: MutableSet<AccountSkill> = mutableSetOf()
)

@Entity
@Table(name = "account_group")
class AccountGroup(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(nullable = false)
    val name: String,

    @OneToMany(mappedBy = "accountGroup", cascade = [CascadeType.ALL], orphanRemoval = true)
    val accounts: MutableSet<AccountToAccountGroup> = mutableSetOf()
)

@Entity
@Table(name = "account_to_account_group")
class AccountToAccountGroup(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id", nullable = false)
    val account: Account,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_group_id", nullable = false)
    val accountGroup: AccountGroup
)

@Entity
@Table(name = "skill")
class Skill(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(nullable = false, unique = true)
    val code: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "translation_type", nullable = false)
    val translationType: TranslationType,

    @Column(name = "source_language", nullable = false)
    val sourceLanguage: String,

    @Column(name = "target_language", nullable = false)
    val targetLanguage: String,

    @OneToMany(mappedBy = "skill", cascade = [CascadeType.ALL], orphanRemoval = true)
    val accountSkills: MutableSet<AccountSkill> = mutableSetOf()
)

@Entity
@Table(name = "account_skill")
class AccountSkill(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id", nullable = false)
    val account: Account,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "skill_id", nullable = false)
    val skill: Skill,

    @Column(name = "skill_code", nullable = false)
    val skillCode: String
)

@Entity
@Table(name = "task")
class Task(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @OneToMany(mappedBy = "task", cascade = [CascadeType.ALL], orphanRemoval = true)
    val distributionGroups: MutableSet<DistributionGroupTask> = mutableSetOf()
)

@Entity
@Table(name = "distribution_group")
class DistributionGroup(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    val state: DistributionGroupState,

    @OneToMany(mappedBy = "distributionGroup", cascade = [CascadeType.ALL], orphanRemoval = true)
    val tasks: MutableSet<DistributionGroupTask> = mutableSetOf(),

    @OneToMany(mappedBy = "distributionGroup", cascade = [CascadeType.ALL], orphanRemoval = true)
    val matchings: MutableSet<DistributionGroupMatching> = mutableSetOf()
)

@Entity
@Table(name = "distribution_group_task")
class DistributionGroupTask(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "distribution_group_id", nullable = false)
    val distributionGroup: DistributionGroup,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "task_id", nullable = false)
    val task: Task
)

@Entity
@Table(name = "distribution_group_matching")
class DistributionGroupMatching(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "distribution_group_id", nullable = false)
    val distributionGroup: DistributionGroup,

    @Column(nullable = false)
    val pointer: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    val type: MatchingType
)
