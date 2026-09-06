package com.snaptube.downloader.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.snaptube.downloader.R
import com.snaptube.downloader.ui.theme.SnaptubeBlack
import com.snaptube.downloader.ui.theme.SnaptubeOrange
import com.snaptube.downloader.ui.theme.SnaptubeYellow
import kotlinx.coroutines.delay

/**
 * Snaptube-Inspired Branded Splash Opening Screen
 *
 * Exact 1:1 layout matching Snaptube's iconic opening screen:
 * - Minimalist, distraction-free matte dark background (#171717)
 * - Dead-center: Snaptube-style yellow circle with white TV screen and orange-red download arrow
 * - Bottom: Bold, heavy yellow brand typography ("VidSnap")
 * - Dynamic physics animations: Spring pop entrance, cascading downward arrow pulse, smooth slide-up
 */
@Composable
fun SplashScreen(
    onSplashFinished: () -> Unit
) {
    // Logo entrance animations
    val logoScale = remember { Animatable(0.35f) }
    val logoAlpha = remember { Animatable(0f) }

    // Bottom brand text animations
    val textAlpha = remember { Animatable(0f) }
    val textOffset = remember { Animatable(20f) }

    // Arrow animated download pulse
    val arrowDropAnim = remember { Animatable(-10f) }

    // Subtle ambient breathing after pop-in
    val infiniteTransition = rememberInfiniteTransition(label = "splash_infinite")
    val subtlePulse by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.03f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "subtle_pulse"
    )

    // Micro downward arrow nudge (mimicking active download motion)
    val arrowNudge by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 2.5f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 650, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "arrow_nudge"
    )

    // Sequence of animations
    LaunchedEffect(Unit) {
        // 1. Logo pop-in with spring physics
        logoAlpha.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 400, easing = LinearEasing)
        )
    }

    LaunchedEffect(Unit) {
        // Bouncy spring entrance for central logo
        logoScale.animateTo(
            targetValue = 1f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessLow
            )
        )
    }

    LaunchedEffect(Unit) {
        // Arrow smooth slide down into place
        arrowDropAnim.animateTo(
            targetValue = 0f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioLowBouncy,
                stiffness = Spring.StiffnessMediumLow
            )
        )
    }

    LaunchedEffect(Unit) {
        // 2. Bottom brand name fades in and slides up
        delay(250)
        textAlpha.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 450, easing = FastOutSlowInEasing)
        )
    }

    LaunchedEffect(Unit) {
        delay(250)
        textOffset.animateTo(
            targetValue = 0f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioLowBouncy,
                stiffness = Spring.StiffnessMedium
            )
        )
    }

    // Keep splash visible for Snaptube-like branded entrance (~1.8 seconds)
    LaunchedEffect(Unit) {
        delay(1850)
        onSplashFinished()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SnaptubeBlack)
            .statusBarsPadding()
            .navigationBarsPadding(),
        contentAlignment = Alignment.Center
    ) {
        // 1. Centered Iconic Snaptube-Inspired Logo
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(150.dp)
                .scale(logoScale.value * subtlePulse)
                .alpha(logoAlpha.value)
        ) {
            // Outer Vibrant Golden-Yellow Circle
            Box(
                modifier = Modifier
                    .size(146.dp)
                    .clip(CircleShape)
                    .background(SnaptubeYellow),
                contentAlignment = Alignment.Center
            ) {
                // White Video Screen / Monitor with Smooth Rounded Corners
                Box(
                    modifier = Modifier
                        .size(width = 86.dp, height = 54.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.White),
                    contentAlignment = Alignment.Center
                ) {
                    // Download Graphic in Vivid Orange-Red
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier
                            .offset(y = (arrowDropAnim.value + arrowNudge).dp)
                    ) {
                        // Top Horizontal Bar
                        Box(
                            modifier = Modifier
                                .size(width = 22.dp, height = 4.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(SnaptubeOrange)
                        )

                        Spacer(modifier = Modifier.height(3.dp))

                        // Middle Horizontal Bar
                        Box(
                            modifier = Modifier
                                .size(width = 22.dp, height = 4.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(SnaptubeOrange)
                        )

                        Spacer(modifier = Modifier.height(3.dp))

                        // Downward Pointing Arrow (Stem + Triangle Head)
                        Canvas(modifier = Modifier.size(width = 26.dp, height = 15.dp)) {
                            val w = size.width
                            val h = size.height
                            val stemWidth = w * 0.46f
                            val stemHeight = h * 0.42f
                            val stemLeft = (w - stemWidth) / 2f
                            val stemRight = stemLeft + stemWidth

                            val arrowPath = Path().apply {
                                // Stem
                                moveTo(stemLeft, 0f)
                                lineTo(stemRight, 0f)
                                lineTo(stemRight, stemHeight)
                                // Right wing of triangle
                                lineTo(w, stemHeight)
                                // Arrow bottom point
                                lineTo(w / 2f, h)
                                // Left wing of triangle
                                lineTo(0f, stemHeight)
                                lineTo(stemLeft, stemHeight)
                                close()
                            }
                            drawPath(path = arrowPath, color = SnaptubeOrange)
                        }
                    }
                }
            }
        }

        // 2. Bottom Brand Typography: Bold Heavy Yellow "VidSnap"
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 76.dp)
                .offset(y = textOffset.value.dp)
                .alpha(textAlpha.value)
        ) {
            Text(
                text = stringResource(id = R.string.app_name),
                color = SnaptubeYellow,
                fontSize = 36.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = (-0.5).sp,
                textAlign = TextAlign.Center
            )
        }
    }
}
