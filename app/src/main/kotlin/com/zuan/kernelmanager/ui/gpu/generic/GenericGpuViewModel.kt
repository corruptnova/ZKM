/*
 * Copyright (c) 2025 ZKM
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.zuan.kernelmanager.ui.gpu.generic

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zuan.kernelmanager.ui.gpu.adreno.viewmodel.AdrenoViewModel.FreqState
import com.zuan.kernelmanager.ui.socmenu.CpuGpuUtils
import com.zuan.kernelmanager.utils.GenericGpuUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** GPU devfreq generik (Mali / Xclipse / Tensor PowerVR). Memakai FreqState Adreno agar UI tab bisa dipakai ulang. */
class GenericGpuViewModel(application: Application) : AndroidViewModel(application) {

    data class UiState(
        val isAvailable: Boolean = true,
        val nodeName: String = "",
        val load: String = "0",
        val freqState: FreqState = FreqState()
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    init {
        viewModelScope.launch(Dispatchers.IO) {
            loadAll()
            while (isActive) {
                delay(1500)
                val path = GenericGpuUtils.getGpuPath() ?: continue
                _state.value = _state.value.copy(
                    load = CpuGpuUtils.getGpuUsage(),
                    freqState = _state.value.freqState.copy(currentFreq = GenericGpuUtils.getCurFreq(path))
                )
            }
        }
    }

    private suspend fun loadAll() {
        val path = GenericGpuUtils.getGpuPath()
        if (path == null) {
            _state.value = UiState(isAvailable = false)
            return
        }
        _state.value = UiState(
            isAvailable = true,
            nodeName = path.substringAfterLast('/'),
            load = CpuGpuUtils.getGpuUsage(),
            freqState = FreqState(
                minFreq = GenericGpuUtils.getMinFreq(path),
                maxFreq = GenericGpuUtils.getMaxFreq(path),
                currentFreq = GenericGpuUtils.getCurFreq(path),
                governor = GenericGpuUtils.getGov(path),
                availableFreqs = GenericGpuUtils.getAvailableFreqs(path),
                availableGovs = GenericGpuUtils.getAvailableGovs(path)
            )
        )
    }

    fun updateFreq(type: String, freqMHz: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val path = GenericGpuUtils.getGpuPath() ?: return@launch
            GenericGpuUtils.setFreq(path, type, freqMHz)
            loadAll()
        }
    }

    fun updateGov(gov: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val path = GenericGpuUtils.getGpuPath() ?: return@launch
            GenericGpuUtils.setGov(path, gov)
            loadAll()
        }
    }
}
