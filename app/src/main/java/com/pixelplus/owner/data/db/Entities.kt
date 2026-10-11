package com.pixelplus.owner.data.db

import android.database.sqlite.SQLiteConstraintException
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Local notebook for one phone.
 *
 * Names and phones are stored only as opaque bytes. This milestone does not
 * seal those bytes with the Keystore; no screen writes them yet. The columns
 * reject a short value so a plain name cannot be saved by mistake.
 * Tokens, licence codes and payment references are not columns.
 *
 * A row is hidden by [deletedAtUtcMs]. Nothing here is removed.
 */

internal object RecordRules {
    const val CIPHER_MIN = 29
    const val NAME_CIPHER_MAX = 1024
    const val PHONE_CIPHER_MAX = 512
    const val LABEL_MAX = 80
    const val TRIAL_MIN = 1
    const val TRIAL_MAX = 60
    const val TOKEN_MIN = 32
    const val TOKEN_MAX = 128
    const val CAPABILITIES_MAX = 4000
    const val URL_MAX = 200

    const val STATUS_ACTIVE = "active"
    const val STATUS_PAUSED = "paused"
    const val SOURCE_LOCAL = "local"
    const val SOURCE_HUB = "hub"
    const val REMOTE_INSTALL = "install"
    const val REMOTE_LICENCE_REQUEST = "licence_request"

    val STATUSES = setOf(STATUS_ACTIVE, STATUS_PAUSED)
    val SOURCES = setOf(SOURCE_LOCAL, SOURCE_HUB)
    val REMOTE_TYPES = setOf(REMOTE_INSTALL, REMOTE_LICENCE_REQUEST)

    private val ID_RE = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
    private val CODE_RE = Regex("^[a-z0-9][a-z0-9._-]{0,63}$")
    private val TOKEN_RE = Regex("^[0-9a-f]{$TOKEN_MIN,$TOKEN_MAX}$")
    private val REMOTE_ID_RE = Regex("^[A-Za-z0-9._:-]{1,128}$")
    private val KEY_RE = Regex("^[a-z][a-z0-9_]{0,32}$")
    private val BANNED_KEY = listOf(
        "token", "secret", "password", "bearer", "auth", "key", "cookie", "session", "credential",
    )

    fun validId(value: String): Boolean = ID_RE.matches(value)

    fun label(raw: String): String? {
        val value = raw.trim()
        if (value.length !in 1..LABEL_MAX) return null
        if (value.any { it.isISOControl() }) return null
        return value
    }

    fun currency(raw: String): String? {
        val value = raw.trim()
        if (value.length != 3 || value.any { it !in 'A'..'Z' }) return null
        return value
    }

    fun productCode(raw: String): String? {
        val value = raw.trim()
        if (!CODE_RE.matches(value)) return null
        return value
    }

    fun trialDays(value: Int?): Boolean = value == null || value in TRIAL_MIN..TRIAL_MAX

    fun requiredTrialDays(value: Int): Boolean = value in TRIAL_MIN..TRIAL_MAX

    fun ciphertext(value: ByteArray, max: Int): Boolean = value.size in CIPHER_MIN..max

    fun searchToken(value: String?): Boolean = value == null || TOKEN_RE.matches(value)

    fun remoteId(value: String): Boolean = REMOTE_ID_RE.matches(value)

    /** Compact JSON object of booleans and small integers. No strings, so a secret cannot hide in it. */
    fun capabilities(raw: String): String? {
        val text = raw.trim()
        if (text.length > CAPABILITIES_MAX || !text.startsWith('{') || !text.endsWith('}')) return null
        val body = text.substring(1, text.length - 1).trim()
        if (body.isEmpty()) return "{}"
        val parts = body.split(',')
        if (parts.size > 20) return null
        val pairs = linkedMapOf<String, String>()
        for (part in parts) {
            val colon = part.indexOf(':')
            if (colon <= 0) return null
            val keyRaw = part.substring(0, colon).trim()
            val valueRaw = part.substring(colon + 1).trim()
            if (keyRaw.length < 3 || !keyRaw.startsWith('"') || !keyRaw.endsWith('"')) return null
            val key = keyRaw.substring(1, keyRaw.length - 1)
            if (!KEY_RE.matches(key) || BANNED_KEY.any { key.contains(it) } || key in pairs) return null
            val value = when (valueRaw) {
                "true", "false" -> valueRaw
                else -> {
                    if (valueRaw.isEmpty() || valueRaw.length > 6) return null
                    if (valueRaw.length > 1 && valueRaw.startsWith('0')) return null
                    if (valueRaw.any { it !in '0'..'9' }) return null
                    valueRaw
                }
            }
            pairs[key] = value
        }
        return pairs.toSortedMap().entries.joinToString(",", "{", "}") { (key, value) ->
            "\"$key\":$value"
        }
    }

    fun constraint(error: SQLiteConstraintException): RecordResult {
        val message = error.message.orEmpty()
        return when {
            "UNIQUE constraint failed" in message -> RecordResult.Conflict
            "workspace_mismatch" in message -> RecordResult.WorkspaceMismatch
            "parent_inactive" in message -> RecordResult.InactiveParent
            else -> RecordResult.Rejected
        }
    }
}

enum class RecordResult {
    Applied,
    Missing,
    NotDeleted,
    AlreadyActive,
    Conflict,
    InactiveParent,
    WorkspaceMismatch,
    Rejected,
}

