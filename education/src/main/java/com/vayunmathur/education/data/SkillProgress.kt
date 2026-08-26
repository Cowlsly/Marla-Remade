package com.vayunmathur.education.data

import androidx.room3.Entity
import androidx.room3.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * Per-skill progress. Stars are a *lite* mastery signal (0-3), not spaced
 * repetition: the best star result achieved on that skill's exercises.
 */
@Serializable
@Entity
data class SkillProgress(
    @PrimaryKey val skillId: String,
    val stars: Int = 0,
    val attempts: Int = 0,
    val lastPracticedEpochDay: Long = 0,
)
