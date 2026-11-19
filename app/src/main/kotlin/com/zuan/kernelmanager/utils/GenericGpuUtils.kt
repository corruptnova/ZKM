/*
 * Copyright (c) 2025 ZKM
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.zuan.kernelmanager.utils

import android.os.SystemClock
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * GPU devfreq generik (Mali/Exynos, Xclipse, Tensor G5 PowerVR "*.gpu0", dll).
 *
 * Semua akses sysfs lewat root shell: SELinux memblokir proses app biasa
 * membaca /sys/class/devfreq di Android modern, jadi java.io.File tidak bisa diandalkan.
 */
object GenericGpuUtils {

    // Cache path agar tidak perlu searching ulang setiap kali request
    @Volatile private var cachedGpuPath: String? = null

    // Path statis GPU (fallback). Hanya node GPU — jangan masukkan devfreq bus/DRAM.
    private val POTENTIAL_PATHS = listOf(
        "/sys/class/devfreq/dfrgx", // Intel/PowerVR Legacy
        "/sys/devices/platform/dfrgx/devfreq/dfrgx",
        "/sys/kernel/gpu", // Samsung Generic Kernel interface
        "/sys/kernel/tegra_gpu" // Nvidia Tegra
    )

    /**
     * Mencari path devfreq GPU yang valid.
     * Support Exynos Mali, Xclipse (*.sgpu), dan Tensor G5 PowerVR (34f00000.gpu0).
     */
    suspend fun getGpuPath(): String? = withContext(Dispatchers.IO) {
        cachedGpuPath?.let { return@withContext it }

        val searchCmds = listOf(
            "find /sys/class/devfreq/ -iname \"*.mali\" -print -quit", // Exynos Mali
            "find /sys/class/devfreq/ -iname \"*.sgpu\" -print -quit", // Exynos Xclipse (AMD)
            "find /sys/class/devfreq/ -iname \"*gpu*\" -print -quit",  // Generic (mis. Tensor G5: 34f00000.gpu0)
            "find /sys/devices/platform/ -iname \"*.mali\" -print -quit"
        )

        for (cmd in searchCmds) {
            try {
                val res = Shell.cmd(cmd).exec()
                val path = res.out.firstOrNull()?.trim()
                if (res.isSuccess && !path.isNullOrEmpty() && isValidDevfreq(path)) {
                    cachedGpuPath = path
                    return@withContext path
                }
            } catch (e: Exception) { /* ignore */ }
        }

        for (path in POTENTIAL_PATHS) {
            if (isValidDevfreq(path)) {
                cachedGpuPath = path
                return@withContext path
            }
        }

        // Sengaja TIDAK fallback ke devfreq pertama di /sys/class/devfreq:
        // itu biasanya bus/memori (MIF/INT/DSU), dan mengubah frekuensinya bukan tuning GPU.
        null
    }

    private fun isValidDevfreq(path: String): Boolean {
        // Valid jika folder ada dan punya node devfreq standar (dicek via root)
        val res = Shell.cmd(
            "[ -e \"$path/available_frequencies\" ] || [ -e \"$path/available_governors\" ] || [ -e \"$path/trans_stat\" ] && echo ok"
        ).exec()
        return res.out.firstOrNull()?.trim() == "ok"
    }

    // === UNITS ===

    /**
     * Devfreq GPU melaporkan Hz (mis. 1094000000) atau KHz (mis. 1094000) tergantung driver.
     * Frekuensi GPU nyata selalu >= 10 MHz, jadi angka >= 10^7 pasti Hz, >= 10^4 pasti KHz.
     */
    fun toMhz(raw: String): String {
        val v = raw.trim().toLongOrNull() ?: return raw.trim()
        return when {
            v >= 10_000_000L -> (v / 1_000_000L).toString() // Hz -> MHz
            v >= 10_000L -> (v / 1_000L).toString() // KHz -> MHz
            else -> v.toString() // Sudah MHz
        }
    }

    // === COMMANDS ===

    suspend fun getMinFreq(path: String): String = toMhz(Utils.readFile("$path/min_freq"))
    suspend fun getMaxFreq(path: String): String = toMhz(Utils.readFile("$path/max_freq"))
    suspend fun getCurFreq(path: String): String = toMhz(Utils.readFile("$path/cur_freq"))
    suspend fun getGov(path: String): String = Utils.readFile("$path/governor")

