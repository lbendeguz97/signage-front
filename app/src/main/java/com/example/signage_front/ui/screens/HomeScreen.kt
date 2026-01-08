package com.example.signage_front.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.signage_front.R
import com.example.signage_front.ui.theme.SignagefrontTheme
import kotlinx.coroutines.delay

@Composable
fun HomeScreen(modifier: Modifier = Modifier, onNavigateToAd: () -> Unit) {
    var lastInteraction by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(lastInteraction) {
        delay(60_000)
        onNavigateToAd()
    }

    Box(
        modifier = modifier.fillMaxSize()
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent()
                        lastInteraction = System.currentTimeMillis()
                    }
                }
            }
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val buttonModifier = Modifier
                .width(140.dp)
                .height(140.dp)
            val buttonColors = ButtonDefaults.buttonColors(containerColor = colorResource(id = R.color.green))

            Button(
                onClick = { /*TODO*/ },
                shape = RoundedCornerShape(16.dp),
                colors = buttonColors,
                modifier = buttonModifier
            ) {
                Text("Éttermek")
            }
            Button(
                onClick = { /*TODO*/ },
                shape = RoundedCornerShape(16.dp),
                colors = buttonColors,
                modifier = buttonModifier
            ) {
                Text("Szállodák")
            }
            Button(
                onClick = { /*TODO*/ },
                shape = RoundedCornerShape(16.dp),
                colors = buttonColors,
                modifier = buttonModifier
            ) {
                Text("Szórakozás")
            }
            Button(
                onClick = { onNavigateToAd() },
                shape = RoundedCornerShape(16.dp),
                colors = buttonColors,
                modifier = buttonModifier
            ) {
                Text("Reklámot akarok")
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun HomeScreenPreview() {
    SignagefrontTheme {
        HomeScreen(onNavigateToAd = {})
    }
}