@Entity(
    tableName = "workspaces",
    indices = [Index(value = ["name"], name = "index_workspaces_name")],
)
data class WorkspaceEntity(
    @PrimaryKey val id: String,
    val name: String,
    @ColumnInfo(defaultValue = "'EGP'") val defaultCurrency: String,
    val createdAtUtcMs: Long,
    val updatedAtUtcMs: Long,
    @ColumnInfo(defaultValue = "1") val revision: Long,
)

@Entity(
    tableName = "hub_profiles",
    foreignKeys = [
        ForeignKey(
            entity = WorkspaceEntity::class,
            parentColumns = ["id"],
            childColumns = ["workspaceId"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.RESTRICT,
        ),
    ],
    indices = [Index(value = ["workspaceId"], name = "index_hub_profiles_workspace")],
)
data class HubProfileEntity(
    @PrimaryKey val id: String,
    val workspaceId: String,
    val createdAtUtcMs: Long,
    val updatedAtUtcMs: Long,
    @ColumnInfo(defaultValue = "1") val revision: Long,
    val deletedAtUtcMs: Long?,
    val normalizedUrl: String,
    @ColumnInfo(defaultValue = "1") val originEpoch: Long,
    @ColumnInfo(defaultValue = "'{}'") val capabilitiesJson: String,
    val lastTestUtcMs: Long?,
    /** Counts credential replacements. The token itself is never stored. */
    @ColumnInfo(defaultValue = "1") val credentialEpoch: Long = 1,
    /** 1 while a replaced credential must be checked before any send. */
    @ColumnInfo(defaultValue = "0") val dispatchPaused: Int = 0,
)

@Entity(
    tableName = "customers",
    foreignKeys = [
        ForeignKey(
            entity = WorkspaceEntity::class,
            parentColumns = ["id"],
            childColumns = ["workspaceId"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(
            value = ["workspaceId", "deletedAtUtcMs", "localStatus"],
            name = "index_customers_workspace_status",
        ),
    ],
)
data class CustomerEntity(
    @PrimaryKey val id: String,
    val workspaceId: String,
    val createdAtUtcMs: Long,
    val updatedAtUtcMs: Long,
    @ColumnInfo(defaultValue = "1") val revision: Long,
    val deletedAtUtcMs: Long?,
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB) val encryptedDisplayName: ByteArray,
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB) val encryptedPhone: ByteArray?,
    val normalizedSearchToken: String?,
    @ColumnInfo(defaultValue = "'active'") val localStatus: String,
)

@Entity(
    tableName = "branches",
    foreignKeys = [
        ForeignKey(
            entity = WorkspaceEntity::class,
            parentColumns = ["id"],
            childColumns = ["workspaceId"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = CustomerEntity::class,
            parentColumns = ["id"],
            childColumns = ["customerId"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["customerId"], name = "index_branches_customer"),
        Index(value = ["workspaceId"], name = "index_branches_workspace"),
    ],
)
data class BranchEntity(
    @PrimaryKey val id: String,
    val workspaceId: String,
    val createdAtUtcMs: Long,
    val updatedAtUtcMs: Long,
    @ColumnInfo(defaultValue = "1") val revision: Long,
    val deletedAtUtcMs: Long?,
    val customerId: String,
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB) val encryptedLabel: ByteArray,
    @ColumnInfo(defaultValue = "0") val isDefault: Boolean,
)

@Entity(
    tableName = "products",
    foreignKeys = [
        ForeignKey(
            entity = WorkspaceEntity::class,
            parentColumns = ["id"],
            childColumns = ["workspaceId"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.RESTRICT,
        ),
    ],
    indices = [Index(value = ["workspaceId"], name = "index_products_workspace")],
)
data class ProductEntity(
    @PrimaryKey val id: String,
    val workspaceId: String,
    val createdAtUtcMs: Long,
    val updatedAtUtcMs: Long,
    @ColumnInfo(defaultValue = "1") val revision: Long,
    val deletedAtUtcMs: Long?,
    val productCode: String,
    val displayName: String,
    val localTrialDays: Int,
    val remoteTrialDays: Int?,
    val policyVersion: Int?,
    @ColumnInfo(defaultValue = "'local'") val source: String,
)

@Entity(
    tableName = "customer_links",
    foreignKeys = [
        ForeignKey(
            entity = WorkspaceEntity::class,
            parentColumns = ["id"],
            childColumns = ["workspaceId"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = CustomerEntity::class,
            parentColumns = ["id"],
            childColumns = ["customerId"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = HubProfileEntity::class,
            parentColumns = ["id"],
            childColumns = ["hubProfileId"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["customerId"], name = "index_customer_links_customer"),
        Index(value = ["hubProfileId"], name = "index_customer_links_profile"),
        Index(value = ["workspaceId"], name = "index_customer_links_workspace"),
    ],
)
data class CustomerLinkEntity(
    @PrimaryKey val id: String,
    val workspaceId: String,
    val createdAtUtcMs: Long,
    val updatedAtUtcMs: Long,
    @ColumnInfo(defaultValue = "1") val revision: Long,
    val deletedAtUtcMs: Long?,
    val customerId: String,
    val hubProfileId: String,
    val remoteType: String,
    val remoteId: String,
    val linkedBy: String,
)
