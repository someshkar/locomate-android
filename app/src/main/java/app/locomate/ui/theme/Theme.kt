package app.locomate.ui.theme

import androidx.compose.animation.core.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// Design tokens mirrored 1:1 from Theme.swift
object LM {
    val Accent   = Color(0xFF3E8EF7)
    val AccentHi = Color(0xFF7FB8FF)
    val Success  = Color(0xFF37C982)
    val Warn     = Color(0xFFF5C77E)
    val Ink      = Color(0xFFF7F7F9)
    val Ink2     = Color(0xFFB4B9C4)
    val Ink3     = Color(0xFF8B909B)
    val Hairline = Color.White.copy(alpha = 0.08f)
    val SheetEdge = Color.White.copy(alpha = 0.10f)

    // Springs — same response/damping language as iOS
    val springUI   = spring<Float>(stiffness = Spring.StiffnessMedium, dampingRatio = Spring.DampingRatioLowBouncy)
    val springSnap = spring<Float>(stiffness = Spring.StiffnessMediumLow, dampingRatio = Spring.DampingRatioNoBouncy)
    val springBouncy = spring<Float>(stiffness = Spring.StiffnessLow, dampingRatio = Spring.DampingRatioMediumBouncy)

    val RadiusSheet = 34.dp
    val RadiusCard = 28.dp
    val RadiusNav = 36.dp
}
