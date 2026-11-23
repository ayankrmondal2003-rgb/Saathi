package `in`.maitri.app.ui

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import `in`.maitri.app.R

@Composable
fun BrandLogo(modifier: Modifier = Modifier) {
    Image(painterResource(R.drawable.brand_mark), "Maitri", modifier, contentScale = ContentScale.Fit)
}
