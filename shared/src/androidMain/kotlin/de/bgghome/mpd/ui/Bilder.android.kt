package de.bgghome.mpd.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import coil3.BitmapImage
import coil3.Image
import coil3.request.ImageRequest
import coil3.request.allowHardware

actual fun Image.zuImageBitmap(): ImageBitmap? = (this as? BitmapImage)?.bitmap?.asImageBitmap()

actual fun ImageRequest.Builder.softwareBitmap(): ImageRequest.Builder = allowHardware(false)
