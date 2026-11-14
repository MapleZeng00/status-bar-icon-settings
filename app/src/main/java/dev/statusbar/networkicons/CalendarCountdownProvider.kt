package dev.statusbar.networkicons

import android.Manifest
import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.provider.CalendarContract.Attendees
import android.provider.CalendarContract.Instances
import android.util.Log

internal val calendarCountdownUri = Uri.parse("content://io.github.iammaplezeng.statusbaricons.calendar")
internal const val CALENDAR_LOOKAHEAD_MS = 30 * 60_000L

class CalendarCountdownProvider : ContentProvider() {
    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val app = context ?: return null
        if (Build.VERSION.SDK_INT < 29) return null
        if (method != "next" || callingPackage != "com.android.systemui") return null
        app.enforceCallingPermission("android.permission.STATUS_BAR_SERVICE", "SystemUI only")
        if (app.checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) return null
        val identity = clearCallingIdentity()
        return try {
            val now = System.currentTimeMillis()
            val includeTitle = arg == "title"
            val uri = Instances.CONTENT_URI.buildUpon().apply {
                ContentUris.appendId(this, now)
                ContentUris.appendId(this, now + CALENDAR_LOOKAHEAD_MS)
            }.build()
            val columns = if (includeTitle) arrayOf(Instances.BEGIN, Instances.TITLE) else arrayOf(Instances.BEGIN)
            app.contentResolver.query(
                uri, columns,
                "${Instances.BEGIN} > ? AND ${Instances.ALL_DAY} = 0 AND ${Instances.VISIBLE} = 1 " +
                    "AND (${Instances.STATUS} IS NULL OR ${Instances.STATUS} != ?) " +
                    "AND (${Instances.SELF_ATTENDEE_STATUS} IS NULL OR ${Instances.SELF_ATTENDEE_STATUS} != ?)",
                arrayOf(now.toString(), Instances.STATUS_CANCELED.toString(), Attendees.ATTENDEE_STATUS_DECLINED.toString()),
                "${Instances.BEGIN} ASC",
            )?.use { rows ->
                if (!rows.moveToFirst()) return@use null
                Bundle().apply {
                    putLong("start", rows.getLong(0))
                    if (includeTitle) putString("title", rows.getString(1)?.replace(Regex("\\s+"), " ")?.trim()?.take(80))
                }
            }
        } catch (error: RuntimeException) {
            Log.w(LOG_TAG, "Calendar unavailable (${error.javaClass.simpleName})")
            null
        } finally {
            restoreCallingIdentity(identity)
        }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
}
