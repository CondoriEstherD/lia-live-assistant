package com.lia.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lia.app.data.model.ThemeMode
import com.lia.app.data.profile.SharedPrefsAppPreferences
import com.lia.app.data.sample.LiaAppViewModel
import com.lia.app.ui.navigation.AppNavigation
import com.lia.app.ui.theme.LiaAppTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val factory = remember {
                object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T {
                        return LiaAppViewModel(
                            appPreferences = SharedPrefsAppPreferences(applicationContext)
                        ) as T
                    }
                }
            }
            val appViewModel: LiaAppViewModel = viewModel(factory = factory)
            val uiState by appViewModel.uiState.collectAsStateWithLifecycle()

            LiaAppTheme(darkTheme = uiState.themeMode == ThemeMode.DARK) {
                AppNavigation(viewModel = appViewModel)
            }
        }
    }
}
