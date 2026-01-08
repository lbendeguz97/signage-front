package com.example.signage_front.ui.composables

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import com.example.signage_front.R
import com.example.signage_front.ui.theme.SignagefrontTheme

@Composable
fun TopNavBar(
    modifier: Modifier = Modifier,
    onHomeClick: () -> Unit = {},
    onDarkModeClick: () -> Unit = {},
    onLanguageClick: () -> Unit = {}
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.LightGray),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onHomeClick) {
            Icon(
                painter = painterResource(id = R.drawable.outline_family_home_24),
                contentDescription = "Home"
            )
        }
        IconButton(onClick = onDarkModeClick) {
            Icon(
                painter = painterResource(id = R.drawable.baseline_mode_night_24),
                contentDescription = "Dark Mode"
            )
        }
        IconButton(onClick = onLanguageClick) {
            Icon(
                painter = painterResource(id = R.drawable.outline_emoji_language_24),
                contentDescription = "Language"
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
fun TopNavBarPreview() {
    SignagefrontTheme {
        TopNavBar()
    }
}
