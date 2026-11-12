package com.henw.crunchometer.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Every server section is wrapped the same way: status + last good data. */
@Serializable
data class Section<T>(
    val ok: Boolean = false,
    val error: String? = null,
    @SerialName("updated_at") val updatedAt: Double? = null,
    val data: T? = null,
)

@Serializable
data class Summary(
    val version: String = "",
    @SerialName("generated_at") val generatedAt: Double = 0.0,
    val boinc: Section<BoincData> = Section(),
    val gridcoin: Section<GridcoinData> = Section(),
    val system: Section<SystemData> = Section(),
    val lact: Section<LactData> = Section(),
)

// ---- BOINC ---------------------------------------------------------------

@Serializable
data class BoincData(
    val projects: List<Project> = emptyList(),
    val active: List<Task> = emptyList(),
    val completed: List<CompletedTask> = emptyList(),
    val counts: Counts = Counts(),
)

@Serializable
data class Project(
    val url: String = "",
    val name: String = "",
    @SerialName("user_total_credit") val userTotalCredit: Double = 0.0,
    @SerialName("user_rac") val userRac: Double = 0.0,
    @SerialName("host_total_credit") val hostTotalCredit: Double = 0.0,
    @SerialName("host_rac") val hostRac: Double = 0.0,
    val suspended: Boolean = false,
    @SerialName("no_new_work") val noNewWork: Boolean = false,
)

@Serializable
data class Task(
    val name: String = "",
    val project: String = "",
    @SerialName("project_url") val projectUrl: String = "",
    val resources: String? = null,
    val deadline: Double? = null,
    val status: String = "",
    @SerialName("progress_percent") val progressPercent: Double = 0.0,
    @SerialName("elapsed_s") val elapsedS: Double? = null,
    @SerialName("remaining_s") val remainingS: Double? = null,
    val overdue: Boolean = false,
)

@Serializable
data class CompletedTask(
    val name: String = "",
    val project: String = "",
    @SerialName("project_url") val projectUrl: String = "",
    val app: String? = null,
    val status: String = "",
    @SerialName("exit_status") val exitStatus: Int = 0,
    @SerialName("elapsed_s") val elapsedS: Double? = null,
    @SerialName("cpu_s") val cpuS: Double? = null,
    @SerialName("completed_at") val completedAt: Double? = null,
)

@Serializable
data class Counts(
    val running: Int = 0,
    val waiting: Int = 0,
    val suspended: Int = 0,
    val transferring: Int = 0,
    @SerialName("ready_to_report") val readyToReport: Int = 0,
    val errors: Int = 0,
)

// ---- Gridcoin --------------------------------------------------------------

@Serializable
data class GridcoinData(
    /** "wallet", "estimate" or "none" */
    val source: String = "none",
    val magnitude: Double? = null,
    val cpid: String? = null,
    val note: String? = null,
    @SerialName("per_project") val perProject: List<ProjectMagnitude> = emptyList(),
    /** Present when source == wallet: the config-based estimate, for comparison. */
    val estimate: Double? = null,
    @SerialName("wallet_error") val walletError: String? = null,
    /** Balance and rewards; null when the wallet RPC can't be reached. */
    val wallet: WalletInfo? = null,
)

@Serializable
data class WalletInfo(
    val balance: Double? = null,
    val staked: Double? = null,
    /** Stake rewards not yet matured ("newmint"). */
    val immature: Double? = null,
    val unconfirmed: Double? = null,
    @SerialName("pending_research_reward") val pendingResearchReward: Double? = null,
    @SerialName("researcher_status") val researcherStatus: String? = null,
)

@Serializable
data class ProjectMagnitude(
    val name: String = "",
    val url: String = "",
    val rac: Double = 0.0,
    @SerialName("mag_per_rac") val magPerRac: Double? = null,
    val magnitude: Double? = null,
)

// ---- System ----------------------------------------------------------------

@Serializable
data class SystemData(
    val hostname: String = "",
    val cpu: Cpu = Cpu(),
    val gpus: List<Gpu> = emptyList(),
    @SerialName("gpu_error") val gpuError: String? = null,
    val history: History = History(),
)

@Serializable
data class Cpu(
    val model: String = "",
    @SerialName("usage_percent") val usagePercent: Double = 0.0,
    @SerialName("per_core") val perCore: List<Double> = emptyList(),
    @SerialName("freq_mhz") val freqMhz: Double? = null,
    @SerialName("temp_c") val tempC: Double? = null,
    @SerialName("load_avg") val loadAvg: List<Double> = emptyList(),
    @SerialName("mem_used_bytes") val memUsedBytes: Long? = null,
    @SerialName("mem_total_bytes") val memTotalBytes: Long? = null,
)

@Serializable
data class Gpu(
    val index: Int = 0,
    val name: String = "",
    @SerialName("util_percent") val utilPercent: Double? = null,
    @SerialName("mem_util_percent") val memUtilPercent: Double? = null,
    @SerialName("mem_used_bytes") val memUsedBytes: Long? = null,
    @SerialName("mem_total_bytes") val memTotalBytes: Long? = null,
    @SerialName("temp_c") val tempC: Double? = null,
    @SerialName("power_w") val powerW: Double? = null,
    @SerialName("power_limit_w") val powerLimitW: Double? = null,
    @SerialName("core_clock_mhz") val coreClockMhz: Double? = null,
    @SerialName("mem_clock_mhz") val memClockMhz: Double? = null,
    @SerialName("max_core_clock_mhz") val maxCoreClockMhz: Double? = null,
    @SerialName("max_mem_clock_mhz") val maxMemClockMhz: Double? = null,
    @SerialName("fan_percent") val fanPercent: Double? = null,
    val pstate: Int? = null,
)

@Serializable
data class History(
    val cpu: List<Double> = emptyList(),
    val gpu: List<List<Double>> = emptyList(),
)

// ---- LACT (read-only) ------------------------------------------------------

@Serializable
data class LactData(
    @SerialName("daemon_reachable") val daemonReachable: Boolean = false,
    @SerialName("daemon_error") val daemonError: String? = null,
    @SerialName("current_profile") val currentProfile: String? = null,
    val gpus: List<LactGpu> = emptyList(),
)

@Serializable
data class LactGpu(
    val id: String = "",
    val name: String = "",
    val settings: List<Setting> = emptyList(),
)

@Serializable
data class Setting(val key: String = "", val value: String = "")
