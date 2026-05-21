package com.android.imeisettings.ui.security

import android.app.Application
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.android.imeisettings.data.local.AppDatabase
import com.android.imeisettings.data.local.SafeZone
import com.android.imeisettings.data.repository.NetworkStateTracker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SafeZoneViewModel(application: Application) : AndroidViewModel(application) {
    private val db = AppDatabase.getDatabase(application)
    private val dao = db.safeZoneDao()

    private val _safeZones = MutableStateFlow<List<SafeZone>>(emptyList())
    val safeZones: StateFlow<List<SafeZone>> = _safeZones

    init {
        viewModelScope.launch {
            dao.getAllSafeZonesFlow().collectLatest { zones ->
                _safeZones.value = zones
            }
        }
    }

    fun addCurrentLocationAsSafeZone(name: String) {
        viewModelScope.launch {
            Log.d("CONSUL_DEBUG", "Adding Cell Fingerprint zone: $name")
            
            val details1 = NetworkStateTracker.networkDetailsSim1.value
            val details2 = NetworkStateTracker.networkDetailsSim2.value

            // Use data from the active SIM or SIM 1 as primary
            val currentMccMnc = if (details1.mccMnc != "---") details1.mccMnc else details2.mccMnc
            val currentLacTac = if (details1.lacTac != "---") details1.lacTac else details2.lacTac

            if (currentMccMnc == "---" || currentLacTac == "---") {
                withContext(Dispatchers.Main) {
                    Toast.makeText(getApplication(), "Error: No cellular signal detected!", Toast.LENGTH_LONG).show()
                }
                return@launch
            }

            val trustedList = mutableListOf<String>()
            if (details1.cellId != "---" && details1.cellId != "0") trustedList.add(details1.cellId)
            if (details2.cellId != "---" && details2.cellId != "0") trustedList.add(details2.cellId)
            
            val uniqueTrusted = trustedList.distinct()

            if (uniqueTrusted.isNotEmpty()) {
                val newZone = SafeZone(
                    name = name,
                    mccMnc = currentMccMnc,
                    lacTac = currentLacTac,
                    trustedCellIds = uniqueTrusted.joinToString(",")
                )
                dao.insertSafeZone(newZone)
                withContext(Dispatchers.Main) {
                    Toast.makeText(getApplication(), "Safe Zone (Area: $currentLacTac) Created!", Toast.LENGTH_SHORT).show()
                }
            } else {
                withContext(Dispatchers.Main) {
                    Toast.makeText(getApplication(), "Error: No valid Cell ID found!", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    fun addCellToSafeZone(zoneId: Int, cellId: String) {
        viewModelScope.launch {
            val zone = dao.getSafeZoneById(zoneId)
            if (zone != null) {
                val currentCells = zone.trustedCellIds.split(",")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .toMutableList()
                
                if (!currentCells.contains(cellId)) {
                    currentCells.add(cellId)
                    val updatedZone = zone.copy(trustedCellIds = currentCells.joinToString(","))
                    dao.insertSafeZone(updatedZone)
                }
            }
        }
    }

    fun deleteZone(id: Int) {
        viewModelScope.launch {
            dao.deleteSafeZone(id)
        }
    }
}
