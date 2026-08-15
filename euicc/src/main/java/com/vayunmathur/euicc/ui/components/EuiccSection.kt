package com.vayunmathur.euicc.ui.components

import androidx.compose.runtime.Composable
import com.vayunmathur.euicc.data.EuiccInfo
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text

@Composable
fun EuiccSection(eid: String?, info: EuiccInfo?) {
    SectionCard(title = "eUICC") {
        Text("EID", style = MaterialTheme.typography.labelMedium)
        Text(eid ?: "unavailable")
        if (info != null) {
            Text("SGP.22 version", style = MaterialTheme.typography.labelMedium)
            Text(info.svn.ifEmpty { "unknown" })
        }
    }
}
