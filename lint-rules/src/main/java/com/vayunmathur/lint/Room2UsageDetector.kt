package com.vayunmathur.lint

import com.android.tools.lint.client.api.UElementHandler
import com.android.tools.lint.detector.api.Category
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Implementation
import com.android.tools.lint.detector.api.Issue
import com.android.tools.lint.detector.api.JavaContext
import com.android.tools.lint.detector.api.Location
import com.android.tools.lint.detector.api.Scope
import com.android.tools.lint.detector.api.Severity
import com.android.tools.lint.detector.api.SourceCodeScanner
import org.jetbrains.uast.UElement
import org.jetbrains.uast.UFile

/**
 * Forbids first-party use of Room 2 (`androidx.room.*`). Room 3 (`androidx.room3.*`) is the
 * only version this repo targets; Room 2 is in maintenance mode and its `SupportSQLite` API
 * has no SQLCipher driver path.
 *
 * Room 2 stays in the *runtime* classpath regardless, because `androidx.work:work-runtime`
 * depends on it for its internal `WorkDatabase` and 13 modules use WorkManager. That is
 * harmless — Room 3 took a new maven group and package precisely so the two can coexist — so
 * the achievable goal, and what this rule enforces, is zero *first-party* Room 2 usage.
 *
 * Matching is done on the file's source text rather than on resolved imports or method calls,
 * because several databases reference Room 2 by fully-qualified name with no import at all
 * (`object : androidx.room.migration.Migration(1, 2)`), which an import-only or
 * `getApplicableMethodNames()` check would miss entirely.
 */
class Room2UsageDetector : Detector(), SourceCodeScanner {

    override fun getApplicableUastTypes(): List<Class<out UElement>> =
        listOf(UFile::class.java)

    override fun createUastHandler(context: JavaContext): UElementHandler =
        object : UElementHandler() {
            override fun visitFile(node: UFile) {
                val source = node.sourcePsi?.text ?: return
                val file = context.file ?: return

                var from = source.indexOf(ROOM2_PREFIX)
                while (from >= 0) {
                    context.report(
                        ISSUE,
                        node,
                        Location.create(file, source, from, from + ROOM2_PREFIX.length),
                        "Room 2 is not used in this repo. Replace `androidx.room.` with " +
                            "`androidx.room3.` (`@TypeConverter`/`@TypeConverters` became " +
                            "`@ColumnTypeConverter`/`@ColumnTypeConverters`, and " +
                            "`RoomDatabase.withTransaction` became `withWriteTransaction`).",
                    )
                    from = source.indexOf(ROOM2_PREFIX, from + ROOM2_PREFIX.length)
                }
            }
        }

    companion object {
        // The trailing dot is what keeps `androidx.room3.` from matching.
        private const val ROOM2_PREFIX = "androidx.room."

        val ISSUE: Issue = Issue.create(
            id = "Room2Usage",
            briefDescription = "Room 2 (androidx.room) used instead of Room 3",
            explanation = """
                This repo is on Room 3 (`androidx.room3`). Room 2 (`androidx.room`) is in \
                maintenance mode and, more importantly, its `SupportSQLite` API is the one \
                Room 3 dropped — `openHelperFactory(...)` no longer exists, so the SQLCipher \
                encryption used by every database here is wired through \
                `setDriver(SQLCipherDriver(...))` instead.

                Replace `androidx.room.` with `androidx.room3.`. Three renames are not \
                mechanical: `@TypeConverter` and `@TypeConverters` are `@ColumnTypeConverter` \
                and `@ColumnTypeConverters`; `RoomDatabase.withTransaction { }` is \
                `withWriteTransaction { }` (or `withReadTransaction`); and `Migration.migrate` \
                takes a suspending `androidx.sqlite.SQLiteConnection` rather than a \
                `SupportSQLiteDatabase`.

                Room 2 remaining on the runtime classpath via `androidx.work:work-runtime` is \
                expected and must not be excluded — WorkManager needs it for `WorkDatabase`.
                """.trimIndent(),
            category = Category.CORRECTNESS,
            priority = 8,
            severity = Severity.ERROR,
            implementation = Implementation(
                Room2UsageDetector::class.java,
                Scope.JAVA_FILE_SCOPE,
            ),
        )
    }
}
