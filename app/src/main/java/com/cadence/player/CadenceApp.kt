package com.cadence.player

import android.app.Application
import androidx.room.Room
import com.cadence.player.data.AppDatabase
import com.cadence.player.data.LibraryScanner
import com.cadence.player.data.Settings
import com.cadence.player.data.USER_MIGRATION_1_2
import com.cadence.player.data.UserDatabase
import com.cadence.player.playback.CadencePlayer

/** Process-wide singletons. The player lives here so the UI and the MediaSessionService share one engine. */
class CadenceApp : Application() {
    val settings by lazy { Settings(this) }
    val db by lazy { Room.databaseBuilder(this, AppDatabase::class.java, "cadence.db").fallbackToDestructiveMigration().build() }
    /** Playlists and listening history live in their own database so a library-cache rebuild never touches them. */
    val userDb by lazy { Room.databaseBuilder(this, UserDatabase::class.java, "cadence_user.db")
            .addMigrations(USER_MIGRATION_1_2)
            .build() }
    val scanner by lazy { LibraryScanner(this, db.tracks(), settings) }
    val player by lazy { CadencePlayer(this, settings, userDb.user()) }
}
