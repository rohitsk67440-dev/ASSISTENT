package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.tools.FileManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("Zoya Assistant", appName)
  }

  @Test
  fun `file manager creates and reads file`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val fileManager = FileManager(context)
    val createRes = fileManager.createFile("test.txt", "Hello Zoya Agent")
    assertTrue(createRes.startsWith("SUCCESS"))

    val readRes = fileManager.readFile("test.txt")
    assertTrue(readRes.contains("Hello Zoya Agent"))
  }
}
