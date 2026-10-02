package com.telefam.connect

/** A single raw device contact entry, already reduced to phones + emails. */
data class DeviceContactEntry(val phones: List<String>, val emails: List<String>)

enum class ContactsPermissionStatus { GRANTED, DENIED, NOT_DETERMINED }

/**
 * Platform bridge for contact discovery. The Android actual requests READ_CONTACTS,
 * reads the address book, and returns entries; the caller hashes them locally
 * (ContactHash) so raw numbers/emails never leave the device.
 */
expect class DeviceContacts {
    fun permissionStatus(): ContactsPermissionStatus
    /** Shows the system permission prompt when needed; invokes [onResult] with the final state. */
    fun requestPermission(onResult: (ContactsPermissionStatus) -> Unit)
    /** Reads the device address book (phones + emails). Empty when permission is missing. */
    suspend fun readContacts(): List<DeviceContactEntry>
}
