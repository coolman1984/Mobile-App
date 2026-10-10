package com.pixelplus.owner.data.db

import android.database.sqlite.SQLiteConstraintException
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.pixelplus.owner.UrlResult
import com.pixelplus.owner.normalizeHubUrl

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
            if (dao.otherActiveHub(workspaceId, url, id) != null) return@write RecordResult.Conflict
            dao.insertHubProfile(row)
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
            if (dao.otherActiveHub(row.workspaceId, url, id) != null) return@write RecordResult.Conflict
            if (dao.changeHubOrigin(id, url, originEpoch, nowUtcMs) != 1) error("hub origin did not write one row")
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
                return@write RecordResult.Conflict
            }
            dao.insertCustomer(row)
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
            if (isDefault && dao.otherDefaultBranch(customerId, id) != null) return@write RecordResult.Conflict
            dao.insertBranch(row)
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
            if (dao.otherActiveProduct(workspaceId, code, id) != null) return@write RecordResult.Conflict
            dao.insertProduct(row)
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
            if (scope != null) return@write scope
            if (dao.otherActiveLink(hubProfileId, remoteType, remoteId, id) != null) return@write RecordResult.Conflict
            dao.insertLink(row)
            RecordResult.Applied
        }
    }

    fun softDeleteHub(id: String, nowUtcMs: Long): RecordResult = hide(id, nowUtcMs, dao::hubProfile, { it.deletedAtUtcMs }, dao::hideHub)
    fun restoreHub(id: String, nowUtcMs: Long): RecordResult = write {
        val row = dao.hubProfile(id) ?: return@write RecordResult.Missing
        if (row.deletedAtUtcMs == null) return@write RecordResult.AlreadyActive
        if (nowUtcMs < row.createdAtUtcMs) return@write RecordResult.Rejected
        if (dao.otherActiveHub(row.workspaceId, row.normalizedUrl, id) != null) return@write RecordResult.Conflict
        if (dao.restoreHub(id, nowUtcMs) != 1) error("hub restore did not write one row")
        RecordResult.Applied
    }

    fun softDeleteCustomer(id: String, nowUtcMs: Long): RecordResult =
        hide(id, nowUtcMs, dao::customer, { it.deletedAtUtcMs }, dao::hideCustomer)

    fun restoreCustomer(id: String, nowUtcMs: Long): RecordResult = write {
        val row = dao.customer(id) ?: return@write RecordResult.Missing
        if (row.deletedAtUtcMs == null) return@write RecordResult.AlreadyActive
        if (nowUtcMs < row.createdAtUtcMs) return@write RecordResult.Rejected
        val token = row.normalizedSearchToken
        if (token != null && dao.otherActiveCustomer(row.workspaceId, token, id) != null) {
            return@write RecordResult.Conflict
        }
        if (dao.restoreCustomer(id, nowUtcMs) != 1) error("customer restore did not write one row")
        RecordResult.Applied
    }

    fun softDeleteBranch(id: String, nowUtcMs: Long): RecordResult =
        hide(id, nowUtcMs, dao::branch, { it.deletedAtUtcMs }, dao::hideBranch)

    fun restoreBranch(id: String, nowUtcMs: Long): RecordResult = write {
        val row = dao.branch(id) ?: return@write RecordResult.Missing
        if (row.deletedAtUtcMs == null) return@write RecordResult.AlreadyActive
        if (nowUtcMs < row.createdAtUtcMs) return@write RecordResult.Rejected
        val parent = dao.customer(row.customerId) ?: return@write RecordResult.InactiveParent
        if (parent.deletedAtUtcMs != null) return@write RecordResult.InactiveParent
        if (row.isDefault && dao.otherDefaultBranch(row.customerId, id) != null) return@write RecordResult.Conflict
        if (dao.restoreBranch(id, nowUtcMs) != 1) error("branch restore did not write one row")
        RecordResult.Applied
    }

    fun softDeleteProduct(id: String, nowUtcMs: Long): RecordResult =
        hide(id, nowUtcMs, dao::product, { it.deletedAtUtcMs }, dao::hideProduct)

    fun restoreProduct(id: String, nowUtcMs: Long): RecordResult = write {
        val row = dao.product(id) ?: return@write RecordResult.Missing
        if (row.deletedAtUtcMs == null) return@write RecordResult.AlreadyActive
        if (nowUtcMs < row.createdAtUtcMs) return@write RecordResult.Rejected
        if (dao.otherActiveProduct(row.workspaceId, row.productCode, id) != null) return@write RecordResult.Conflict
        if (dao.restoreProduct(id, nowUtcMs) != 1) error("product restore did not write one row")
        RecordResult.Applied
    }

    fun softDeleteLink(id: String, nowUtcMs: Long): RecordResult =
        hide(id, nowUtcMs, dao::link, { it.deletedAtUtcMs }, dao::hideLink)

    fun restoreLink(id: String, nowUtcMs: Long): RecordResult = write {
        val row = dao.link(id) ?: return@write RecordResult.Missing
        if (row.deletedAtUtcMs == null) return@write RecordResult.AlreadyActive
        if (nowUtcMs < row.createdAtUtcMs) return@write RecordResult.Rejected
        val scope = linkParents(row.workspaceId, row.customerId, row.hubProfileId)
        if (scope != null) return@write scope
        if (dao.otherActiveLink(row.hubProfileId, row.remoteType, row.remoteId, id) != null) {
            return@write RecordResult.Conflict
        }
        if (dao.restoreLink(id, nowUtcMs) != 1) error("link restore did not write one row")
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
        load: (String) -> T?,
        deletedAt: (T) -> Long?,
        mark: (String, Long) -> Int,
    ): RecordResult {
        if (!RecordRules.validId(id) || nowUtcMs < 0) return RecordResult.Rejected
        return write {
            val row = load(id) ?: return@write RecordResult.Missing
            if (deletedAt(row) != null) return@write RecordResult.NotDeleted
            if (mark(id, nowUtcMs) != 1) error("soft-delete did not write one row")
            RecordResult.Applied
        }
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

    private fun write(body: () -> RecordResult): RecordResult = try {
        database.runInTransaction(body)
    } catch (error: SQLiteConstraintException) {
        RecordRules.constraint(error)
    }
}
