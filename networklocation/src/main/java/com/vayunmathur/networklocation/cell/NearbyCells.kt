package com.vayunmathur.networklocation.cell

import android.content.Context
import android.telephony.CellIdentityGsm
import android.telephony.CellIdentityLte
import android.telephony.CellIdentityNr
import android.telephony.CellIdentityWcdma
import android.telephony.CellInfo
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoWcdma
import android.telephony.TelephonyManager
import com.vayunmathur.networklocation.BeaconId

/**
 * Reads nearby cell towers from [TelephonyManager]. Only towers with a full,
 * non-sentinel identity (MCC/MNC/cell id/area code) are usable as beacons.
 */
class NearbyCells(context: Context) {
    private val telephonyManager =
        context.applicationContext.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager

    fun scan(): List<BeaconId.Cell> {
        val infos = runCatching { telephonyManager?.allCellInfo }.getOrNull() ?: return emptyList()
        return infos.mapNotNull { it.toCell() }.distinct()
    }

    private fun CellInfo.toCell(): BeaconId.Cell? = when (this) {
        is CellInfoLte -> cellIdentity.toCell()
        is CellInfoGsm -> cellIdentity.toCell()
        is CellInfoWcdma -> cellIdentity.toCell()
        is CellInfoNr -> (cellIdentity as? CellIdentityNr)?.toCell()
        else -> null
    }

    private fun CellIdentityLte.toCell(): BeaconId.Cell? =
        build(mccString?.toIntOrNull(), mncString?.toIntOrNull(), ci, tac)

    private fun CellIdentityGsm.toCell(): BeaconId.Cell? =
        build(mccString?.toIntOrNull(), mncString?.toIntOrNull(), cid, lac)

    private fun CellIdentityWcdma.toCell(): BeaconId.Cell? =
        build(mccString?.toIntOrNull(), mncString?.toIntOrNull(), cid, lac)

    private fun CellIdentityNr.toCell(): BeaconId.Cell? =
        build(mccString?.toIntOrNull(), mncString?.toIntOrNull(), nci.toInt(), tac)

    private fun build(mcc: Int?, mnc: Int?, cellId: Int, areaCode: Int): BeaconId.Cell? {
        if (mcc == null || mnc == null) return null
        if (cellId == CellInfo.UNAVAILABLE || areaCode == CellInfo.UNAVAILABLE) return null
        if (cellId <= 0) return null
        return BeaconId.Cell(mcc = mcc, mnc = mnc, cellId = cellId, tacOrLac = areaCode)
    }
}
