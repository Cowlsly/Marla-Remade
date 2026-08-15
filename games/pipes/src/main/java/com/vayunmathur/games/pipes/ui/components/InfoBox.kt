package com.vayunmathur.games.pipes.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text

@Composable
fun InfoBox(title: String, content: @Composable () -> Unit) {
    Surface(modifier = Modifier.size(width = 150.dp, height = 120.dp), shape = RoundedCornerShape(12.dp)) { Column(modifier = Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceAround) { Text(text = title, fontSize = 16.sp); content() } }
}