    private fun readTokens(file: String): List<String> =
        Utils.readFile(file).split(Regex("\\s+")).filter { it.isNotBlank() }

    suspend fun getAvailableFreqs(path: String): List<String> = withContext(Dispatchers.IO) {
        readTokens("$path/available_frequencies")
            .map { toMhz(it) }
            .distinct()
            .sortedBy { it.toLongOrNull() ?: 0L }
    }

    suspend fun getAvailableGovs(path: String): List<String> = withContext(Dispatchers.IO) {
        readTokens("$path/available_governors")
    }

    suspend fun setFreq(path: String, type: String, freqMHz: String) = withContext(Dispatchers.IO) {
        // 1. Cari raw value dari available_frequencies yang cocok dengan MHz user
        val targetRaw = readTokens("$path/available_frequencies").find { toMhz(it) == freqMHz }
            ?: return@withContext false // Jangan tulis nilai MHz mentah ke node yang memakai Hz/KHz

        // 2. Tentukan Node target
        val standardNode = if (type == "min") "min_freq" else "max_freq"
        val lockNode = if (type == "min") "dvfs_min_lock" else "dvfs_max_lock"

        // 3. Samsung: tulis ke DVFS Lock agar tidak di-overwrite sistem
        val checkLock = Shell.cmd("[ -f \"$path/$lockNode\" ] && echo true").exec()
        val hasLockFile = checkLock.out.firstOrNull()?.trim() == "true"

        Utils.writeFile("$path/${if (hasLockFile) lockNode else standardNode}", targetRaw)
    }

    // === LOAD ===

    private val loadMutex = Mutex()
    private var lastBusyMs = -1L
    private var lastSampleAt = 0L
    private var lastLoad = 0

    /**
     * Load GPU dalam persen, atau null jika tidak ada sumber load.
     *
     * 1. Node `load` (format "45@396000000") jika driver menyediakannya.
     * 2. `device/uid_time_in_state` (Pixel / Tensor G5 PowerVR): waktu aktif GPU per-UID per-frekuensi
     *    dalam ms. Load = Δ(total waktu aktif) / Δ(waktu nyata). Butuh dua sampel.
     */
    suspend fun getLoad(path: String): Int? = withContext(Dispatchers.IO) {
        Regex("^\\s*(\\d+)").find(Utils.readFile("$path/load"))?.let {
            return@withContext it.groupValues[1].toInt().coerceIn(0, 100)
        }

        loadMutex.withLock {
            var busy = readUidBusyMs(path) ?: return@withLock null
            var now = SystemClock.elapsedRealtime()

            // Belum ada baseline (atau terlalu lama): ambil sampel kedua sebentar lagi
            if (lastBusyMs < 0 || now - lastSampleAt > 10_000L) {
                lastBusyMs = busy
                lastSampleAt = now
                delay(250)
                busy = readUidBusyMs(path) ?: return@withLock null
                now = SystemClock.elapsedRealtime()
            }

            val dt = now - lastSampleAt
            if (dt < 200L) return@withLock lastLoad // Interval terlalu pendek, hasil akan noise

            val dBusy = busy - lastBusyMs
            // dBusy < 0: baris UID hilang (app di-uninstall) -> pertahankan nilai lama, reset baseline.
            // Beberapa app bisa memakai GPU bersamaan, jadi total bisa > 100%.
            if (dBusy >= 0) lastLoad = (dBusy * 100 / dt).toInt().coerceIn(0, 100)
            lastBusyMs = busy
            lastSampleAt = now
            lastLoad
        }
    }

    private fun readUidBusyMs(path: String): Long? = sumUidTimeInState(Utils.readFile("$path/device/uid_time_in_state"))

    /** Total semua kolom waktu di uid_time_in_state ("uid: f1 f2 ..." lalu "10367: t1 t2 ..."). */
    fun sumUidTimeInState(text: String): Long? {
        var total = 0L
        var rows = 0
        for (line in text.lineSequence()) {
            val parts = line.split(':', limit = 2)
            if (parts.size != 2 || parts[0].trim().toLongOrNull() == null) continue // Header "uid:" / baris rusak
            total += parts[1].trim().split(Regex("\\s+")).sumOf { it.toLongOrNull() ?: 0L }
            rows++
        }
        return if (rows > 0) total else null
    }

    suspend fun setGov(path: String, gov: String) = withContext(Dispatchers.IO) {
        Utils.writeFile("$path/governor", gov)
    }
}
