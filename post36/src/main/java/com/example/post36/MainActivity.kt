package com.example.post36

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.post36.ui.navigation.Screen
import com.example.post36.ui.screen.BehaviorChangesScreen
import com.example.post36.ui.screen.bondloss.BondLossHandlingScreen
import com.example.post36.ui.theme.Android16SnippetTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent { App() }
    }

    @Composable
    fun App() {
        val navController = rememberNavController()

        Android16SnippetTheme {
            NavHost(
                navController = navController,
                startDestination = Screen.BehaviorChanges
            ) {
                composable<Screen.BehaviorChanges> {
                    BehaviorChangesScreen(
                        onNextClick = {
                            navController.navigate(Screen.BondLossHandling)
                        }
                    )
                }

                composable<Screen.BondLossHandling> {
                    BondLossHandlingScreen()
                }
            }
        }
    }
}