package dev.tracebox.storage

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StorageSymlinkGuardTest {
    @Test
    fun android_user_storage_guards_from_the_package_directory() {
        assertEquals(
            3,
            androidPrivateStoragePackageIndex(
                listOf("data", "user", "0", "dev.tracebox.fixture", "no_backup", "tracebox"),
            ),
        )
        assertNull(
            androidPrivateStoragePackageIndex(
                listOf("build", "data", "user", "0", "dev.tracebox.fixture", "tracebox"),
            ),
        )
    }

    @Test
    fun platform_prefix_alias_is_allowed_but_package_controlled_aliases_are_rejected() {
        val root = Path.of(
            "build",
            "storage-symlink-guard-tests",
            "data",
            "user",
            "0",
            "dev.tracebox.fixture",
            "no_backup",
            "tracebox",
        ).toAbsolutePath().normalize()
        val platformAlias = root.parent.parent.parent
        val packageDirectory = root.parent.parent

        assertFalse(
            simulatedSymbolicLinkCheck(root, packageDirectory, setOf(platformAlias)),
            "the zygote-owned /data/user/0 alias is outside the application-controlled boundary",
        )
        assertTrue(simulatedSymbolicLinkCheck(root, packageDirectory, setOf(packageDirectory)))
        assertTrue(simulatedSymbolicLinkCheck(root, packageDirectory, setOf(root)))
    }

    @Test
    fun generic_storage_claim_still_rejects_an_ancestor_symlink() {
        val fixture = Files.createTempDirectory("tracebox-storage-symlink-guard")
        val outside = fixture.resolve("outside").also(Files::createDirectories)
        val alias = fixture.resolve("application-controlled-alias")
        if (runCatching { Files.createSymbolicLink(alias, outside) }.isFailure) return

        assertFailsWith<IllegalArgumentException> {
            TraceboxOwnedStorageRoot.claim(alias.resolve("tracebox"))
        }
        assertFalse(
            Files.exists(
                outside.resolve("tracebox").resolve(TraceboxOwnedStorageRoot.OWNERSHIP_MARKER_FILE),
            ),
        )
    }

    private fun simulatedSymbolicLinkCheck(
        path: Path,
        firstGuardedComponent: Path,
        symbolicLinks: Set<Path>,
    ): Boolean = hasSymbolicLinkComponentAtOrBelow(
        path = path,
        firstGuardedComponent = firstGuardedComponent,
        exists = { true },
        isSymbolicLink = { it in symbolicLinks },
    )
}
