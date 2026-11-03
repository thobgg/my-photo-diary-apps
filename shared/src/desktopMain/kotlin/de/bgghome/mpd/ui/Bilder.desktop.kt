package de.bgghome.mpd.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import coil3.BitmapImage
import coil3.Image
import coil3.request.ImageRequest

actual fun Image.zuImageBitmap(): ImageBitmap? = (this as? BitmapImage)?.bitmap?.asComposeImageBitmap()

actual fun ImageRequest.Builder.softwareBitmap(): ImageRequest.Builder = this
