package org.openscantron

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import dagger.hilt.android.AndroidEntryPoint
import org.openscantron.ui.Classroom
import org.openscantron.ui.ClassroomModel

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val model: ClassroomModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        model.repository.schedule()
        setContent { Classroom(model) }
    }
}
