package com.example.post36.navigation

import androidx.annotation.StringRes
import com.example.post36.R
import kotlinx.serialization.Serializable

@Serializable
sealed class Screen(val route: String, @StringRes val resourceId: Int) {

    @Serializable
    data object BehaviorChanges : Screen(
        route = "behaviorChanges",
        resourceId = R.string.label_behavior_changes
    )
}