package com.example.tools

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import android.provider.CalendarContract
import com.example.data.ZoyaRepository

class ProductivityHelper(
    private val context: Context,
    private val repository: ZoyaRepository
) {

    fun setAlarm(hour: Int, minute: Int, message: String = "Zoya Alarm"): String {
        return try {
            val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, hour.coerceIn(0, 23))
                putExtra(AlarmClock.EXTRA_MINUTES, minute.coerceIn(0, 59))
                putExtra(AlarmClock.EXTRA_MESSAGE, message)
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            val timeFormatted = String.format("%02d:%02d", hour, minute)
            "SUCCESS: Alarm set for $timeFormatted ($message)."
        } catch (e: Exception) {
            "FAILURE: Could not set alarm: ${e.message}"
        }
    }

    fun setTimer(seconds: Int, message: String = "Zoya Timer"): String {
        return try {
            val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                putExtra(AlarmClock.EXTRA_LENGTH, seconds.coerceAtLeast(1))
                putExtra(AlarmClock.EXTRA_MESSAGE, message)
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            "SUCCESS: Timer started for $seconds seconds ($message)."
        } catch (e: Exception) {
            "FAILURE: Could not start timer: ${e.message}"
        }
    }

    fun createReminder(title: String, description: String = "", minutesFromNow: Int = 60): String {
        return try {
            val beginTime = System.currentTimeMillis() + (minutesFromNow * 60 * 1000L)
            val endTime = beginTime + (30 * 60 * 1000L)

            val intent = Intent(Intent.ACTION_INSERT).apply {
                data = CalendarContract.Events.CONTENT_URI
                putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, beginTime)
                putExtra(CalendarContract.EXTRA_EVENT_END_TIME, endTime)
                putExtra(CalendarContract.Events.TITLE, title)
                putExtra(CalendarContract.Events.DESCRIPTION, description)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            "SUCCESS: Opened calendar event creator for '$title' ($minutesFromNow minutes from now)."
        } catch (e: Exception) {
            "FAILURE: Could not open calendar: ${e.message}"
        }
    }

    suspend fun saveNote(title: String, content: String): String {
        return try {
            val id = repository.saveNote(title, content)
            "SUCCESS: Note saved with ID #$id ('$title')."
        } catch (e: Exception) {
            "FAILURE: Error saving note: ${e.message}"
        }
    }

    suspend fun listNotes(): String {
        return try {
            val notes = repository.searchNotes("")
            if (notes.isEmpty()) return "SUCCESS: You have no notes saved yet."

            val sb = StringBuilder("SUCCESS: Found ${notes.size} notes:\n")
            notes.forEach { n ->
                sb.append("- [#${n.id}] ${n.title}: ${n.content.take(50)}${if (n.content.length > 50) "..." else ""}\n")
            }
            sb.toString()
        } catch (e: Exception) {
            "FAILURE: Error retrieving notes: ${e.message}"
        }
    }

    suspend fun deleteNote(noteId: Long): String {
        return try {
            repository.deleteNoteById(noteId)
            "SUCCESS: Note #$noteId deleted."
        } catch (e: Exception) {
            "FAILURE: Error deleting note: ${e.message}"
        }
    }
}
