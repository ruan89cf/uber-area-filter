package com.ruan.uberareafilter.storage

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Banco local do painel de Ganhos (SQLite, so no celular).
 *
 * entries: tudo que mexe no dinheiro
 *   kind = RIDE (corrida lida pelo app ou lancada a mao) | INCOME (gorjeta, bonus, promocao)
 *        | EXPENSE (gasto: pneu, oleo, lavagem...). recurring=1 -> gasto fixo mensal (aluguel, seguro...)
 *   status = OPEN (aceita, em andamento) | DONE (concluida) | CANCELLED (nao conta no ganho)
 *   source = AUTO (aceite automatico) | MANUAL (aceite no dedo detectado) | USER (lancado no painel)
 * days: um registro por dia (yyyy-MM-dd) com tempo online medido, horas manuais e numeros das ofertas.
 */
class EarningsStore private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION), LedgerStore {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE entries(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                ts INTEGER NOT NULL,
                kind TEXT NOT NULL,
                app TEXT NOT NULL DEFAULT '',
                amount REAL NOT NULL DEFAULT 0,
                category TEXT NOT NULL DEFAULT '',
                note TEXT NOT NULL DEFAULT '',
                km REAL NOT NULL DEFAULT 0,
                pickup_km REAL NOT NULL DEFAULT 0,
                minutes REAL NOT NULL DEFAULT 0,
                fuel_cost REAL NOT NULL DEFAULT 0,
                status TEXT NOT NULL DEFAULT 'DONE',
                source TEXT NOT NULL DEFAULT 'USER',
                recurring INTEGER NOT NULL DEFAULT 0,
                pickup TEXT NOT NULL DEFAULT '',
                dropoff TEXT NOT NULL DEFAULT '',
                updated_at INTEGER NOT NULL DEFAULT 0
            )"""
        )
        db.execSQL("CREATE INDEX idx_entries_ts ON entries(ts)")
        db.execSQL(
            """CREATE TABLE days(
                day TEXT PRIMARY KEY,
                online_sec INTEGER NOT NULL DEFAULT 0,
                manual_min INTEGER NOT NULL DEFAULT -1,
                offers INTEGER NOT NULL DEFAULT 0,
                rejected INTEGER NOT NULL DEFAULT 0,
                accepted INTEGER NOT NULL DEFAULT 0,
                first_ts INTEGER NOT NULL DEFAULT 0,
                last_ts INTEGER NOT NULL DEFAULT 0,
                reasons TEXT NOT NULL DEFAULT '{}'
            )"""
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v1 e a primeira versao; migracoes futuras entram aqui
    }

    // ---------- copia na conta (servidor) ----------

    /** Quantos lancamentos existem (0 = celular novo / app reinstalado). */
    fun entryCount(): Long = readableDatabase.rawQuery("SELECT COUNT(*) FROM entries", null).use { c ->
        if (c.moveToFirst()) c.getLong(0) else 0L
    }

    /** Copia consistente do banco num arquivo (para mandar ao servidor). */
    @Synchronized
    fun exportTo(dest: java.io.File) {
        dest.delete()
        val db = writableDatabase
        val ok = runCatching { db.execSQL("VACUUM INTO ?", arrayOf(dest.absolutePath)); true }.getOrDefault(false)
        if (!ok) {
            runCatching { db.rawQuery("PRAGMA wal_checkpoint(FULL)", null).use { it.moveToFirst() } }
            java.io.File(db.path).copyTo(dest, overwrite = true)
        }
    }

    /**
     * Junta um banco baixado da conta neste (sem fechar o banco em uso). Lancamentos entram todos;
     * dias que ja existem aqui ficam como estao. Devolve quantos lancamentos entraram.
     */
    @Synchronized
    fun importFrom(src: java.io.File): Int {
        val db = writableDatabase
        fun cols(schema: String, table: String): List<String> =
            db.rawQuery("PRAGMA $schema.table_info($table)", null).use { c ->
                val out = mutableListOf<String>()
                while (c.moveToNext()) out += c.getString(1)
                out
            }
        db.execSQL("ATTACH DATABASE ? AS bk", arrayOf(src.absolutePath))
        try {
            val before = entryCount()
            db.beginTransaction()
            try {
                val e = cols("main", "entries").intersect(cols("bk", "entries").toSet()).filter { it != "id" }.joinToString(",")
                if (e.isNotEmpty()) db.execSQL("INSERT INTO main.entries($e) SELECT $e FROM bk.entries")
                val d = cols("main", "days").intersect(cols("bk", "days").toSet()).joinToString(",")
                if (d.isNotEmpty()) db.execSQL("INSERT OR IGNORE INTO main.days($d) SELECT $d FROM bk.days")
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            return (entryCount() - before).toInt()
        } finally {
            runCatching { db.execSQL("DETACH DATABASE bk") }
        }
    }

    /** Apaga lancamentos e dias ANTES de `dayKey` (aaaa-mm-dd); o dia escolhido fica. Devolve quantos lancamentos saiu. */
    @Synchronized
    fun deleteBefore(dayKey: String): Int {
        val ts = runCatching { SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(dayKey)!!.time }.getOrNull() ?: return 0
        val db = writableDatabase
        val n = db.delete("entries", "ts < ?", arrayOf(ts.toString()))
        db.delete("days", "day < ?", arrayOf(dayKey))
        return n
    }

    // ---------- entries ----------

    data class Entry(
        val id: Long = 0L,
        val ts: Long,
        val kind: String,
        val app: String = "",
        val amount: Double = 0.0,
        val category: String = "",
        val note: String = "",
        val km: Double = 0.0,
        val pickupKm: Double = 0.0,
        val minutes: Double = 0.0,
        val fuelCost: Double = 0.0,
        val status: String = STATUS_DONE,
        val source: String = SOURCE_USER,
        val recurring: Boolean = false,
        val pickup: String = "",
        val dropoff: String = ""
    )

    @Synchronized
    override fun insert(e: Entry): Long = writableDatabase.insert("entries", null, e.toValues())

    @Synchronized
    fun update(e: Entry): Boolean =
        writableDatabase.update("entries", e.toValues(), "id=?", arrayOf(e.id.toString())) > 0

    @Synchronized
    override fun setStatus(id: Long, status: String): Boolean {
        val cv = ContentValues().apply {
            put("status", status)
            put("updated_at", System.currentTimeMillis())
        }
        return writableDatabase.update("entries", cv, "id=?", arrayOf(id.toString())) > 0
    }

    /** Duracao real da corrida (minutos), gravada quando ela termina. */
    @Synchronized
    override fun setMinutes(id: Long, minutes: Double): Boolean {
        val cv = ContentValues().apply {
            put("minutes", minutes)
            put("updated_at", System.currentTimeMillis())
        }
        return writableDatabase.update("entries", cv, "id=?", arrayOf(id.toString())) > 0
    }

    @Synchronized
    fun delete(id: Long): Boolean = writableDatabase.delete("entries", "id=?", arrayOf(id.toString())) > 0

    @Synchronized
    fun get(id: Long): Entry? = readableDatabase.rawQuery("SELECT * FROM entries WHERE id=?", arrayOf(id.toString())).use { c ->
        if (c.moveToFirst()) readEntry(c) else null
    }

    /** Corridas aceitas ha mais de 4 h que nunca foram fechadas -> concluidas (o app nao viu o fim). */
    @Synchronized
    fun closeStaleOpenRides(now: Long = System.currentTimeMillis()): Int {
        val cv = ContentValues().apply {
            put("status", STATUS_DONE)
            put("updated_at", now)
        }
        return writableDatabase.update(
            "entries", cv, "status=? AND ts<?", arrayOf(STATUS_OPEN, (now - 4 * 3_600_000L).toString())
        )
    }

    /** Corridas ainda em andamento (OPEN) desde [fromTs], da mais antiga para a mais nova. */
    @Synchronized
    override fun openRidesSince(fromTs: Long): List<Entry> =
        readableDatabase.rawQuery(
            "SELECT * FROM entries WHERE kind=? AND status=? AND ts>=? ORDER BY ts ASC",
            arrayOf(KIND_RIDE, STATUS_OPEN, fromTs.toString())
        ).use { c ->
            val out = ArrayList<Entry>()
            while (c.moveToNext()) out.add(readEntry(c))
            out
        }

    @Synchronized
    fun entriesSince(fromTs: Long): List<Entry> =
        readableDatabase.rawQuery(
            "SELECT * FROM entries WHERE ts>=? OR recurring=1 ORDER BY ts DESC",
            arrayOf(fromTs.toString())
        ).use { c ->
            val out = ArrayList<Entry>()
            while (c.moveToNext()) out.add(readEntry(c))
            out
        }

    private fun readEntry(c: android.database.Cursor) = Entry(
        id = c.getLong(c.getColumnIndexOrThrow("id")),
        ts = c.getLong(c.getColumnIndexOrThrow("ts")),
        kind = c.getString(c.getColumnIndexOrThrow("kind")),
        app = c.getString(c.getColumnIndexOrThrow("app")),
        amount = c.getDouble(c.getColumnIndexOrThrow("amount")),
        category = c.getString(c.getColumnIndexOrThrow("category")),
        note = c.getString(c.getColumnIndexOrThrow("note")),
        km = c.getDouble(c.getColumnIndexOrThrow("km")),
        pickupKm = c.getDouble(c.getColumnIndexOrThrow("pickup_km")),
        minutes = c.getDouble(c.getColumnIndexOrThrow("minutes")),
        fuelCost = c.getDouble(c.getColumnIndexOrThrow("fuel_cost")),
        status = c.getString(c.getColumnIndexOrThrow("status")),
        source = c.getString(c.getColumnIndexOrThrow("source")),
        recurring = c.getInt(c.getColumnIndexOrThrow("recurring")) == 1,
        pickup = c.getString(c.getColumnIndexOrThrow("pickup")),
        dropoff = c.getString(c.getColumnIndexOrThrow("dropoff"))
    )

    private fun Entry.toValues() = ContentValues().apply {
        put("ts", ts)
        put("kind", kind)
        put("app", app)
        put("amount", amount)
        put("category", category)
        put("note", note)
        put("km", km)
        put("pickup_km", pickupKm)
        put("minutes", minutes)
        put("fuel_cost", fuelCost)
        put("status", status)
        put("source", source)
        put("recurring", if (recurring) 1 else 0)
        put("pickup", pickup)
        put("dropoff", dropoff)
        put("updated_at", System.currentTimeMillis())
    }

    // ---------- days ----------

    data class DayStats(
        val day: String,
        val onlineSec: Long = 0L,
        val manualMin: Int = -1,
        val offers: Int = 0,
        val rejected: Int = 0,
        val accepted: Int = 0,
        val firstTs: Long = 0L,
        val lastTs: Long = 0L,
        val reasons: JSONObject = JSONObject()
    )

    @Synchronized
    fun day(day: String): DayStats =
        readableDatabase.rawQuery("SELECT * FROM days WHERE day=?", arrayOf(day)).use { c ->
            if (c.moveToFirst()) readDay(c) else DayStats(day)
        }

    @Synchronized
    fun daysSince(fromDay: String): List<DayStats> =
        readableDatabase.rawQuery("SELECT * FROM days WHERE day>=? ORDER BY day", arrayOf(fromDay)).use { c ->
            val out = ArrayList<DayStats>()
            while (c.moveToNext()) out.add(readDay(c))
            out
        }

    private fun readDay(c: android.database.Cursor) = DayStats(
        day = c.getString(c.getColumnIndexOrThrow("day")),
        onlineSec = c.getLong(c.getColumnIndexOrThrow("online_sec")),
        manualMin = c.getInt(c.getColumnIndexOrThrow("manual_min")),
        offers = c.getInt(c.getColumnIndexOrThrow("offers")),
        rejected = c.getInt(c.getColumnIndexOrThrow("rejected")),
        accepted = c.getInt(c.getColumnIndexOrThrow("accepted")),
        firstTs = c.getLong(c.getColumnIndexOrThrow("first_ts")),
        lastTs = c.getLong(c.getColumnIndexOrThrow("last_ts")),
        reasons = runCatching { JSONObject(c.getString(c.getColumnIndexOrThrow("reasons"))) }.getOrDefault(JSONObject())
    )

    @Synchronized
    private fun saveDay(d: DayStats) {
        val cv = ContentValues().apply {
            put("day", d.day)
            put("online_sec", d.onlineSec)
            put("manual_min", d.manualMin)
            put("offers", d.offers)
            put("rejected", d.rejected)
            put("accepted", d.accepted)
            put("first_ts", d.firstTs)
            put("last_ts", d.lastTs)
            put("reasons", d.reasons.toString())
        }
        writableDatabase.insertWithOnConflict("days", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    @Synchronized
    override fun addOnline(ts: Long, seconds: Long) {
        if (seconds <= 0L) return
        val d = day(dayKey(ts))
        saveDay(
            d.copy(
                onlineSec = d.onlineSec + seconds,
                firstTs = if (d.firstTs == 0L) ts - seconds * 1000L else d.firstTs,
                lastTs = ts
            )
        )
    }

    @Synchronized
    override fun countOffer(ts: Long) {
        val d = day(dayKey(ts))
        saveDay(d.copy(offers = d.offers + 1))
    }

    @Synchronized
    override fun countRejected(ts: Long, reasonKeys: List<String>) {
        val d = day(dayKey(ts))
        val r = d.reasons
        reasonKeys.filter { it.isNotBlank() }.distinct().forEach { k -> r.put(k, r.optInt(k, 0) + 1) }
        saveDay(d.copy(rejected = d.rejected + 1, reasons = r))
    }

    @Synchronized
    override fun countAccepted(ts: Long) {
        val d = day(dayKey(ts))
        saveDay(d.copy(accepted = d.accepted + 1))
    }

    /** Horas trabalhadas digitadas pelo motorista (substitui o tempo medido). -1 volta para o medido. */
    @Synchronized
    fun setManualMinutes(day: String, minutes: Int) {
        val d = day(day)
        saveDay(d.copy(manualMin = minutes))
    }

    // ---------- export ----------

    fun toJson(fromTs: Long, fromDay: String): JSONObject {
        closeStaleOpenRides()
        val arr = JSONArray()
        entriesSince(fromTs).forEach { e ->
            arr.put(JSONObject().apply {
                put("id", e.id); put("ts", e.ts); put("kind", e.kind); put("app", e.app)
                put("amount", e.amount); put("category", e.category); put("note", e.note)
                put("km", e.km); put("pickupKm", e.pickupKm); put("minutes", e.minutes)
                put("fuelCost", e.fuelCost); put("status", e.status); put("source", e.source)
                put("recurring", e.recurring); put("pickup", e.pickup); put("dropoff", e.dropoff)
            })
        }
        val days = JSONArray()
        daysSince(fromDay).forEach { d ->
            days.put(JSONObject().apply {
                put("day", d.day); put("onlineSec", d.onlineSec); put("manualMin", d.manualMin)
                put("offers", d.offers); put("rejected", d.rejected); put("accepted", d.accepted)
                put("firstTs", d.firstTs); put("lastTs", d.lastTs); put("reasons", d.reasons)
            })
        }
        return JSONObject().put("entries", arr).put("days", days)
    }

    companion object {
        private const val DB_NAME = "ganhos.db"
        private const val DB_VERSION = 1

        const val KIND_RIDE = "RIDE"
        const val KIND_INCOME = "INCOME"
        const val KIND_EXPENSE = "EXPENSE"
        const val STATUS_OPEN = "OPEN"
        const val STATUS_DONE = "DONE"
        const val STATUS_CANCELLED = "CANCELLED"
        const val SOURCE_AUTO = "AUTO"
        const val SOURCE_MANUAL = "MANUAL"
        const val SOURCE_USER = "USER"

        @Volatile
        private var instance: EarningsStore? = null

        fun get(context: Context): EarningsStore =
            instance ?: synchronized(this) {
                instance ?: EarningsStore(context).also { instance = it }
            }

        fun dayKey(ts: Long): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(ts))
    }
}

/** O que o [com.ruan.uberareafilter.engine.RideLedgerTracker] precisa do banco (permite testar sem SQLite). */
interface LedgerStore {
    fun insert(e: EarningsStore.Entry): Long
    fun setStatus(id: Long, status: String): Boolean
    fun setMinutes(id: Long, minutes: Double): Boolean
    fun addOnline(ts: Long, seconds: Long)
    fun countOffer(ts: Long)
    fun countRejected(ts: Long, reasonKeys: List<String>)
    fun countAccepted(ts: Long)
    fun openRidesSince(fromTs: Long): List<EarningsStore.Entry>
}
