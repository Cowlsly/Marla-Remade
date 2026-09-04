# FFDatabase.kt:161 declares `companion object : DatabaseMigrations`, which
# SqlCipher.kt:93 reaches through getDeclaredField("Companion"). The shared root
# file keeps the Companion *field* on every RoomDatabase subclass; this keeps the
# companion *class* it points at, which is a separate type.
-keep class com.vayunmathur.findfamily.data.FFDatabase$Companion { *; }
