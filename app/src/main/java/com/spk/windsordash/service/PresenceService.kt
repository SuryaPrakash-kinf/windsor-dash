package com.spk.windsordash.service

import android.companion.AssociationInfo
import android.companion.CompanionDeviceService
import androidx.annotation.RequiresApi
import com.spk.windsordash.data.Dash
import com.spk.windsordash.data.LocalLog
import com.spk.windsordash.data.LinkStatus

/**
 * Android wakes this when the device chosen for auto-connect appears — the car's Bluetooth
 * connecting to the phone, or a BLE dongle coming into range — and we start the OBD link.
 */
@RequiresApi(31)
class PresenceService : CompanionDeviceService() {

    @Deprecated("Deprecated in Java")
    override fun onDeviceAppeared(address: String) = appeared(address)

    override fun onDeviceAppeared(associationInfo: AssociationInfo) =
        appeared(associationInfo.deviceMacAddress?.toString() ?: "device")

    @Deprecated("Deprecated in Java")
    override fun onDeviceDisappeared(address: String) {}

    override fun onDeviceDisappeared(associationInfo: AssociationInfo) {}

    private fun appeared(what: String) {
        Dash.init(this)
        if (Dash.savedAdapter == null) return
        val st = Dash.state.value.status
        if (st == LinkStatus.IDLE) {
            LocalLog.event("auto-connect: $what appeared")
            LinkService.startAuto(this)
        }
    }
}
