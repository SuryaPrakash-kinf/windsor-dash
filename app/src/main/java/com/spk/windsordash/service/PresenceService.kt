package com.spk.windsordash.service

import android.companion.AssociationInfo
import android.companion.CompanionDeviceService
import androidx.annotation.RequiresApi
import com.spk.windsordash.data.Dash
import com.spk.windsordash.data.LocalLog

/**
 * Android wakes this when the device chosen for auto-connect appears — the car's Bluetooth
 * connecting to the phone, or a BLE dongle coming into range. This used to start the OBD link
 * right away, but Bluetooth range alone isn't a reliable signal the car is actually on (the
 * dongle can sit in range of the house for hours after parking) — that caused ACC to stay on
 * and iSmart to complain. Reconnecting now only happens from opening the app (MainActivity) or
 * the phone connecting to the car's head unit (Android Auto); this just logs the sighting.
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
        LocalLog.event("auto-connect: $what appeared (not reconnecting from range alone)")
    }
}
