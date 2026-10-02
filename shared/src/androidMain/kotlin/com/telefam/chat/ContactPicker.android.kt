package com.telefam.chat

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

@Composable
fun rememberContactPicker(onPicked: (ContactData?) -> Unit): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.PickContact()) { uri ->
        onPicked(uri?.let { readContact(context, it) })
    }
    return { launcher.launch(null) }
}

private fun readContact(context: Context, contactUri: Uri): ContactData? {
    context.contentResolver.query(contactUri, null, null, null, null)?.use { cursor ->
        if (!cursor.moveToFirst()) return null
        val name = cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME)) ?: return null
        val hasPhone = cursor.getInt(cursor.getColumnIndexOrThrow(ContactsContract.Contacts.HAS_PHONE_NUMBER)) > 0
        val contactId = cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.Contacts._ID))
        if (!hasPhone) return ContactData(name, "")

        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI, null,
            "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?", arrayOf(contactId), null
        )?.use { phoneCursor ->
            if (phoneCursor.moveToFirst()) {
                val phone = phoneCursor.getString(phoneCursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER))
                return ContactData(name, phone)
            }
        }
        return ContactData(name, "")
    }
    return null
}

fun saveContactToDevice(context: Context, contact: ContactData) {
    val intent = Intent(ContactsContract.Intents.Insert.ACTION).apply {
        type = ContactsContract.RawContacts.CONTENT_TYPE
        putExtra(ContactsContract.Intents.Insert.NAME, contact.name)
        putExtra(ContactsContract.Intents.Insert.PHONE, contact.phoneNumber)
    }
    context.startActivity(intent)
}

fun callContact(context: Context, contact: ContactData) {
    context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${contact.phoneNumber}")))
}

fun openContact(context: Context, contact: ContactData) {
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("tel:${contact.phoneNumber}")))
}
