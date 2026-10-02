package com.telefam.connect

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.Contacts.*

/** iOS bridge: real CNContactStore authorization + fetch of phone numbers and emails. */
actual class DeviceContacts {
    private val store = CNContactStore()

    actual fun permissionStatus(): ContactsPermissionStatus =
        when (CNContactStore.authorizationStatusForEntityType(CNEntityType.CNEntityTypeContacts)) {
            CNAuthorizationStatus.CNAuthorizationStatusAuthorized -> ContactsPermissionStatus.GRANTED
            CNAuthorizationStatus.CNAuthorizationStatusDenied,
            CNAuthorizationStatus.CNAuthorizationStatusRestricted -> ContactsPermissionStatus.DENIED
            else -> ContactsPermissionStatus.NOT_DETERMINED
        }

    actual fun requestPermission(onResult: (ContactsPermissionStatus) -> Unit) {
        if (permissionStatus() == ContactsPermissionStatus.GRANTED) { onResult(ContactsPermissionStatus.GRANTED); return }
        store.requestAccessForEntityType(CNEntityType.CNEntityTypeContacts) { granted, _ ->
            onResult(if (granted) ContactsPermissionStatus.GRANTED else ContactsPermissionStatus.DENIED)
        }
    }

    @Suppress("UNCHECKED_CAST")
    actual suspend fun readContacts(): List<DeviceContactEntry> = withContext(Dispatchers.Default) {
        if (permissionStatus() != ContactsPermissionStatus.GRANTED) return@withContext emptyList()
        val keys = listOf(CNContactPhoneNumbersKey, CNContactEmailAddressesKey)
        val request = CNContactFetchRequest(keysToFetch = keys)
        val entries = mutableListOf<DeviceContactEntry>()
        store.enumerateContactsWithFetchRequest(request, error = null) { contact, _ ->
            contact ?: return@enumerateContactsWithFetchRequest
            val phones = (contact.phoneNumbers as? List<CNLabeledValue>)
                ?.mapNotNull { (it.value as? CNPhoneNumber)?.stringValue }.orEmpty()
            val emails = (contact.emailAddresses as? List<CNLabeledValue>)
                ?.mapNotNull { it.value as? String }.orEmpty()
            if (phones.isNotEmpty() || emails.isNotEmpty()) entries += DeviceContactEntry(phones, emails)
        }
        entries
    }
}
