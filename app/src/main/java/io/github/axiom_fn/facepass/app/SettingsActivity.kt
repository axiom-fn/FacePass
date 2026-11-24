package io.github.axiom_fn.facepass.app

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationManager
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.ListView
import android.widget.SearchView
import android.widget.Switch
import android.widget.TextView
import io.github.axiom_fn.facepass.R
import io.github.axiom_fn.facepass.intervention.act.FaceHint
import io.github.axiom_fn.facepass.judgment.Whitelist
import io.github.libxposed.service.XposedService
import java.util.concurrent.Executors

/** The settings screen: where the whitelist is kept. */
class SettingsActivity : Activity() {

    private data class AppEntry(
        val packageName: String,
        val label: String,
        val isSystem: Boolean,
        val info: ApplicationInfo
    )

    private val worker = Executors.newSingleThreadExecutor()
    private val iconCache = HashMap<String, Drawable>()

    private lateinit var statusView: TextView
    private lateinit var listView: ListView
    private lateinit var emptyView: TextView

    private var prefs: SharedPreferences? = null
    private var allApps: List<AppEntry> = emptyList()
    private var shownApps: List<AppEntry> = emptyList()
    private val selected = HashSet<String>()
    private var query = ""
    private var showSystem = false

    private val serviceListener: (XposedService?) -> Unit = { onServiceChanged(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusView = findViewById(R.id.status)
        listView = findViewById(R.id.app_list)
        emptyView = findViewById(R.id.empty)
        listView.emptyView = emptyView
        listView.adapter = adapter
        listView.setOnItemClickListener { _, _, position, _ -> toggle(shownApps[position]) }

        findViewById<SearchView>(R.id.search).setOnQueryTextListener(
            object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(text: String?) = true
                override fun onQueryTextChange(text: String?): Boolean {
                    query = text.orEmpty().trim()
                    refreshList()
                    return true
                }
            }
        )
        findViewById<Switch>(R.id.show_system).setOnCheckedChangeListener { _, checked ->
            showSystem = checked
            refreshList()
        }

        findViewById<Button>(R.id.test_fluid_cloud).setOnClickListener { testFluidCloud() }

        FacePassApp.addServiceListener(serviceListener)
        onServiceChanged(FacePassApp.service)
        loadApps()
    }

    override fun onDestroy() {
        FacePassApp.removeServiceListener(serviceListener)
        worker.shutdownNow()
        super.onDestroy()
    }

    /** Posts a Live Update for eight seconds, as the face hint does, and shows how the system took it. */
    private fun testFluidCloud() {
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            statusView.setText(R.string.fluid_need_permission)
            return
        }
        val nm = getSystemService(NotificationManager::class.java)
        // Same silent channel as the face hint, created when the app starts.
        val notification = Notification.Builder(this, FLUID_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_face)
            .setContentTitle(getString(R.string.fluid_title))
            .setContentText(getString(R.string.fluid_text))
            .setShortCriticalText(getString(R.string.fluid_chip))
            .setOngoing(true)
            .setRequestPromotedOngoing(true)
            .build()
        nm.notify(FLUID_NOTIFICATION_ID, notification)

        val canPromote = runCatching { nm.canPostPromotedNotifications() }.getOrDefault(false)
        val promotable = runCatching { notification.hasPromotableCharacteristics() }.getOrDefault(false)
        Handler(Looper.getMainLooper()).postDelayed({
            val posted = nm.activeNotifications.firstOrNull { it.id == FLUID_NOTIFICATION_ID }
            val promoted = posted != null && posted.notification.flags and Notification.FLAG_PROMOTED_ONGOING != 0
            statusView.text = getString(R.string.fluid_result, yesNo(canPromote), yesNo(promotable), yesNo(promoted))
        }, 500)
        Handler(Looper.getMainLooper()).postDelayed({ nm.cancel(FLUID_NOTIFICATION_ID) }, 8000)
    }

    private fun yesNo(value: Boolean) = if (value) "✓" else "✗"

    private fun onServiceChanged(service: XposedService?) {
        prefs = service?.let { runCatching { it.getRemotePreferences(Whitelist.PREFS_GROUP) }.getOrNull() }
        selected.clear()
        prefs?.getStringSet(Whitelist.KEY_PACKAGES, null)?.let { selected.addAll(it) }
        statusView.text = if (prefs != null) {
            getString(R.string.status_ready, selected.size)
        } else {
            getString(R.string.status_no_service)
        }
        listView.isEnabled = prefs != null
        refreshList()
    }

    private fun loadApps() {
        emptyView.setText(R.string.loading)
        val pm = packageManager
        val selfPackage = packageName
        worker.execute {
            val apps = pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
                .asSequence()
                .filter { it.packageName != selfPackage }
                .map {
                    AppEntry(
                        packageName = it.packageName,
                        label = it.loadLabel(pm).toString(),
                        isSystem = it.flags and
                            (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0,
                        info = it
                    )
                }
                .sortedBy { it.label.lowercase() }
                .toList()
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                allApps = apps
                emptyView.setText(R.string.no_match)
                refreshList()
            }
        }
    }

    private fun refreshList() {
        shownApps = allApps
            .filter { showSystem || !it.isSystem || it.packageName in selected }
            .filter {
                query.isEmpty() ||
                    it.label.contains(query, ignoreCase = true) ||
                    it.packageName.contains(query, ignoreCase = true)
            }
            // Ticked apps first: the whitelist is read at a glance.
            .sortedByDescending { it.packageName in selected }
        adapter.notifyDataSetChanged()
    }

    private fun toggle(app: AppEntry) {
        val editablePrefs = prefs ?: return
        if (!selected.remove(app.packageName)) selected.add(app.packageName)
        editablePrefs.edit().putStringSet(Whitelist.KEY_PACKAGES, HashSet(selected)).apply()
        statusView.text = getString(R.string.status_ready, selected.size)
        adapter.notifyDataSetChanged()
    }

    private fun iconFor(app: AppEntry): Drawable =
        iconCache.getOrPut(app.packageName) { app.info.loadIcon(packageManager) }

    private companion object {
        const val FLUID_CHANNEL = FaceHint.CHANNEL_ID
        const val FLUID_NOTIFICATION_ID = 4095
    }

    private val adapter = object : BaseAdapter() {
        override fun getCount() = shownApps.size
        override fun getItem(position: Int) = shownApps[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: layoutInflater.inflate(R.layout.item_app, parent, false)
            val app = shownApps[position]
            view.findViewById<ImageView>(R.id.icon).setImageDrawable(iconFor(app))
            view.findViewById<TextView>(R.id.label).text = app.label
            view.findViewById<TextView>(R.id.package_name).text = app.packageName
            view.findViewById<CheckBox>(R.id.checkbox).apply {
                isChecked = app.packageName in selected
                isEnabled = prefs != null
            }
            return view
        }
    }
}
