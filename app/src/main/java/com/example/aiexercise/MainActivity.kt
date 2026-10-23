package com.example.aiexercise

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.aiexercise.ui.AiExerciseRoot
import com.example.aiexercise.ui.theme.AiExerciseTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AiExerciseTheme {
                AiExerciseRoot()
            }
        }
    }
}
