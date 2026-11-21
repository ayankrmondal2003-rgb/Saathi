package com.example.surakshanet.ui

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.example.surakshanet.R

@Composable
fun BrandLogo(modifier: Modifier = Modifier) {
    Image(painterResource(R.drawable.brand_mark), "SurakshaNet", modifier, contentScale = ContentScale.Fit)
}
