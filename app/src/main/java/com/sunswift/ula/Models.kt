package com.sunswift.ula

import org.json.JSONObject

enum class AppPage(val label: String) {
    Dashboard("Overview"),
    Records("Records"),
    RecordForm("Form"),
    Reports("Reports"),
    Activity("Activity"),
    Access("User control")
}

data class UlaOtpQuota(
    val remaining: Int,
    val limit: Int,
    val projectRemaining: Int,
    val projectLimit: Int,
    val cooldownSeconds: Int
)

data class UlaLoginAccess(
    val configured: Boolean,
    val authorized: Boolean,
    val quota: UlaOtpQuota
)

data class UlaUser(
    val mobile: String,
    val name: String,
    val role: String,
    val canAdmin: Boolean,
    val canEdit: Boolean,
    val permissionsJson: String = "{}"
) {
    companion object {
        fun fromJson(json: JSONObject): UlaUser {
            val permissions = json.optJSONObject("permissions") ?: JSONObject()
            return UlaUser(
                mobile = json.optString("mobile"),
                name = json.optString("name", json.optString("mobile", "User")),
                role = json.optString("role", "VIEWER"),
                canAdmin = permissions.optBoolean("admin", false),
                canEdit = permissions.optBoolean("edit", false),
                permissionsJson = permissions.toString()
            )
        }
    }
}

data class UlaSession(
    val token: String,
    val expiresAt: Long,
    val user: UlaUser
)

data class UlaRecord(
    val values: Map<String, String>,
    val version: Int
) {
    val id: String get() = values["id"].orEmpty()
    val consumerNo: String get() = values["consumerNo"].orEmpty()
    val consumerName: String get() = values["consumerName"].orEmpty()
    val mobileNo: String get() = values["mobileNo"].orEmpty()
    val villageName: String get() = values["villageName"].orEmpty()
    val panchayatName: String get() = values["panchayatName"].orEmpty()
    val sectionName: String get() = values["sectionName"].orEmpty()
    val divisionName: String get() = values["divisionName"].orEmpty()
    val installationDate: String get() = values["installationDate"].orEmpty()
    val updatedAt: String get() = values["updatedAt"].orEmpty()
    val updatedBy: String get() = values["updatedBy"].orEmpty()

    fun toJson(): JSONObject = JSONObject().apply {
        values.forEach { (key, value) -> put(key, value) }
        put("version", version)
    }

    companion object {
        fun fromJson(json: JSONObject): UlaRecord {
            val values = ULA_RECORD_KEYS.associateWith { key ->
                if (json.isNull(key)) "" else json.optString(key, "")
            }
            return UlaRecord(values = values, version = json.optInt("version", 0))
        }
    }
}

data class UlaActivity(
    val timestamp: String,
    val action: String,
    val recordId: String,
    val consumerNo: String,
    val consumerName: String,
    val user: String,
    val comments: String
) {
    companion object {
        fun fromJson(json: JSONObject) = UlaActivity(
            timestamp = json.optString("timestamp"),
            action = json.optString("action"),
            recordId = json.optString("recordId"),
            consumerNo = json.optString("consumerNo"),
            consumerName = json.optString("consumerName"),
            user = json.optString("user"),
            comments = json.optString("comments")
        )
    }
}

data class UlaAuthorizedUser(
    val mobile: String,
    val name: String,
    val role: String,
    val active: Boolean,
    val permanent: Boolean,
    val lastLoginAt: String
) {
    companion object {
        fun fromJson(json: JSONObject) = UlaAuthorizedUser(
            mobile = json.optString("mobile"),
            name = json.optString("name"),
            role = json.optString("role", "VIEWER"),
            active = json.optBoolean("active", false),
            permanent = json.optBoolean("permanent", false),
            lastLoginAt = json.optString("lastLoginAt")
        )
    }
}

data class UlaRecordsPayload(
    val records: List<UlaRecord>,
    val installers: List<String>
)

data class UlaAdminData(
    val users: List<UlaAuthorizedUser>,
    val installers: List<String>
)

data class UlaAppState(
    val checkingSession: Boolean = true,
    val loading: Boolean = false,
    val saving: Boolean = false,
    val user: UlaUser? = null,
    val sessionToken: String = "",
    val sessionExpiresAt: Long = 0L,
    val records: List<UlaRecord> = emptyList(),
    val activity: List<UlaActivity> = emptyList(),
    val authorizedUsers: List<UlaAuthorizedUser> = emptyList(),
    val installers: List<String> = emptyList(),
    val selectedRecord: UlaRecord? = null,
    val page: AppPage = AppPage.Dashboard,
    val error: String? = null,
    val lastSync: String = ""
)

val ULA_EDIT_FIELDS = linkedMapOf(
    "registrationDate" to "Registration date",
    "registerBy" to "Register By",
    "mobileNo" to "Mobile No",
    "consumerNo" to "Electricity Consumer No",
    "referenceNo" to "Reference No",
    "consumerName" to "Consumer Name",
    "villageName" to "Village Name",
    "panchayatName" to "Panchayat Name",
    "sectionName" to "Section Name",
    "divisionName" to "Division Name",
    "surveyDate" to "Survey date",
    "paymentDate" to "Payment date",
    "paidAmount" to "Paid to TPSODL",
    "paymentReceivedBy" to "Paid by",
    "paymentReceiveDate" to "Payment Receive Date",
    "paymentReceivedFromConsumer" to "Payment received from Consumer",
    "consumerPaymentReceivedBy" to "Payment received by",
    "installationDate" to "Installation Date",
    "installer1" to "Installer1",
    "installer2" to "Installer2",
    "installer3" to "Installer3",
    "panelNo1" to "Panel No1",
    "panelNo2" to "Panel No2",
    "inverterSerialNo" to "Inverter Serial No",
    "distanceTpsodlToSectionKm" to "Distance from TPSODL Warehouse to Section Warehouse in KM",
    "distanceSectionToConsumerKm" to "Distance from Section Warehouse to Consumer Site in KM"
)

val ULA_RECORD_KEYS = listOf(
    "id", "consumerKey", "createdAt", "updatedAt", "updatedBy", "createdBy"
) + ULA_EDIT_FIELDS.keys
