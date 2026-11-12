package theseus.agent.storage

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import javax.crypto.AEADBadTagException

class JournalCipherTest {
    private val cipher = JournalCipher("ab".repeat(32))

    @Test
    fun `payload is randomized authenticated and bound to record identity`() {
        val first = cipher.encrypt("one", "invite_code=SECRET")
        val second = cipher.encrypt("one", "invite_code=SECRET")
        assertNotEquals(first, second)
        assertFalse(first.contains("SECRET"))
        assertEquals("invite_code=SECRET", cipher.decrypt("one", first))
        assertThrows<AEADBadTagException> { cipher.decrypt("two", first) }
        assertThrows<AEADBadTagException> { JournalCipher("cd".repeat(32)).decrypt("one", first) }
    }
}
