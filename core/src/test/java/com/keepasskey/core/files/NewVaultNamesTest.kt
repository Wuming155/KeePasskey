package com.keepasskey.core.files

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class NewVaultNamesTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun extensionAndWhitespaceNormalizeToOneName() {
        assertEquals("vault.kdbx", NewVaultNames.normalize(" vault.KDBX "))
        assertEquals("vault.kdbx", NewVaultNames.normalize("vault"))
    }

    @Test fun pathEscapeAndEmptyNamesAreRejected() {
        listOf("", "  ", ".", "..", ".kdbx", "../outside", "a/b", "a\\b", "C:vault", "a\u0000b").forEach { name ->
            assertThrows(IllegalArgumentException::class.java) { NewVaultNames.target(folder.root, name) }
        }
        assertTrue(folder.root.listFiles()!!.isEmpty())
    }

    @Test fun suggestionsSkipFilesAndDirectoriesWithoutModifyingThem() {
        val existing = File(folder.root, "passwords.kdbx").apply { writeBytes(byteArrayOf(1)) }
        folder.newFolder("passwords (1).kdbx")
        assertEquals("passwords (2).kdbx", NewVaultNames.suggest(folder.root))
        assertArrayEquals(byteArrayOf(1), existing.readBytes())
    }

    @Test fun caseSensitivityFollowsTheActualFilesystem() {
        val existing = File(folder.root, "VAULT.kdbx").apply { writeBytes(byteArrayOf(1)) }
        val lowercase = File(folder.root, "vault.kdbx")
        val result = NewVaultNames.suggest(folder.root, "vault.kdbx")
        assertEquals(if (lowercase.exists()) "vault (1).kdbx" else "vault.kdbx", result)
        assertArrayEquals(byteArrayOf(1), existing.readBytes())
    }
}
