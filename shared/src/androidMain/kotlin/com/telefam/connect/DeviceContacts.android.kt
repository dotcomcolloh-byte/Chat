package com.telefam.connect

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap

/**
 * Android bridge: real READ_CONTACTS permission flow + ContentResolver read.
 * Construct with the host Activity (permission prompts need it); reads work with
 * any Context via applicationContext.
 */
actual class DeviceContacts(context: Context) {
    private val appContext = context.applicationContext
    private val activityRef = WeakReference(context as? Activity)

    actual fun permissionStatus(): ContactsPermissionStatus =
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED)
            ContactsPermissionStatus.GRANTED else ContactsPermissionStatus.NOT_DETERMINED

    actual fun requestPermission(onResult: (ContactsPermissionStatus) -> Unit) {
        val activity = activityRef.get()
        if (permissionStatus() == ContactsPermissionStatus.GRANTED) { onResult(ContactsPermissionStatus.GRANTED); return }
        if (activity == null) { onResult(ContactsPermissionStatus.DENIED); return }
        pendingResults[REQUEST_CODE] = onResult
        ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.READ_CONTACTS), REQUEST_CODE)
    }

    actual suspend fun readContacts(): List<DeviceContactEntry> = withContext(Dispatchers.IO) {
        if (permissionStatus() != ContactsPermissionStatus.GRANTED) return@withContext emptyList()
        val phones = mutableMapOf<String, MutableSet<String>>()  // contactId -> numbers
        val emails = mutableMapOf<String, MutableSet<String>>()
        appContext.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.CONTACT_ID, ContactsContract.CommonDataKinds.Phone.NUMBER),
            null, null, null
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
            val numCol = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (c.moveToNext()) phones.getOrPut(c.getString(idCol)) { mutableSetOf() }.add(c.getString(numCol))
        }
        appContext.contentResolver.query(
            ContactsContract.CommonDataKinds.Email.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Email.CONTACT_ID, ContactsContract.CommonDataKinds.Email.ADDRESS),
            null, null, null
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Email.CONTACT_ID)
            val addrCol = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Email.ADDRESS)
            while (c.moveToNext()) emails.getOrPut(c.getString(idCol)) { mutableSetOf() }.add(c.getString(addrCol))
        }
        (phones.keys + emails.keys).map { id ->
            DeviceContactEntry(phones[id]?.toList().orEmpty(), emails[id]?.toList().orEmpty())
        }
    }

    companion object {
        private const val REQUEST_CODE = 4811
        private val pendingResults = ConcurrentHashMap<Int, (ContactsPermissionStatus) -> Unit>()

        /** Call from the host Activity's onRequestPermissionsResult. */
        fun onRequestPermissionsResult(requestCode: Int, grantResults: IntArray) {
            val cb = pendingResults.remove(requestCode) ?: return
            cb(if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED)
                ContactsPermissionStatus.GRANTED else ContactsPermissionStatus.DENIED)
        }
    }
}
