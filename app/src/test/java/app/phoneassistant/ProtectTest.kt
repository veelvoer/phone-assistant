package app.phoneassistant

import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ProtectTest {
    @Test fun bankingAppsAreRecognised() {
        val c = RuntimeEnvironment.getApplication()
        assertTrue(Protect.isProtected(c, "com.kbc.mobile.android.phone.kbc"))
        assertFalse(Protect.isProtected(c, "com.snapchat.android"))
        assertFalse(Protect.isProtected(c, null))
        c.getSharedPreferences("p", Context.MODE_PRIVATE).edit().putString("pause_apps", "my.bank.app").commit()
        assertTrue(Protect.isProtected(c, "my.bank.app"))
        assertFalse(Protect.isProtected(c, c.packageName))
    }
}
