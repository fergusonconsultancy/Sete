package com.fergusonconsultancy.sete

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.google.android.gms.tasks.Tasks
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Source
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Optional: one household's garden shared between phones through Firestore.
 * The Firebase project's three values arrive at build time from GitHub secrets; a build
 * without them simply has no sync, and everything else works the same.
 */
object Sync {
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var fb: FirebaseApp? = null
    private var listener: ListenerRegistration? = null

    val configured: Boolean
        get() = BuildConfig.FIREBASE_API_KEY.isNotBlank() && BuildConfig.FIREBASE_APP_ID.isNotBlank() && BuildConfig.FIREBASE_PROJECT_ID.isNotBlank()

    val active: Boolean get() = configured && Store.household.isNotBlank()

    @Synchronized
    private fun app(c: Context): FirebaseApp {
        fb?.let { return it }
        val existing = FirebaseApp.getApps(c).firstOrNull { it.name == "sete" }
        val a = existing ?: FirebaseApp.initializeApp(
            c.applicationContext,
            FirebaseOptions.Builder()
                .setApiKey(BuildConfig.FIREBASE_API_KEY)
                .setApplicationId(BuildConfig.FIREBASE_APP_ID)
                .setProjectId(BuildConfig.FIREBASE_PROJECT_ID)
                .build(),
            "sete",
        )
        fb = a
        return a
    }

    /** Signs in anonymously; call off the main thread. */
    private fun signIn(c: Context) {
        val auth = FirebaseAuth.getInstance(app(c))
        if (auth.currentUser == null) Tasks.await(auth.signInAnonymously(), 20, TimeUnit.SECONDS)
    }

    private fun plants(c: Context, code: String = Store.household): CollectionReference =
        FirebaseFirestore.getInstance(app(c)).collection("households").document(code).collection("plants")

    /** Follows the household live while the garden is on screen. */
    fun start(c: Context) {
        if (!active || listener != null) return
        worker.execute {
            runCatching { signIn(c) }.onFailure { return@execute }
            main.post {
                if (listener != null || !active) return@post
                listener = plants(c).addSnapshotListener { snap, err ->
                    if (err != null || snap == null) return@addSnapshotListener
                    // An empty first answer from the cache says nothing about the household
                    if (snap.isEmpty && snap.metadata.isFromCache) return@addSnapshotListener
                    val list = snap.documents.mapNotNull { d -> d.data?.let { Plant.fromMap(d.id, it) } }
                    worker.execute { Store.replaceAll(list) }
                }
            }
        }
    }

    fun stop() { listener?.remove(); listener = null }

    fun push(c: Context, p: Plant) {
        if (!active) return
        worker.execute { runCatching { signIn(c); plants(c).document(p.id).set(p.toMap()) } }
    }

    fun delete(c: Context, id: String) {
        if (!active) return
        worker.execute { runCatching { signIn(c); plants(c).document(id).delete() } }
    }

    /** Before a reminder: fetch the household's latest, so a plant watered on the other phone stays quiet. */
    fun refreshBlocking(c: Context, timeoutMs: Long = 8000) {
        if (!active) return
        runCatching {
            signIn(c)
            val snap = Tasks.await(plants(c).get(Source.SERVER), timeoutMs, TimeUnit.MILLISECONDS)
            if (!snap.isEmpty) Store.replaceAll(snap.documents.mapNotNull { d -> d.data?.let { Plant.fromMap(d.id, it) } })
        }
    }

    private fun newCode(): String {
        val abc = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val r = SecureRandom()
        return (1..10).map { abc[r.nextInt(abc.length)] }.joinToString("")
    }

    /** Puts this phone's garden online under a new household code. */
    fun create(c: Context, done: (String?, String?) -> Unit) {
        worker.execute {
            val result = runCatching {
                signIn(c)
                val code = newCode()
                val db = FirebaseFirestore.getInstance(app(c))
                val batch = db.batch()
                batch.set(db.collection("households").document(code), mapOf("created" to System.currentTimeMillis()))
                Store.all().forEach { batch.set(plants(c, code).document(it.id), it.toMap()) }
                Tasks.await(batch.commit(), 20, TimeUnit.SECONDS)
                code
            }
            main.post {
                result.onSuccess { Store.household = it; start(c); done(it, null) }
                    .onFailure { done(null, "Couldn't reach Firebase. Check the connection, and that anonymous sign-in and Firestore are switched on.") }
            }
        }
    }

    /** Joins the garden behind [code]; this phone's own plants are replaced by the household's. */
    fun join(c: Context, code: String, done: (Boolean, String?) -> Unit) {
        val clean = code.uppercase().filter { it.isLetterOrDigit() }
        if (clean.length < 8) { done(false, "That code looks too short. It has 10 letters and numbers."); return }
        worker.execute {
            val result = runCatching {
                signIn(c)
                val snap = Tasks.await(plants(c, clean).get(Source.SERVER), 20, TimeUnit.SECONDS)
                snap.documents.mapNotNull { d -> d.data?.let { Plant.fromMap(d.id, it) } }
            }
            main.post {
                result.onSuccess { list ->
                    if (list.isEmpty()) done(false, "No garden found for that code. Check it on the other phone.")
                    else { stop(); Store.household = clean; Store.replaceAll(list); start(c); done(true, null) }
                }.onFailure { done(false, "Couldn't reach Firebase. Check the connection and try again.") }
            }
        }
    }

    fun leave() { stop(); Store.household = "" }
}
