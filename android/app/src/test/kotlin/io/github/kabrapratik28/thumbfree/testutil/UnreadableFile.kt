package io.github.kabrapratik28.thumbfree.testutil

import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.util.EnumSet

/** Denies read access to [File] and returns a function that restores it. */
internal fun File.denyReadForTest(): () -> Unit {
    check(isFile) { "Cannot deny read access to missing file: $this" }
    val restore: () -> Unit
    if (System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)) {
        val view = Files.getFileAttributeView(toPath(), AclFileAttributeView::class.java)
            ?: error("The Windows test filesystem does not support ACLs")
        val original = view.acl
        // File.setReadable(false) does not deny reads on Windows.
        val deny = AclEntry.newBuilder()
            .setType(AclEntryType.DENY)
            .setPrincipal(Files.getOwner(toPath()))
            .setPermissions(EnumSet.of(AclEntryPermission.READ_DATA))
            .build()
        view.setAcl(listOf(deny) + original)
        restore = { if (exists()) view.setAcl(original) }
    } else {
        check(setReadable(false)) { "Could not remove read access from $this" }
        restore = {
            if (exists()) check(setReadable(true)) { "Could not restore read access to $this" }
        }
    }
    if (canOpenForRead()) {
        restore()
        error("Could not deny read access to $this")
    }
    return restore
}

private fun File.canOpenForRead(): Boolean =
    try {
        FileInputStream(this).use { it.read() }
        true
    } catch (e: IOException) {
        false
    }
