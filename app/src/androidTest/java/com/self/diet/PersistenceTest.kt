package com.self.diet

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.room.Room
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.time.LocalDate
import java.util.zip.ZipFile

@RunWith(AndroidJUnit4::class)
class PersistenceTest {
    @Test fun recordsSurviveReopenAndExportWithPhoto() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "verification-${System.nanoTime()}.db"
        var db = Room.databaseBuilder(context, DietDatabase::class.java, databaseName).build()
        val folder = java.io.File(context.cacheDir, "test-export-${System.nanoTime()}").apply { mkdirs() }
        try {
            val photoName = "22222222-2222-2222-2222-222222222222.jpg"
            java.io.File(folder, photoName).writeBytes(byteArrayOf(1, 2, 3))
            val time = "2026-10-08T08:15:00+08:00"
            val meal = Record(kind = Kinds.MEAL, occurredAt = time, date = "2026-10-08", name = "米饭", amount = 250.0, photos = photoName)
            val weight = Record(kind = Kinds.WEIGHT, occurredAt = time, date = "2026-10-08", amount = 90.0)
            val exercise = Record(kind = Kinds.EXERCISE, occurredAt = time, date = "2026-10-08", name = "散步", amount = 30.0, met = 4.0)
            listOf(meal, weight, exercise).forEach { it.validate(); db.records().save(it) }
            db.close()
            db = Room.databaseBuilder(context, DietDatabase::class.java, databaseName).build()
            val read = db.records().all()
            assertEquals(3, read.size)
            assertNull(read.first { it.kind == Kinds.MEAL }.calories)
            db.records().save(meal.copy(calories = 325.0))
            assertEquals(3, db.records().all().size)
            val model = ModelProfile(confirmed = true)
            val snapshot = EnergyModel.snapshot(db.records().all(), model, "2026-10-08", "retrospective")
            db.records().saveSnapshot(snapshot)
            db.close()
            db = Room.databaseBuilder(context, DietDatabase::class.java, databaseName).build()
            assertEquals(snapshot, db.records().snapshots().single())
            val target = java.io.File(folder, "verified.zip")
            target.outputStream().use { Export.writeZip(it, db.records().all(), folder, LocalDate.parse("2026-10-08"), LocalDate.parse("2026-10-08"), model, db.records().snapshots()) }
            ZipFile(target).use { zip -> assertNotNull(zip.getEntry("photos/$photoName")); assertTrue(zip.getInputStream(zip.getEntry("meals.csv")).reader(Charsets.UTF_8).readText().contains("325")) }
            db.records().delete(meal.id)
            assertEquals(2, db.records().all().size)
        } finally { db.close(); context.deleteDatabase(databaseName); folder.deleteRecursively() }
    }
    @Test fun importedPhotoIsOwnedAndReadableAfterSourceRemoved() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val source = java.io.File(context.cacheDir, "test-photo-${System.nanoTime()}.png")
        var owned: java.io.File? = null
        try {
            val bitmap = android.graphics.Bitmap.createBitmap(2400, 1200, android.graphics.Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(android.graphics.Color.GREEN)
            source.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
            owned = java.io.File(PhotoFiles.directory(context), PhotoFiles.import(context, android.net.Uri.fromFile(source)))
            source.delete()
            val decoded = android.graphics.BitmapFactory.decodeFile(owned.path)
            assertNotNull(decoded)
            assertEquals(2048, decoded.width)
            assertEquals(1024, decoded.height)
            decoded.recycle()
        } finally { source.delete(); owned?.delete() }
    }
}
