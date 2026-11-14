package com.blila.app

import android.content.Context
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import java.util.Locale
import java.util.concurrent.Executors

/** Tab ids: 0 Vitals, 1 GPS, 2 Cells, 3 Motion. */
val TabIds = listOf(0, 1, 2, 3)
fun tabName(i: Int): String = when (i) {
    0 -> "Vitals"
    1 -> "GPS"
    2 -> "Cells"
    else -> "Motion"
}
val TabKeys = listOf("vitals", "gps", "cells", "motion")

class Settings(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("blila", Context.MODE_PRIVATE)

    /** 0 = system, 1 = light, 2 = dark */
    var theme by mutableIntStateOf(prefs.getInt("theme", 0))
    val tabOn = mutableStateListOf<Boolean>().also { l ->
        for (i in TabIds) l.add(prefs.getBoolean("tab_$i", true))
    }
    val saveOn = mutableStateListOf<Boolean>().also { l ->
        for (i in TabIds) l.add(prefs.getBoolean("save_$i", false))
    }

    init {
        val saved = prefs.getString("lang", null)
        Lang.fr = if (saved != null) saved == "fr" else Locale.getDefault().language == "fr"
    }

    fun updateTheme(v: Int) {
        theme = v
        prefs.edit().putInt("theme", v).apply()
    }

    fun setLang(fr: Boolean) {
        Lang.fr = fr
        prefs.edit().putString("lang", if (fr) "fr" else "en").apply()
    }

    fun setTab(i: Int, v: Boolean) {
        tabOn[i] = v
        prefs.edit().putBoolean("tab_$i", v).apply()
    }

    fun setSave(i: Int, v: Boolean) {
        saveOn[i] = v
        prefs.edit().putBoolean("save_$i", v).apply()
    }
}

object App {
    lateinit var store: Store
    lateinit var settings: Settings
}

class Rec(
    val id: Long, val ts: Long,
    val a: Double?, val b: Double?, val c: Double?, val d: Double?,
    val label: String
)

class Agg(val t: Long, val avg: Double, val min: Double, val max: Double, val n: Int)

/** Small SQLite store shared by all tabs. Column a is the main value of a sample. */
class Store(ctx: Context) : SQLiteOpenHelper(ctx, "sgh.db", null, 1) {
    private val io = Executors.newSingleThreadExecutor()

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE s(id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER NOT NULL, tab TEXT NOT NULL, " +
                "kind TEXT NOT NULL, a REAL, b REAL, c REAL, d REAL, label TEXT)"
        )
        db.execSQL("CREATE INDEX s_idx ON s(tab, kind, ts)")
    }

    override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) {}

    fun add(
        tab: String, kind: String, a: Double? = null, b: Double? = null,
        c: Double? = null, d: Double? = null, label: String = ""
    ) {
        val ts = System.currentTimeMillis()
        io.execute {
            try {
                val cv = ContentValues()
                cv.put("ts", ts)
                cv.put("tab", tab)
                cv.put("kind", kind)
                if (a != null) cv.put("a", a) else cv.putNull("a")
                if (b != null) cv.put("b", b) else cv.putNull("b")
                if (c != null) cv.put("c", c) else cv.putNull("c")
                if (d != null) cv.put("d", d) else cv.putNull("d")
                cv.put("label", label)
                writableDatabase.insert("s", null, cv)
            } catch (e: Exception) {
            }
        }
    }

    /** Buckets values of column a over time. bucket = 1 gives raw points. */
    fun agg(tab: String, kind: String, since: Long, bucket: Long, offset: Long): List<Agg> {
        val out = ArrayList<Agg>()
        try {
            readableDatabase.rawQuery(
                "SELECT ((ts+($offset))/$bucket)*$bucket-($offset), AVG(a), MIN(a), MAX(a), COUNT(a) FROM s " +
                    "WHERE tab=? AND kind=? AND ts>=$since AND a IS NOT NULL GROUP BY 1 ORDER BY 1",
                arrayOf(tab, kind)
            ).use { c ->
                while (c.moveToNext()) {
                    out.add(Agg(c.getLong(0), c.getDouble(1), c.getDouble(2), c.getDouble(3), c.getInt(4)))
                }
            }
        } catch (e: Exception) {
        }
        return out
    }

    fun rows(tab: String, kind: String, limit: Int): List<Rec> {
        val out = ArrayList<Rec>()
        try {
            readableDatabase.rawQuery(
                "SELECT id, ts, a, b, c, d, label FROM s WHERE tab=? AND kind=? ORDER BY ts DESC LIMIT $limit",
                arrayOf(tab, kind)
            ).use { c ->
                fun dbl(i: Int): Double? = if (c.isNull(i)) null else c.getDouble(i)
                while (c.moveToNext()) {
                    out.add(Rec(c.getLong(0), c.getLong(1), dbl(2), dbl(3), dbl(4), dbl(5), c.getString(6) ?: ""))
                }
            }
        } catch (e: Exception) {
        }
        return out
    }

    fun labels(tab: String, kind: String, since: Long): List<Pair<String, Int>> {
        val out = ArrayList<Pair<String, Int>>()
        try {
            readableDatabase.rawQuery(
                "SELECT label, COUNT(*) FROM s WHERE tab=? AND kind=? AND ts>=$since GROUP BY label ORDER BY 2 DESC LIMIT 6",
                arrayOf(tab, kind)
            ).use { c -> while (c.moveToNext()) out.add(Pair(c.getString(0) ?: "", c.getInt(1))) }
        } catch (e: Exception) {
        }
        return out
    }

    fun count(tab: String): Int {
        try {
            readableDatabase.rawQuery("SELECT COUNT(*) FROM s WHERE tab=?", arrayOf(tab)).use { c ->
                if (c.moveToFirst()) return c.getInt(0)
            }
        } catch (e: Exception) {
        }
        return 0
    }

    fun delete(id: Long) {
        io.execute { try { writableDatabase.delete("s", "id=?", arrayOf(id.toString())) } catch (e: Exception) {} }
    }

    fun clear(tab: String, kind: String? = null) {
        io.execute {
            try {
                if (kind == null) writableDatabase.delete("s", "tab=?", arrayOf(tab))
                else writableDatabase.delete("s", "tab=? AND kind=?", arrayOf(tab, kind))
            } catch (e: Exception) {
            }
        }
    }

    fun prune() {
        val cutoff = System.currentTimeMillis() - 60L * 24 * 3600 * 1000
        io.execute { try { writableDatabase.delete("s", "ts<$cutoff", null) } catch (e: Exception) {} }
    }
}
