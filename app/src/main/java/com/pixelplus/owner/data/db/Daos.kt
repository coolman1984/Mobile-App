package com.pixelplus.owner.data.db

import android.database.sqlite.SQLiteConstraintException
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import java.util.concurrent.Callable
import com.pixelplus.owner.UrlResult
import com.pixelplus.owner.audit.AuditCanonical
import com.pixelplus.owner.audit.AuditVocab
import com.pixelplus.owner.audit.JsonValue
import com.pixelplus.owner.domain.Undo
import com.pixelplus.owner.domain.UndoChannel
import com.pixelplus.owner.domain.UndoDecision
import com.pixelplus.owner.domain.UndoOutcome
import com.pixelplus.owner.domain.UndoRequest
import com.pixelplus.owner.normalizeHubUrl

private const val OP_HIDE = "hide"
private const val OP_RESTORE = "restore"
private const val OP_STATUS = "status"

/**
 * Reads and writes for the local notebook.
 * Callers pass the time. The database does not read the device clock.
 * There is no delete method.
 */
@Dao
interface OwnerDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertWorkspace(row: WorkspaceEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertHubProfile(row: HubProfileEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertCustomer(row: CustomerEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertBranch(row: BranchEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertProduct(row: ProductEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertLink(row: CustomerLinkEntity)

    @Query("SELECT * FROM workspaces WHERE id = :id")
    fun workspace(id: String): WorkspaceEntity?

    @Query("SELECT count(*) FROM workspaces")
    fun workspaceCount(): Int

    @Query("SELECT count(*) FROM customers")
    fun customerCount(): Int

    @Query("SELECT count(*) FROM products")
    fun productCount(): Int

    @Query("SELECT count(*) FROM customer_links")
    fun linkCount(): Int

    @Query("SELECT count(*) FROM branches")
    fun branchCount(): Int

    @Query("SELECT count(*) FROM hub_profiles")
    fun hubCount(): Int

    @Query(
        """
        UPDATE workspaces
        SET name = :name, updatedAtUtcMs = :nowUtcMs, revision = revision + 1
        WHERE id = :id
        """,
    )
    fun renameWorkspace(id: String, name: String, nowUtcMs: Long): Int

    @Query("SELECT * FROM hub_profiles WHERE id = :id")
    fun hubProfile(id: String): HubProfileEntity?

    @Query(
        """
        SELECT * FROM hub_profiles
        WHERE workspaceId = :workspaceId AND normalizedUrl = :url AND deletedAtUtcMs IS NULL AND id != :exceptId
        LIMIT 1
        """,
    )
    fun otherActiveHub(workspaceId: String, url: String, exceptId: String): HubProfileEntity?

    @Query(
        """
        UPDATE hub_profiles
        SET normalizedUrl = :url, originEpoch = :originEpoch, updatedAtUtcMs = :nowUtcMs, revision = revision + 1
        WHERE id = :id AND deletedAtUtcMs IS NULL
        """,
    )
    fun changeHubOrigin(id: String, url: String, originEpoch: Long, nowUtcMs: Long): Int

    @Query(
        """
        UPDATE hub_profiles
        SET lastTestUtcMs = :testedAtUtcMs, updatedAtUtcMs = :nowUtcMs, revision = revision + 1
        WHERE id = :id AND deletedAtUtcMs IS NULL
        """,
    )
    fun markHubTested(id: String, testedAtUtcMs: Long, nowUtcMs: Long): Int

    @Query("SELECT * FROM customers WHERE id = :id")
    fun customer(id: String): CustomerEntity?

    @Query(
        """
        SELECT * FROM customers
        WHERE workspaceId = :workspaceId AND deletedAtUtcMs IS NULL
        ORDER BY id
        """,
    )
    fun activeCustomers(workspaceId: String): List<CustomerEntity>

    @Query(
        """
        SELECT * FROM customers
        WHERE workspaceId = :workspaceId AND normalizedSearchToken = :token
        AND deletedAtUtcMs IS NULL AND id != :exceptId
        LIMIT 1
        """,
    )
    fun otherActiveCustomer(workspaceId: String, token: String, exceptId: String): CustomerEntity?

    @Query(
        """
        UPDATE customers
        SET localStatus = :status, updatedAtUtcMs = :nowUtcMs, revision = revision + 1
        WHERE id = :id AND deletedAtUtcMs IS NULL
        """,
    )
    fun setCustomerStatus(id: String, status: String, nowUtcMs: Long): Int

    @Query("SELECT * FROM branches WHERE id = :id")
    fun branch(id: String): BranchEntity?

    @Query(
        """
        SELECT branches.* FROM branches
        JOIN customers ON customers.id = branches.customerId
        WHERE branches.workspaceId = :workspaceId
        AND branches.deletedAtUtcMs IS NULL
        AND customers.deletedAtUtcMs IS NULL
        ORDER BY branches.id
        """,
    )
    fun activeBranches(workspaceId: String): List<BranchEntity>

    @Query(
        """
        SELECT * FROM branches
        WHERE customerId = :customerId AND isDefault = 1 AND deletedAtUtcMs IS NULL AND id != :exceptId
        LIMIT 1
        """,
    )
    fun otherDefaultBranch(customerId: String, exceptId: String): BranchEntity?

    @Query("SELECT * FROM products WHERE id = :id")
    fun product(id: String): ProductEntity?

    @Query(
        """
        SELECT * FROM products
        WHERE workspaceId = :workspaceId AND deletedAtUtcMs IS NULL
        ORDER BY productCode, id
        """,
    )
    fun activeProducts(workspaceId: String): List<ProductEntity>

    @Query(
        """
        SELECT * FROM products
        WHERE workspaceId = :workspaceId AND productCode = :productCode
        AND deletedAtUtcMs IS NULL AND id != :exceptId
        LIMIT 1
        """,
    )
    fun otherActiveProduct(workspaceId: String, productCode: String, exceptId: String): ProductEntity?

    @Query("SELECT * FROM customer_links WHERE id = :id")
    fun link(id: String): CustomerLinkEntity?

    @Query(
        """
        SELECT links.* FROM customer_links AS links
        JOIN customers ON customers.id = links.customerId
        JOIN hub_profiles ON hub_profiles.id = links.hubProfileId
        WHERE links.workspaceId = :workspaceId
        AND links.deletedAtUtcMs IS NULL
        AND customers.deletedAtUtcMs IS NULL
        AND hub_profiles.deletedAtUtcMs IS NULL
        ORDER BY links.id
        """,
    )
    fun activeLinks(workspaceId: String): List<CustomerLinkEntity>

    @Query(
        """
        SELECT * FROM customer_links
        WHERE hubProfileId = :hubProfileId AND remoteType = :remoteType AND remoteId = :remoteId
        AND deletedAtUtcMs IS NULL AND id != :exceptId
        LIMIT 1
        """,
    )
    fun otherActiveLink(
        hubProfileId: String,
        remoteType: String,
        remoteId: String,
        exceptId: String,
    ): CustomerLinkEntity?

    @Query(
        """
        UPDATE hub_profiles
        SET deletedAtUtcMs = :nowUtcMs, updatedAtUtcMs = :nowUtcMs, revision = revision + 1
        WHERE id = :id AND deletedAtUtcMs IS NULL
        """,
    )
    fun hideHub(id: String, nowUtcMs: Long): Int

    @Query(
        """
        UPDATE hub_profiles
        SET deletedAtUtcMs = NULL, updatedAtUtcMs = :nowUtcMs, revision = revision + 1
        WHERE id = :id AND deletedAtUtcMs IS NOT NULL
        """,
    )
    fun restoreHub(id: String, nowUtcMs: Long): Int

    @Query(
        """
        UPDATE customers
        SET deletedAtUtcMs = :nowUtcMs, updatedAtUtcMs = :nowUtcMs, revision = revision + 1
        WHERE id = :id AND deletedAtUtcMs IS NULL
        """,
    )
    fun hideCustomer(id: String, nowUtcMs: Long): Int

    @Query(
        """
        UPDATE customers
        SET deletedAtUtcMs = NULL, updatedAtUtcMs = :nowUtcMs, revision = revision + 1
        WHERE id = :id AND deletedAtUtcMs IS NOT NULL
        """,
    )
    fun restoreCustomer(id: String, nowUtcMs: Long): Int

    @Query(
        """
        UPDATE branches
        SET deletedAtUtcMs = :nowUtcMs, updatedAtUtcMs = :nowUtcMs, revision = revision + 1
        WHERE id = :id AND deletedAtUtcMs IS NULL
        """,
    )
    fun hideBranch(id: String, nowUtcMs: Long): Int

    @Query(
        """
        UPDATE branches
        SET deletedAtUtcMs = NULL, updatedAtUtcMs = :nowUtcMs, revision = revision + 1
        WHERE id = :id AND deletedAtUtcMs IS NOT NULL
        """,
    )
    fun restoreBranch(id: String, nowUtcMs: Long): Int

    @Query(
        """
        UPDATE products
        SET deletedAtUtcMs = :nowUtcMs, updatedAtUtcMs = :nowUtcMs, revision = revision + 1
        WHERE id = :id AND deletedAtUtcMs IS NULL
        """,
    )
    fun hideProduct(id: String, nowUtcMs: Long): Int

    @Query(
        """
        UPDATE products
        SET deletedAtUtcMs = NULL, updatedAtUtcMs = :nowUtcMs, revision = revision + 1
        WHERE id = :id AND deletedAtUtcMs IS NOT NULL
        """,
    )
    fun restoreProduct(id: String, nowUtcMs: Long): Int

    @Query(
        """
        UPDATE customer_links
        SET deletedAtUtcMs = :nowUtcMs, updatedAtUtcMs = :nowUtcMs, revision = revision + 1
        WHERE id = :id AND deletedAtUtcMs IS NULL
        """,
    )
    fun hideLink(id: String, nowUtcMs: Long): Int

    @Query(
        """
        UPDATE customer_links
        SET deletedAtUtcMs = NULL, updatedAtUtcMs = :nowUtcMs, revision = revision + 1
        WHERE id = :id AND deletedAtUtcMs IS NOT NULL
        """,
    )
    fun restoreLink(id: String, nowUtcMs: Long): Int
}

internal class OwnerRecords(private val database: OwnerDatabase) {
    private val dao: OwnerDao get() = database.ownerDao()

    fun createWorkspace(
        id: String,
        name: String,
        nowUtcMs: Long,
        defaultCurrency: String = "EGP",
    ): RecordResult {
        val cleanName = RecordRules.label(name)
        val cleanCurrency = RecordRules.currency(defaultCurrency)
        if (!RecordRules.validId(id) || cleanName == null || cleanCurrency == null || nowUtcMs < 0) {
            return RecordResult.Rejected
        }
        val row = WorkspaceEntity(
            id = id,
            name = cleanName,
            defaultCurrency = cleanCurrency,
            createdAtUtcMs = nowUtcMs,
            updatedAtUtcMs = nowUtcMs,
            revision = 1,
        )
        return write {
            dao.insertWorkspace(row)
            note(
                AuditVocab.SCREEN_NOTEBOOK,
                AuditVocab.ACTION_CREATE,
                AuditVocab.ENTITY_WORKSPACE,
                id,
                id,
                nowUtcMs,
                after = mapOf("revision" to rev(1), "currency" to word(cleanCurrency)),
            )
            RecordResult.Applied
        }
    }

    fun renameWorkspace(id: String, name: String, nowUtcMs: Long): RecordResult {
        val cleanName = RecordRules.label(name)
        if (!RecordRules.validId(id) || cleanName == null || nowUtcMs < 0) return RecordResult.Rejected
        return write {
            val row = dao.workspace(id) ?: return@write RecordResult.Missing
            if (nowUtcMs < row.createdAtUtcMs) return@write RecordResult.Rejected
            if (row.name == cleanName) return@write RecordResult.AlreadyActive
            if (dao.renameWorkspace(id, cleanName, nowUtcMs) != 1) error("workspace rename did not write one row")
            note(
                AuditVocab.SCREEN_NOTEBOOK,
                AuditVocab.ACTION_UPDATE,
                AuditVocab.ENTITY_WORKSPACE,
                id,
                id,
                nowUtcMs,
                before = mapOf("revision" to rev(row.revision), "renamed" to bit(false)),
                after = mapOf("revision" to rev(row.revision + 1), "renamed" to bit(true)),
            )
            RecordResult.Applied
        }
    }

    fun createHubProfile(
        id: String,
        workspaceId: String,
        rawUrl: String,
        nowUtcMs: Long,
        originEpoch: Long = 1,
        capabilitiesJson: String = "{}",
    ): RecordResult {
        val url = normalizedUrl(rawUrl)
        val capabilities = RecordRules.capabilities(capabilitiesJson)
        if (!RecordRules.validId(id) || !RecordRules.validId(workspaceId) || url == null || capabilities == null) {
            return RecordResult.Rejected
        }
        if (nowUtcMs < 0 || originEpoch < 1) return RecordResult.Rejected
        val row = HubProfileEntity(
            id = id,
            workspaceId = workspaceId,
            createdAtUtcMs = nowUtcMs,
            updatedAtUtcMs = nowUtcMs,
            revision = 1,
            deletedAtUtcMs = null,
            normalizedUrl = url,
            originEpoch = originEpoch,
            capabilitiesJson = capabilities,
            lastTestUtcMs = null,
        )
        return write {
            if (dao.workspace(workspaceId) == null) return@write RecordResult.Rejected
            if (dao.otherActiveHub(workspaceId, url, id) != null) {
                note(
                    AuditVocab.SCREEN_HUB,
                    AuditVocab.ACTION_CREATE,
                    AuditVocab.ENTITY_HUB,
                    id,
                    workspaceId,
                    nowUtcMs,
                    result = AuditVocab.RESULT_CONFLICT,
                    reasonCode = "duplicate",
                )
                return@write RecordResult.Conflict
            }
            dao.insertHubProfile(row)
            note(
                AuditVocab.SCREEN_HUB,
                AuditVocab.ACTION_CREATE,
                AuditVocab.ENTITY_HUB,
                id,
                workspaceId,
                nowUtcMs,
                after = mapOf(
                    "revision" to rev(1),
                    "normalizedUrl" to word(url),
                    "originEpoch" to rev(originEpoch),
                    "deleted" to bit(false),
                ),
                hubProfileId = id,
                undo = localUndo(1, OP_HIDE),
            )
            RecordResult.Applied
        }
    }

    fun changeHubOrigin(id: String, rawUrl: String, originEpoch: Long, nowUtcMs: Long): RecordResult {
        val url = normalizedUrl(rawUrl)
        if (!RecordRules.validId(id) || url == null || originEpoch < 1 || nowUtcMs < 0) return RecordResult.Rejected
        return write {
            val row = dao.hubProfile(id) ?: return@write RecordResult.Missing
            if (row.deletedAtUtcMs != null) return@write RecordResult.NotDeleted
            if (nowUtcMs < row.createdAtUtcMs || originEpoch <= row.originEpoch) return@write RecordResult.Rejected
            if (dao.otherActiveHub(row.workspaceId, url, id) != null) {
                note(
                    AuditVocab.SCREEN_HUB,
                    AuditVocab.ACTION_UPDATE,
                    AuditVocab.ENTITY_HUB,
                    id,
                    row.workspaceId,
                    nowUtcMs,
                    result = AuditVocab.RESULT_CONFLICT,
                    reasonCode = "duplicate",
                    hubProfileId = id,
                )
                return@write RecordResult.Conflict
            }
            if (dao.changeHubOrigin(id, url, originEpoch, nowUtcMs) != 1) error("hub origin did not write one row")
            note(
                AuditVocab.SCREEN_HUB,
                AuditVocab.ACTION_UPDATE,
                AuditVocab.ENTITY_HUB,
                id,
                row.workspaceId,
                nowUtcMs,
                before = mapOf("originEpoch" to rev(row.originEpoch), "revision" to rev(row.revision)),
                after = mapOf(
                    "originEpoch" to rev(originEpoch),
                    "normalizedUrl" to word(url),
                    "revision" to rev(row.revision + 1),
                ),
                hubProfileId = id,
            )
            RecordResult.Applied
        }
    }

    fun markHubTested(id: String, testedAtUtcMs: Long, nowUtcMs: Long): RecordResult {
        if (!RecordRules.validId(id) || testedAtUtcMs < 0 || nowUtcMs < 0) return RecordResult.Rejected
        return write {
            val row = dao.hubProfile(id) ?: return@write RecordResult.Missing
            if (row.deletedAtUtcMs != null) return@write RecordResult.NotDeleted
            if (nowUtcMs < row.createdAtUtcMs) return@write RecordResult.Rejected
            if (dao.markHubTested(id, testedAtUtcMs, nowUtcMs) != 1) error("hub test mark did not write one row")
            note(
                AuditVocab.SCREEN_HUB,
                AuditVocab.ACTION_UPDATE,
                AuditVocab.ENTITY_HUB,
                id,
                row.workspaceId,
                nowUtcMs,
                before = mapOf("tested" to bit(false), "revision" to rev(row.revision)),
                after = mapOf("tested" to bit(true), "revision" to rev(row.revision + 1)),
                hubProfileId = id,
            )
            RecordResult.Applied
        }
    }

    fun createCustomer(
        id: String,
        workspaceId: String,
        encryptedDisplayName: ByteArray,
        nowUtcMs: Long,
        encryptedPhone: ByteArray? = null,
        normalizedSearchToken: String? = null,
        localStatus: String = RecordRules.STATUS_ACTIVE,
    ): RecordResult {
        if (!customerShape(id, workspaceId, encryptedDisplayName, encryptedPhone, normalizedSearchToken, localStatus, nowUtcMs)) {
            return RecordResult.Rejected
        }
        val row = CustomerEntity(
            id = id,
            workspaceId = workspaceId,
            createdAtUtcMs = nowUtcMs,
            updatedAtUtcMs = nowUtcMs,
            revision = 1,
            deletedAtUtcMs = null,
            encryptedDisplayName = encryptedDisplayName,
            encryptedPhone = encryptedPhone,
            normalizedSearchToken = normalizedSearchToken,
            localStatus = localStatus,
        )
        return write {
            if (dao.workspace(workspaceId) == null) return@write RecordResult.Rejected
            if (normalizedSearchToken != null && dao.otherActiveCustomer(workspaceId, normalizedSearchToken, id) != null) {
                note(
                    AuditVocab.SCREEN_CUSTOMERS,
                    AuditVocab.ACTION_CREATE,
                    AuditVocab.ENTITY_CUSTOMER,
                    id,
                    workspaceId,
                    nowUtcMs,
                    result = AuditVocab.RESULT_CONFLICT,
                    reasonCode = "duplicate",
                )
                return@write RecordResult.Conflict
            }
            dao.insertCustomer(row)
            val after = linkedMapOf<String, JsonValue>(
                "revision" to rev(1),
                "localStatus" to word(localStatus),
                "deleted" to bit(false),
            )
            if (encryptedPhone != null) after["phone"] = word("present")
            if (normalizedSearchToken != null) after["normalizedSearchToken"] = word("present")
            note(
                AuditVocab.SCREEN_CUSTOMERS,
                AuditVocab.ACTION_CREATE,
                AuditVocab.ENTITY_CUSTOMER,
                id,
                workspaceId,
                nowUtcMs,
                after = after,
                undo = localUndo(1, OP_HIDE),
            )
            RecordResult.Applied
        }
    }

    fun setCustomerStatus(id: String, status: String, nowUtcMs: Long): RecordResult {
        if (!RecordRules.validId(id) || status !in RecordRules.STATUSES || nowUtcMs < 0) return RecordResult.Rejected
        return write {
            val row = dao.customer(id) ?: return@write RecordResult.Missing
            if (row.deletedAtUtcMs != null) return@write RecordResult.NotDeleted
            if (nowUtcMs < row.createdAtUtcMs) return@write RecordResult.Rejected
            if (row.localStatus == status) return@write RecordResult.AlreadyActive
            if (dao.setCustomerStatus(id, status, nowUtcMs) != 1) error("customer status did not write one row")
            note(
                AuditVocab.SCREEN_CUSTOMERS,
                AuditVocab.ACTION_UPDATE,
                AuditVocab.ENTITY_CUSTOMER,
                id,
                row.workspaceId,
                nowUtcMs,
                before = mapOf("localStatus" to word(row.localStatus), "revision" to rev(row.revision)),
                after = mapOf("localStatus" to word(status), "revision" to rev(row.revision + 1)),
                undo = localUndo(row.revision + 1, OP_STATUS, mapOf("localStatus" to word(row.localStatus))),
            )
            RecordResult.Applied
        }
    }

    fun createBranch(
        id: String,
        workspaceId: String,
        customerId: String,
        encryptedLabel: ByteArray,
        nowUtcMs: Long,
        isDefault: Boolean = false,
    ): RecordResult {
        if (!RecordRules.validId(id) || !RecordRules.validId(workspaceId) || !RecordRules.validId(customerId)) {
            return RecordResult.Rejected
        }
        if (!RecordRules.ciphertext(encryptedLabel, RecordRules.NAME_CIPHER_MAX) || nowUtcMs < 0) {
            return RecordResult.Rejected
        }
        val row = BranchEntity(
            id = id,
            workspaceId = workspaceId,
            createdAtUtcMs = nowUtcMs,
            updatedAtUtcMs = nowUtcMs,
            revision = 1,
            deletedAtUtcMs = null,
            customerId = customerId,
            encryptedLabel = encryptedLabel,
            isDefault = isDefault,
        )
        return write {
            val customer = dao.customer(customerId) ?: return@write RecordResult.InactiveParent
            if (customer.deletedAtUtcMs != null) return@write RecordResult.InactiveParent
            if (customer.workspaceId != workspaceId) return@write RecordResult.WorkspaceMismatch
            if (isDefault && dao.otherDefaultBranch(customerId, id) != null) {
                note(
                    AuditVocab.SCREEN_CUSTOMERS,
                    AuditVocab.ACTION_CREATE,
                    AuditVocab.ENTITY_BRANCH,
                    id,
                    workspaceId,
                    nowUtcMs,
                    result = AuditVocab.RESULT_CONFLICT,
                    reasonCode = "duplicate",
                )
                return@write RecordResult.Conflict
            }
            dao.insertBranch(row)
            note(
                AuditVocab.SCREEN_CUSTOMERS,
                AuditVocab.ACTION_CREATE,
                AuditVocab.ENTITY_BRANCH,
                id,
                workspaceId,
                nowUtcMs,
                after = mapOf(
                    "revision" to rev(1),
                    "customerId" to word(customerId),
                    "isDefault" to bit(isDefault),
                    "deleted" to bit(false),
                ),
                undo = localUndo(1, OP_HIDE),
            )
            RecordResult.Applied
        }
    }

    fun createProduct(
        id: String,
        workspaceId: String,
        productCode: String,
        displayName: String,
        localTrialDays: Int,
        nowUtcMs: Long,
        remoteTrialDays: Int? = null,
        policyVersion: Int? = null,
        source: String = RecordRules.SOURCE_LOCAL,
    ): RecordResult {
        val code = RecordRules.productCode(productCode)
        val name = RecordRules.label(displayName)
        if (!RecordRules.validId(id) || !RecordRules.validId(workspaceId) || code == null || name == null) {
            return RecordResult.Rejected
        }
        if (!RecordRules.requiredTrialDays(localTrialDays) || !RecordRules.trialDays(remoteTrialDays)) {
            return RecordResult.Rejected
        }
        if (policyVersion != null && policyVersion < 1) return RecordResult.Rejected
        if (source !in RecordRules.SOURCES || nowUtcMs < 0) return RecordResult.Rejected
        val row = ProductEntity(
            id = id,
            workspaceId = workspaceId,
            createdAtUtcMs = nowUtcMs,
            updatedAtUtcMs = nowUtcMs,
            revision = 1,
            deletedAtUtcMs = null,
            productCode = code,
            displayName = name,
            localTrialDays = localTrialDays,
            remoteTrialDays = remoteTrialDays,
            policyVersion = policyVersion,
            source = source,
        )
        return write {
            if (dao.workspace(workspaceId) == null) return@write RecordResult.Rejected
            if (dao.otherActiveProduct(workspaceId, code, id) != null) {
                note(
                    AuditVocab.SCREEN_PRODUCTS,
                    AuditVocab.ACTION_CREATE,
                    AuditVocab.ENTITY_PRODUCT,
                    id,
                    workspaceId,
                    nowUtcMs,
                    result = AuditVocab.RESULT_CONFLICT,
                    reasonCode = "duplicate",
                )
                return@write RecordResult.Conflict
            }
            dao.insertProduct(row)
            val after = linkedMapOf<String, JsonValue>(
                "revision" to rev(1),
                "productCode" to word(code),
                "displayName" to word(name),
                "localTrialDays" to rev(localTrialDays.toLong()),
                "source" to word(source),
                "deleted" to bit(false),
            )
            if (remoteTrialDays != null) after["remoteTrialDays"] = rev(remoteTrialDays.toLong())
            if (policyVersion != null) after["policyVersion"] = rev(policyVersion.toLong())
            note(
                AuditVocab.SCREEN_PRODUCTS,
                AuditVocab.ACTION_CREATE,
                AuditVocab.ENTITY_PRODUCT,
                id,
                workspaceId,
                nowUtcMs,
                after = after,
                undo = localUndo(1, OP_HIDE),
            )
            RecordResult.Applied
        }
    }

    fun createLink(
        id: String,
        workspaceId: String,
        customerId: String,
        hubProfileId: String,
        remoteType: String,
        remoteId: String,
        linkedBy: String,
        nowUtcMs: Long,
    ): RecordResult {
        if (!linkShape(id, workspaceId, customerId, hubProfileId, remoteType, remoteId, linkedBy, nowUtcMs)) {
            return RecordResult.Rejected
        }
        val row = CustomerLinkEntity(
            id = id,
            workspaceId = workspaceId,
            createdAtUtcMs = nowUtcMs,
            updatedAtUtcMs = nowUtcMs,
            revision = 1,
            deletedAtUtcMs = null,
            customerId = customerId,
            hubProfileId = hubProfileId,
            remoteType = remoteType,
            remoteId = remoteId,
            linkedBy = linkedBy,
        )
        return write {
            val scope = linkParents(workspaceId, customerId, hubProfileId)
            if (scope != null) {
                if (dao.workspace(workspaceId) != null) {
                    note(
                        AuditVocab.SCREEN_HUB,
                        AuditVocab.ACTION_CREATE,
                        AuditVocab.ENTITY_LINK,
                        id,
                        workspaceId,
                        nowUtcMs,
                        result = AuditVocab.RESULT_FAILURE,
                        reasonCode = if (scope == RecordResult.WorkspaceMismatch) "workspace_mismatch" else "parent_inactive",
                        hubProfileId = if (dao.hubProfile(hubProfileId) != null) hubProfileId else null,
                    )
                }
                return@write scope
            }
            if (dao.otherActiveLink(hubProfileId, remoteType, remoteId, id) != null) {
                note(
                    AuditVocab.SCREEN_HUB,
                    AuditVocab.ACTION_CREATE,
                    AuditVocab.ENTITY_LINK,
                    id,
                    workspaceId,
                    nowUtcMs,
                    result = AuditVocab.RESULT_CONFLICT,
                    reasonCode = "duplicate",
                    hubProfileId = hubProfileId,
                )
                return@write RecordResult.Conflict
            }
            dao.insertLink(row)
            note(
                AuditVocab.SCREEN_HUB,
                AuditVocab.ACTION_CREATE,
                AuditVocab.ENTITY_LINK,
                id,
                workspaceId,
                nowUtcMs,
                after = mapOf(
                    "revision" to rev(1),
                    "customerId" to word(customerId),
                    "hubProfileId" to word(hubProfileId),
                    "remoteType" to word(remoteType),
                    "remoteId" to word(remoteId),
                    "deleted" to bit(false),
                ),
                hubProfileId = hubProfileId,
                undo = localUndo(1, OP_HIDE),
            )
            RecordResult.Applied
        }
    }

    fun softDeleteHub(id: String, nowUtcMs: Long): RecordResult = hide(
        id,
        nowUtcMs,
        AuditVocab.ENTITY_HUB,
        AuditVocab.SCREEN_HUB,
        dao::hubProfile,
        { it.deletedAtUtcMs },
        dao::hideHub,
        { it.workspaceId },
        { it.revision },
        { it.id },
    )
    fun restoreHub(id: String, nowUtcMs: Long): RecordResult = write {
        val row = dao.hubProfile(id) ?: return@write RecordResult.Missing
        if (row.deletedAtUtcMs == null) return@write RecordResult.AlreadyActive
        if (nowUtcMs < row.createdAtUtcMs) return@write RecordResult.Rejected
        if (dao.otherActiveHub(row.workspaceId, row.normalizedUrl, id) != null) {
            note(
                AuditVocab.SCREEN_HUB,
                AuditVocab.ACTION_RESTORE,
                AuditVocab.ENTITY_HUB,
                id,
                row.workspaceId,
                nowUtcMs,
                result = AuditVocab.RESULT_CONFLICT,
                reasonCode = "duplicate",
                hubProfileId = id,
                before = mapOf("deleted" to bit(true), "revision" to rev(row.revision)),
            )
            return@write RecordResult.Conflict
        }
        if (dao.restoreHub(id, nowUtcMs) != 1) error("hub restore did not write one row")
        note(
            AuditVocab.SCREEN_HUB,
            AuditVocab.ACTION_RESTORE,
            AuditVocab.ENTITY_HUB,
            id,
            row.workspaceId,
            nowUtcMs,
            before = mapOf("deleted" to bit(true), "revision" to rev(row.revision)),
            after = mapOf("deleted" to bit(false), "revision" to rev(row.revision + 1)),
            hubProfileId = id,
            undo = localUndo(row.revision + 1, OP_HIDE),
        )
        RecordResult.Applied
    }

    fun softDeleteCustomer(id: String, nowUtcMs: Long): RecordResult = hide(
        id,
        nowUtcMs,
        AuditVocab.ENTITY_CUSTOMER,
        AuditVocab.SCREEN_CUSTOMERS,
        dao::customer,
        { it.deletedAtUtcMs },
        dao::hideCustomer,
        { it.workspaceId },
        { it.revision },
    )

    fun restoreCustomer(id: String, nowUtcMs: Long): RecordResult = write {
        val row = dao.customer(id) ?: return@write RecordResult.Missing
        if (row.deletedAtUtcMs == null) return@write RecordResult.AlreadyActive
        if (nowUtcMs < row.createdAtUtcMs) return@write RecordResult.Rejected
        val token = row.normalizedSearchToken
        if (token != null && dao.otherActiveCustomer(row.workspaceId, token, id) != null) {
            note(
                AuditVocab.SCREEN_CUSTOMERS,
                AuditVocab.ACTION_RESTORE,
                AuditVocab.ENTITY_CUSTOMER,
                id,
                row.workspaceId,
                nowUtcMs,
                result = AuditVocab.RESULT_CONFLICT,
                reasonCode = "duplicate",
                before = mapOf("deleted" to bit(true), "revision" to rev(row.revision)),
            )
            return@write RecordResult.Conflict
        }
        if (dao.restoreCustomer(id, nowUtcMs) != 1) error("customer restore did not write one row")
        note(
            AuditVocab.SCREEN_CUSTOMERS,
            AuditVocab.ACTION_RESTORE,
            AuditVocab.ENTITY_CUSTOMER,
            id,
            row.workspaceId,
            nowUtcMs,
            before = mapOf("deleted" to bit(true), "revision" to rev(row.revision)),
            after = mapOf("deleted" to bit(false), "revision" to rev(row.revision + 1)),
            undo = localUndo(row.revision + 1, OP_HIDE),
        )
        RecordResult.Applied
    }

    fun softDeleteBranch(id: String, nowUtcMs: Long): RecordResult = hide(
        id,
        nowUtcMs,
        AuditVocab.ENTITY_BRANCH,
        AuditVocab.SCREEN_CUSTOMERS,
        dao::branch,
        { it.deletedAtUtcMs },
        dao::hideBranch,
        { it.workspaceId },
        { it.revision },
    )

    fun restoreBranch(id: String, nowUtcMs: Long): RecordResult = write {
        val row = dao.branch(id) ?: return@write RecordResult.Missing
        if (row.deletedAtUtcMs == null) return@write RecordResult.AlreadyActive
        if (nowUtcMs < row.createdAtUtcMs) return@write RecordResult.Rejected
        val parent = dao.customer(row.customerId) ?: return@write RecordResult.InactiveParent
        if (parent.deletedAtUtcMs != null) return@write RecordResult.InactiveParent
        if (row.isDefault && dao.otherDefaultBranch(row.customerId, id) != null) {
            note(
                AuditVocab.SCREEN_CUSTOMERS,
                AuditVocab.ACTION_RESTORE,
                AuditVocab.ENTITY_BRANCH,
                id,
                row.workspaceId,
                nowUtcMs,
                result = AuditVocab.RESULT_CONFLICT,
                reasonCode = "duplicate",
                before = mapOf("deleted" to bit(true), "revision" to rev(row.revision)),
            )
            return@write RecordResult.Conflict
        }
        if (dao.restoreBranch(id, nowUtcMs) != 1) error("branch restore did not write one row")
        note(
            AuditVocab.SCREEN_CUSTOMERS,
            AuditVocab.ACTION_RESTORE,
            AuditVocab.ENTITY_BRANCH,
            id,
            row.workspaceId,
            nowUtcMs,
            before = mapOf("deleted" to bit(true), "revision" to rev(row.revision)),
            after = mapOf("deleted" to bit(false), "revision" to rev(row.revision + 1)),
            undo = localUndo(row.revision + 1, OP_HIDE),
        )
        RecordResult.Applied
    }

    fun softDeleteProduct(id: String, nowUtcMs: Long): RecordResult = hide(
        id,
        nowUtcMs,
        AuditVocab.ENTITY_PRODUCT,
        AuditVocab.SCREEN_PRODUCTS,
        dao::product,
        { it.deletedAtUtcMs },
        dao::hideProduct,
        { it.workspaceId },
        { it.revision },
    )

    fun restoreProduct(id: String, nowUtcMs: Long): RecordResult = write {
        val row = dao.product(id) ?: return@write RecordResult.Missing
        if (row.deletedAtUtcMs == null) return@write RecordResult.AlreadyActive
        if (nowUtcMs < row.createdAtUtcMs) return@write RecordResult.Rejected
        if (dao.otherActiveProduct(row.workspaceId, row.productCode, id) != null) {
            note(
                AuditVocab.SCREEN_PRODUCTS,
                AuditVocab.ACTION_RESTORE,
                AuditVocab.ENTITY_PRODUCT,
                id,
                row.workspaceId,
                nowUtcMs,
                result = AuditVocab.RESULT_CONFLICT,
                reasonCode = "duplicate",
                before = mapOf("deleted" to bit(true), "revision" to rev(row.revision)),
            )
            return@write RecordResult.Conflict
        }
        if (dao.restoreProduct(id, nowUtcMs) != 1) error("product restore did not write one row")
        note(
            AuditVocab.SCREEN_PRODUCTS,
            AuditVocab.ACTION_RESTORE,
            AuditVocab.ENTITY_PRODUCT,
            id,
            row.workspaceId,
            nowUtcMs,
            before = mapOf("deleted" to bit(true), "revision" to rev(row.revision)),
            after = mapOf("deleted" to bit(false), "revision" to rev(row.revision + 1)),
            undo = localUndo(row.revision + 1, OP_HIDE),
        )
        RecordResult.Applied
    }

    fun softDeleteLink(id: String, nowUtcMs: Long): RecordResult = hide(
        id,
        nowUtcMs,
        AuditVocab.ENTITY_LINK,
        AuditVocab.SCREEN_HUB,
        dao::link,
        { it.deletedAtUtcMs },
        dao::hideLink,
        { it.workspaceId },
        { it.revision },
        { it.hubProfileId },
    )

    fun restoreLink(id: String, nowUtcMs: Long): RecordResult = write {
        val row = dao.link(id) ?: return@write RecordResult.Missing
        if (row.deletedAtUtcMs == null) return@write RecordResult.AlreadyActive
        if (nowUtcMs < row.createdAtUtcMs) return@write RecordResult.Rejected
        val scope = linkParents(row.workspaceId, row.customerId, row.hubProfileId)
        if (scope != null) return@write scope
        if (dao.otherActiveLink(row.hubProfileId, row.remoteType, row.remoteId, id) != null) {
            note(
                AuditVocab.SCREEN_HUB,
                AuditVocab.ACTION_RESTORE,
                AuditVocab.ENTITY_LINK,
                id,
                row.workspaceId,
                nowUtcMs,
                result = AuditVocab.RESULT_CONFLICT,
                reasonCode = "duplicate",
                hubProfileId = row.hubProfileId,
                before = mapOf("deleted" to bit(true), "revision" to rev(row.revision)),
            )
            return@write RecordResult.Conflict
        }
        if (dao.restoreLink(id, nowUtcMs) != 1) error("link restore did not write one row")
        note(
            AuditVocab.SCREEN_HUB,
            AuditVocab.ACTION_RESTORE,
            AuditVocab.ENTITY_LINK,
            id,
            row.workspaceId,
            nowUtcMs,
            before = mapOf("deleted" to bit(true), "revision" to rev(row.revision)),
            after = mapOf("deleted" to bit(false), "revision" to rev(row.revision + 1)),
            hubProfileId = row.hubProfileId,
            undo = localUndo(row.revision + 1, OP_HIDE),
        )
        RecordResult.Applied
    }

    private fun linkParents(workspaceId: String, customerId: String, hubProfileId: String): RecordResult? {
        val customer = dao.customer(customerId) ?: return RecordResult.InactiveParent
        val hub = dao.hubProfile(hubProfileId) ?: return RecordResult.InactiveParent
        if (customer.workspaceId != workspaceId || hub.workspaceId != workspaceId) {
            return RecordResult.WorkspaceMismatch
        }
        if (customer.deletedAtUtcMs != null || hub.deletedAtUtcMs != null) return RecordResult.InactiveParent
        return null
    }

    private fun <T> hide(
        id: String,
        nowUtcMs: Long,
        entityType: String,
        screen: String,
        load: (String) -> T?,
        deletedAt: (T) -> Long?,
        mark: (String, Long) -> Int,
        workspaceOf: (T) -> String,
        revisionOf: (T) -> Long,
        hubOf: (T) -> String? = { null },
    ): RecordResult {
        if (!RecordRules.validId(id) || nowUtcMs < 0) return RecordResult.Rejected
        return write {
            val row = load(id) ?: return@write RecordResult.Missing
            if (deletedAt(row) != null) return@write RecordResult.NotDeleted
            val revision = revisionOf(row)
            if (mark(id, nowUtcMs) != 1) error("soft-delete did not write one row")
            note(
                screen,
                AuditVocab.ACTION_SOFT_DELETE,
                entityType,
                id,
                workspaceOf(row),
                nowUtcMs,
                before = mapOf("deleted" to bit(false), "revision" to rev(revision)),
                after = mapOf("deleted" to bit(true), "revision" to rev(revision + 1)),
                hubProfileId = hubOf(row),
                undo = localUndo(revision + 1, OP_RESTORE),
            )
            RecordResult.Applied
        }
    }

    fun undo(operationId: String, nowUtcMs: Long, channel: UndoChannel): UndoOutcome {
        if (!RecordRules.validId(operationId) || nowUtcMs < 0) {
            return UndoOutcome.Blocked("تعذر التراجع.", "Undo cannot proceed.", "id")
        }
        var outcome: UndoOutcome = UndoOutcome.Blocked("تعذر التراجع.", "Undo cannot proceed.", "id")
        write {
            val record = database.auditDao().undo(operationId)
            if (record == null) {
                outcome = UndoOutcome.Blocked("تعذر التراجع.", "Undo cannot proceed.", "missing")
                return@write RecordResult.Missing
            }
            val decision = Undo.decide(
                UndoRequest(
                    undoClass = record.undoClass,
                    expectedRevision = record.expectedRevision,
                    currentRevision = currentRevision(record.entityType, record.entityId),
                    expiresAtUtcMs = record.expiresAtUtcMs,
                    nowUtcMs = nowUtcMs,
                    channel = channel,
                    dependentChange = dependentChange(record),
                    used = record.usedAtUtcMs != null,
                    invalidated = record.invalidatedAtUtcMs != null,
                ),
            )
            when (decision) {
                UndoDecision.Apply -> {
                    val applied = applyInverse(record, nowUtcMs)
                    if (applied != RecordResult.Applied) {
                        outcome = blocked(applied)
                        return@write applied
                    }
                    if (database.auditDao().markUndoUsed(operationId, nowUtcMs) != 1) {
                        error("undo mark did not write one row")
                    }
                    note(
                        screenFor(record.entityType),
                        AuditVocab.ACTION_UNDO,
                        record.entityType,
                        record.entityId,
                        record.workspaceId,
                        nowUtcMs,
                        before = mapOf("revision" to rev(record.expectedRevision)),
                        after = mapOf("revision" to rev(record.expectedRevision + 1)),
                        hubProfileId = hubFor(record),
                        originalEventId = record.eventId,
                        correlationId = record.eventId,
                    )
                    outcome = UndoOutcome.Done(record.eventId)
                    RecordResult.Applied
                }
                is UndoDecision.Refused -> {
                    if (decision.reason == "revision" || decision.reason == "sent") {
                        database.auditDao().markUndoInvalid(operationId, nowUtcMs)
                    }
                    outcome = UndoOutcome.Blocked(decision.arabic, decision.english, decision.reason)
                    RecordResult.Rejected
                }
                UndoDecision.AlreadyUsed -> {
                    outcome = UndoOutcome.Blocked("تم التراجع من قبل.", "This undo was already used.", "used")
                    RecordResult.AlreadyActive
                }
                UndoDecision.Invalidated -> {
                    outcome = UndoOutcome.Blocked(
                        "تعذر التراجع لأن السجل تغيّر.",
                        "Undo cannot proceed because the record changed.",
                        "invalidated",
                    )
                    RecordResult.Rejected
                }
                UndoDecision.MissingRecord -> {
                    database.auditDao().markUndoInvalid(operationId, nowUtcMs)
                    outcome = UndoOutcome.Blocked("السجل غير موجود.", "The record is missing.", "missing")
                    RecordResult.Missing
                }
            }
        }
        return outcome
    }

    private fun customerShape(
        id: String,
        workspaceId: String,
        encryptedDisplayName: ByteArray,
        encryptedPhone: ByteArray?,
        normalizedSearchToken: String?,
        localStatus: String,
        nowUtcMs: Long,
    ): Boolean {
        if (!RecordRules.validId(id) || !RecordRules.validId(workspaceId) || nowUtcMs < 0) return false
        if (!RecordRules.ciphertext(encryptedDisplayName, RecordRules.NAME_CIPHER_MAX)) return false
        if (encryptedPhone != null && !RecordRules.ciphertext(encryptedPhone, RecordRules.PHONE_CIPHER_MAX)) return false
        if (!RecordRules.searchToken(normalizedSearchToken) || localStatus !in RecordRules.STATUSES) return false
        return true
    }

    private fun linkShape(
        id: String,
        workspaceId: String,
        customerId: String,
        hubProfileId: String,
        remoteType: String,
        remoteId: String,
        linkedBy: String,
        nowUtcMs: Long,
    ): Boolean {
        if (!RecordRules.validId(id) || !RecordRules.validId(workspaceId)) return false
        if (!RecordRules.validId(customerId) || !RecordRules.validId(hubProfileId) || !RecordRules.validId(linkedBy)) {
            return false
        }
        if (remoteType !in RecordRules.REMOTE_TYPES || !RecordRules.remoteId(remoteId) || nowUtcMs < 0) return false
        return true
    }

    private fun normalizedUrl(raw: String): String? = when (val parsed = normalizeHubUrl(raw)) {
        is UrlResult.Ok -> parsed.url
        is UrlResult.Reject -> null
    }

    private fun currentRevision(entityType: String, id: String): Long? = when (entityType) {
        AuditVocab.ENTITY_WORKSPACE -> dao.workspace(id)?.revision
        AuditVocab.ENTITY_HUB -> dao.hubProfile(id)?.revision
        AuditVocab.ENTITY_CUSTOMER -> dao.customer(id)?.revision
        AuditVocab.ENTITY_BRANCH -> dao.branch(id)?.revision
        AuditVocab.ENTITY_PRODUCT -> dao.product(id)?.revision
        AuditVocab.ENTITY_LINK -> dao.link(id)?.revision
        else -> null
    }

    private fun dependentChange(record: UndoRecordEntity): Boolean {
        val audit = database.auditDao()
        if (audit.laterAudits(record.entityId, record.eventId) > 0) return true
        return when (record.entityType) {
            AuditVocab.ENTITY_CUSTOMER ->
                audit.branchesTouchedAfter(record.entityId, record.createdAtUtcMs) > 0 ||
                    audit.linksTouchedAfter(record.entityId, record.createdAtUtcMs) > 0
            AuditVocab.ENTITY_HUB -> audit.hubLinksTouchedAfter(record.entityId, record.createdAtUtcMs) > 0
            else -> false
        }
    }

    private fun applyInverse(record: UndoRecordEntity, nowUtcMs: Long): RecordResult {
        val opened = runCatching { database.auditRuntime.sealer.open(record.inverseCiphertext) }.getOrNull()
            ?: return RecordResult.Rejected
        val fields = runCatching { AuditCanonical.parseObject(opened) }.getOrNull() ?: return RecordResult.Rejected
        val op = (fields["op"] as? JsonValue.Text)?.value ?: return RecordResult.Rejected
        return when (op) {
            OP_HIDE -> hideForUndo(record.entityType, record.entityId, nowUtcMs, record.expectedRevision)
            OP_RESTORE -> restoreForUndo(record.entityType, record.entityId, nowUtcMs, record.expectedRevision)
            OP_STATUS -> {
                val status = (fields["localStatus"] as? JsonValue.Text)?.value ?: return RecordResult.Rejected
                statusForUndo(record.entityId, status, nowUtcMs, record.expectedRevision)
            }
            else -> RecordResult.Rejected
        }
    }

    private fun hideForUndo(entityType: String, id: String, nowUtcMs: Long, expected: Long): RecordResult {
        fun gate(revision: Long, createdAt: Long, deletedAt: Long?, mark: () -> Int): RecordResult {
            if (deletedAt != null || revision != expected || nowUtcMs < createdAt) return RecordResult.Rejected
            if (mark() != 1) error("undo hide did not write one row")
            return RecordResult.Applied
        }
        return when (entityType) {
            AuditVocab.ENTITY_HUB -> {
                val row = dao.hubProfile(id) ?: return RecordResult.Missing
                gate(row.revision, row.createdAtUtcMs, row.deletedAtUtcMs) { dao.hideHub(id, nowUtcMs) }
            }
            AuditVocab.ENTITY_CUSTOMER -> {
                val row = dao.customer(id) ?: return RecordResult.Missing
                gate(row.revision, row.createdAtUtcMs, row.deletedAtUtcMs) { dao.hideCustomer(id, nowUtcMs) }
            }
            AuditVocab.ENTITY_BRANCH -> {
                val row = dao.branch(id) ?: return RecordResult.Missing
                gate(row.revision, row.createdAtUtcMs, row.deletedAtUtcMs) { dao.hideBranch(id, nowUtcMs) }
            }
            AuditVocab.ENTITY_PRODUCT -> {
                val row = dao.product(id) ?: return RecordResult.Missing
                gate(row.revision, row.createdAtUtcMs, row.deletedAtUtcMs) { dao.hideProduct(id, nowUtcMs) }
            }
            AuditVocab.ENTITY_LINK -> {
                val row = dao.link(id) ?: return RecordResult.Missing
                gate(row.revision, row.createdAtUtcMs, row.deletedAtUtcMs) { dao.hideLink(id, nowUtcMs) }
            }
            else -> RecordResult.Rejected
        }
    }

    private fun restoreForUndo(entityType: String, id: String, nowUtcMs: Long, expected: Long): RecordResult {
        return when (entityType) {
            AuditVocab.ENTITY_HUB -> {
                val row = dao.hubProfile(id) ?: return RecordResult.Missing
                if (row.deletedAtUtcMs == null || row.revision != expected || nowUtcMs < row.createdAtUtcMs) {
                    return RecordResult.Rejected
                }
                if (dao.otherActiveHub(row.workspaceId, row.normalizedUrl, id) != null) return RecordResult.Conflict
                if (dao.restoreHub(id, nowUtcMs) != 1) error("undo restore did not write one row")
                RecordResult.Applied
            }
            AuditVocab.ENTITY_CUSTOMER -> {
                val row = dao.customer(id) ?: return RecordResult.Missing
                if (row.deletedAtUtcMs == null || row.revision != expected || nowUtcMs < row.createdAtUtcMs) {
                    return RecordResult.Rejected
                }
                val token = row.normalizedSearchToken
                if (token != null && dao.otherActiveCustomer(row.workspaceId, token, id) != null) {
                    return RecordResult.Conflict
                }
                if (dao.restoreCustomer(id, nowUtcMs) != 1) error("undo restore did not write one row")
                RecordResult.Applied
            }
            AuditVocab.ENTITY_BRANCH -> {
                val row = dao.branch(id) ?: return RecordResult.Missing
                if (row.deletedAtUtcMs == null || row.revision != expected || nowUtcMs < row.createdAtUtcMs) {
                    return RecordResult.Rejected
                }
                val parent = dao.customer(row.customerId) ?: return RecordResult.InactiveParent
                if (parent.deletedAtUtcMs != null) return RecordResult.InactiveParent
                if (row.isDefault && dao.otherDefaultBranch(row.customerId, id) != null) return RecordResult.Conflict
                if (dao.restoreBranch(id, nowUtcMs) != 1) error("undo restore did not write one row")
                RecordResult.Applied
            }
            AuditVocab.ENTITY_PRODUCT -> {
                val row = dao.product(id) ?: return RecordResult.Missing
                if (row.deletedAtUtcMs == null || row.revision != expected || nowUtcMs < row.createdAtUtcMs) {
                    return RecordResult.Rejected
                }
                if (dao.otherActiveProduct(row.workspaceId, row.productCode, id) != null) return RecordResult.Conflict
                if (dao.restoreProduct(id, nowUtcMs) != 1) error("undo restore did not write one row")
                RecordResult.Applied
            }
            AuditVocab.ENTITY_LINK -> {
                val row = dao.link(id) ?: return RecordResult.Missing
                if (row.deletedAtUtcMs == null || row.revision != expected || nowUtcMs < row.createdAtUtcMs) {
                    return RecordResult.Rejected
                }
                val scope = linkParents(row.workspaceId, row.customerId, row.hubProfileId)
                if (scope != null) return scope
                if (dao.otherActiveLink(row.hubProfileId, row.remoteType, row.remoteId, id) != null) {
                    return RecordResult.Conflict
                }
                if (dao.restoreLink(id, nowUtcMs) != 1) error("undo restore did not write one row")
                RecordResult.Applied
            }
            else -> RecordResult.Rejected
        }
    }

    private fun statusForUndo(id: String, status: String, nowUtcMs: Long, expected: Long): RecordResult {
        if (status !in RecordRules.STATUSES) return RecordResult.Rejected
        val row = dao.customer(id) ?: return RecordResult.Missing
        if (row.deletedAtUtcMs != null) return RecordResult.NotDeleted
        if (row.revision != expected || nowUtcMs < row.createdAtUtcMs) return RecordResult.Rejected
        if (row.localStatus == status) return RecordResult.AlreadyActive
        if (dao.setCustomerStatus(id, status, nowUtcMs) != 1) error("undo status did not write one row")
        return RecordResult.Applied
    }

    private fun hubFor(record: UndoRecordEntity): String? = when (record.entityType) {
        AuditVocab.ENTITY_HUB -> record.entityId
        AuditVocab.ENTITY_LINK -> dao.link(record.entityId)?.hubProfileId
        else -> null
    }

    private fun screenFor(entityType: String): String = when (entityType) {
        AuditVocab.ENTITY_CUSTOMER, AuditVocab.ENTITY_BRANCH -> AuditVocab.SCREEN_CUSTOMERS
        AuditVocab.ENTITY_PRODUCT -> AuditVocab.SCREEN_PRODUCTS
        AuditVocab.ENTITY_HUB, AuditVocab.ENTITY_LINK -> AuditVocab.SCREEN_HUB
        AuditVocab.ENTITY_WORKSPACE -> AuditVocab.SCREEN_NOTEBOOK
        AuditVocab.ENTITY_PAYMENT -> AuditVocab.SCREEN_MONEY
        else -> AuditVocab.SCREEN_ACTIVITY
    }

    private fun blocked(result: RecordResult): UndoOutcome = when (result) {
        RecordResult.Conflict -> UndoOutcome.Blocked(
            "تعذر التراجع لأن السجل موجود.",
            "Undo cannot proceed because the record already exists.",
            "conflict",
        )
        RecordResult.InactiveParent -> UndoOutcome.Blocked(
            "تعذر التراجع لأن السجل الأب مخفي.",
            "Undo cannot proceed because the parent record is hidden.",
            "parent",
        )
        RecordResult.Missing -> UndoOutcome.Blocked("السجل غير موجود.", "The record is missing.", "missing")
        else -> UndoOutcome.Blocked("تعذر التراجع.", "Undo cannot proceed.", "rejected")
    }

    private class AuditSession {
        var pending: AuditSpec? = null
        fun keep(spec: AuditSpec) {
            pending = spec
        }
    }

    private fun AuditSession.note(
        screen: String,
        action: String,
        entityType: String,
        entityId: String,
        workspaceId: String,
        nowUtcMs: Long,
        before: Map<String, JsonValue> = emptyMap(),
        after: Map<String, JsonValue> = emptyMap(),
        result: String = AuditVocab.RESULT_SUCCESS,
        hubProfileId: String? = null,
        undo: PendingUndo? = null,
        reasonCode: String? = null,
        originalEventId: String? = null,
        correlationId: String? = null,
    ) {
        keep(
            AuditSpec(
                screen = screen,
                action = action,
                entityType = entityType,
                entityId = entityId,
                workspaceId = workspaceId,
                hubProfileId = hubProfileId,
                nowUtcMs = nowUtcMs,
                before = before,
                after = after,
                result = result,
                reasonCode = reasonCode,
                undo = undo,
                originalEventId = originalEventId,
                correlationId = correlationId,
            ),
        )
    }

    private fun localUndo(revision: Long, op: String, extra: Map<String, JsonValue> = emptyMap()): PendingUndo {
        val inverse = linkedMapOf<String, JsonValue>("op" to JsonValue.Text(op))
        extra.forEach { (key, value) -> inverse[key] = value }
        return PendingUndo(Undo.CLASS_LOCAL, revision, inverse)
    }

    private fun rev(value: Long): JsonValue = JsonValue.Int64(value)

    private fun bit(value: Boolean): JsonValue = JsonValue.Bool(value)

    private fun word(value: String): JsonValue = JsonValue.Text(value)

    private fun write(body: AuditSession.() -> RecordResult): RecordResult {
        var attempt = 0
        while (true) {
            attempt += 1
            try {
                return database.runInTransaction(
                    Callable {
                        val session = AuditSession()
                        val result = session.body()
                        val spec = session.pending
                        if (spec != null) AuditWriter(database).append(spec)
                        result
                    },
                )
            } catch (error: Throwable) {
                val constraint = unwrapConstraint(error)
                if (constraint != null) {
                    val message = constraint.message.orEmpty()
                    if (
                        "audit_immutable" in message ||
                        "audit_link" in message ||
                        "bad_chain" in message ||
                        "undo_frozen" in message
                    ) {
                        throw constraint
                    }
                    if (AuditWriter.isChainRace(constraint) && attempt < 6) continue
                    return RecordRules.constraint(constraint)
                }
                if (isBusy(error) && attempt < 6) continue
                throw error
            }
        }
    }

    private fun unwrapConstraint(error: Throwable): SQLiteConstraintException? {
        var current: Throwable? = error
        while (current != null) {
            if (current is SQLiteConstraintException) return current
            current = current.cause
        }
        return null
    }

    private fun isBusy(error: Throwable): Boolean {
        var current: Throwable? = error
        while (current != null) {
            val message = current.message.orEmpty()
            if (current is android.database.sqlite.SQLiteDatabaseLockedException) return true
            if ("database is locked" in message || "SQLITE_BUSY" in message) return true
            current = current.cause
        }
        return false
    }
}
