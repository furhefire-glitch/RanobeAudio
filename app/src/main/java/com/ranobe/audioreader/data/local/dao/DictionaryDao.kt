package com.ranobe.audioreader.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.ranobe.audioreader.data.local.entity.ReplacementRuleEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DictionaryDao {
    @Query("SELECT * FROM replacement_rules ORDER BY sortOrder")
    fun observeAll(): Flow<List<ReplacementRuleEntity>>

    @Query("SELECT * FROM replacement_rules WHERE enabled = 1 ORDER BY sortOrder")
    suspend fun getEnabled(): List<ReplacementRuleEntity>

    @Insert
    suspend fun insert(rule: ReplacementRuleEntity): Long

    @Update
    suspend fun update(rule: ReplacementRuleEntity)

    @Delete
    suspend fun delete(rule: ReplacementRuleEntity)

    @Query("DELETE FROM replacement_rules WHERE pattern = :word AND isRegex = 0")
    suspend fun deleteLiteral(word: String)

    @androidx.room.Transaction
    suspend fun saveStress(word: String, accented: String) {
        deleteLiteral(word)
        insert(ReplacementRuleEntity(pattern = word, replacement = accented))
    }

    @Query("DELETE FROM replacement_rules")
    suspend fun deleteAll()
}
