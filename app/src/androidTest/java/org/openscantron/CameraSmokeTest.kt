package org.openscantron

import android.Manifest
import android.content.pm.PackageManager
import android.widget.FrameLayout
import androidx.camera.view.PreviewView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.openscantron.archive.Photos
import org.openscantron.camera.Capture
import org.openscantron.domain.Paper
import org.openscantron.domain.SheetLayout
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class CameraSmokeTest {
    @get:Rule val permission: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.CAMERA)

    @Test fun cameraPreviewProducesCapture() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        assumeTrue(context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA))
        val frames = CountDownLatch(1)
        val response = CountDownLatch(1)
        val original = AtomicReference<File>()
        val error = AtomicReference<String>()
        var session: Capture? = null
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val preview = PreviewView(activity).apply {
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                }
                activity.addContentView(preview, FrameLayout.LayoutParams(480, 640))
                preview.previewStreamState.observe(activity) {
                    if (it == PreviewView.StreamState.STREAMING) frames.countDown()
                }
                session = Capture(activity, activity, preview,
                    SheetLayout("test-template", "test-exam", 10, listOf("A", "B", "C", "D"), Paper.A4),
                    { _, _ -> }, { error.set(it); frames.countDown(); response.countDown() }).also(Capture::start)
            }
            try {
                assertTrue("Timed out waiting for CameraX preview", frames.await(90, TimeUnit.SECONDS))
                assertNull(error.get())
                scenario.onActivity { session?.take(original::set, response::countDown) }
                assertTrue("Timed out waiting for CameraX JPEG", response.await(90, TimeUnit.SECONDS))
                assertNull(error.get())
                val photo = requireNotNull(original.get())
                assertTrue(photo.isFile && photo.length() > 0)
                val bitmap = Photos.oriented(photo)
                assertTrue(bitmap.width > 0 && bitmap.height > 0)
                bitmap.recycle()
            } finally {
                scenario.onActivity { session?.close() }
                original.get()?.delete()
            }
        }
    }
}
