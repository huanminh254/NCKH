package vn.tlu.sosmesh.coremap.db

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import vn.tlu.sosmesh.coremap.mesh.MeshQueueEngine

@RunWith(AndroidJUnit4::class)
class MeshMessageDaoTest {
    private lateinit var db: SosMeshDatabase

    @Before
    fun openDatabase() {
        db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            SosMeshDatabase::class.java
        ).build()
    }

    @After
    fun closeDatabase() = db.close()

    private fun sos() = MeshMessageEntity(
        "SOS-ROOM", "SOS", "NODE-A", "Test", 21.0, 105.0, 2,
        "Test", "HIGH", "Test", null, MeshQueueEngine.UNLIMITED_TTL,
        0, "QUEUED", 1_000L, 1_000L
    )

    @Test
    fun delayedAckDoesNotRegressPersistedStatus() = runBlocking {
        val dao = db.messageDao()
        dao.upsertMessage(sos())
        dao.updateStatus("SOS-ROOM", "REACHED", 2_000L)
        dao.updateStatus("SOS-ROOM", "DELIVERED", 3_000L)
        dao.updateStatus("SOS-ROOM", "INVALID", 4_000L)
        assertEquals("REACHED", dao.getMessageById("SOS-ROOM")!!.status)
        assertEquals(2_000L, dao.getMessageById("SOS-ROOM")!!.updatedAt)
    }

    @Test
    fun delayedPayloadDoesNotOverwriteNewerDatabaseState() = runBlocking {
        val dao = db.messageDao()
        dao.mergeMessage(sos().copy(status = "APPROACHING"))
        dao.mergeMessage(sos().copy(status = "QUEUED", content = "Old payload"))
        assertEquals("APPROACHING", dao.getMessageById("SOS-ROOM")!!.status)
        assertEquals("Test", dao.getMessageById("SOS-ROOM")!!.content)
    }

    @Test
    fun persistedQueueCanRehydrateAfterServiceRecreation() = runBlocking {
        val dao = db.messageDao()
        dao.mergeMessage(sos())
        val engine = MeshQueueEngine(maxAgeMs = 10_000L)
        dao.getAllMessages().forEach { engine.enqueueOrUpdate(it, 1_500L) }
        assertEquals(setOf("SOS-ROOM"), engine.summaryVector())
        assertEquals(1, dao.deleteExpiredMessages(11_000L, 10_000L))
        assertEquals(0, dao.getAllMessages().size)
    }
}
