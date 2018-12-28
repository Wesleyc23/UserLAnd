package tech.ula

import android.app.AlertDialog
import android.app.DownloadManager
import android.arch.lifecycle.Observer
import android.arch.lifecycle.ViewModelProviders
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Environment
import android.support.v4.content.LocalBroadcastManager
import android.support.v7.app.AppCompatActivity
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.animation.AlphaAnimation
import android.widget.Toast
import androidx.navigation.NavController
import androidx.navigation.Navigation
import androidx.navigation.findNavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.NavigationUI
import androidx.navigation.ui.NavigationUI.setupWithNavController
import kotlinx.android.synthetic.main.activity_main.*
import org.jetbrains.anko.defaultSharedPreferences
import tech.ula.model.entities.App
import tech.ula.model.entities.Session
import tech.ula.model.repositories.AssetRepository
import tech.ula.model.repositories.UlaDatabase
import tech.ula.model.state.SessionStartupFsm
import tech.ula.model.state.SessionStartupState
import tech.ula.ui.AppListFragment
import tech.ula.ui.SessionListFragment
import tech.ula.utils.*
import tech.ula.viewmodel.MainActivityViewModel
import tech.ula.viewmodel.MainActivityViewModelFactory

class MainActivity : AppCompatActivity(), SessionListFragment.SessionSelection, AppListFragment.AppSelection {

    private var progressBarIsVisible = false
    private var currentFragmentDisplaysProgressDialog = false

    private val navController: NavController by lazy {
        findNavController(R.id.nav_host_fragment)
    }

    private val notificationManager by lazy {
        NotificationUtility(this)
    }

    private val serverServiceBroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.let {
                val type = it.getStringExtra("type")
                when (type) {
                    "updateProgressBar" -> {
                        val step = intent.getStringExtra("step")
                        val details = intent.getStringExtra("details")
                        updateProgressBar(step, details)
                    }
                    "killProgressBar" -> killProgressBar()
                    "isProgressBarActive" -> syncProgressBarDisplayedWithService(it)
                    "displayNetworkChoices" -> displayNetworkChoicesDialog()
                    "toast" -> showToast(it)
                    "dialog" -> showDialog(it)
                }
            }
        }
    }

    private val viewModel: MainActivityViewModel by lazy {
        val ulaDatabase = UlaDatabase.getInstance(this)

        val timestampPreferences = TimestampPreferences(this.getSharedPreferences("file_timestamps", Context.MODE_PRIVATE))
        val assetPreferences = AssetPreferences(this.getSharedPreferences("assetLists", Context.MODE_PRIVATE))
        val assetRepository = AssetRepository(filesDir.path, timestampPreferences, assetPreferences)

        val execUtility = ExecUtility(filesDir.path, Environment.getExternalStorageDirectory().absolutePath, DefaultPreferences(defaultSharedPreferences))
        val filesystemUtility = FilesystemUtility(filesDir.path, execUtility)

        val downloadManager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val downloadManagerWrapper = DownloadManagerWrapper()
        val downloadUtility = DownloadUtility(downloadManager, timestampPreferences, downloadManagerWrapper, filesDir)

        val sessionStartupFsm = SessionStartupFsm(ulaDatabase, assetRepository, filesystemUtility, downloadUtility)
        ViewModelProviders.of(this, MainActivityViewModelFactory(sessionStartupFsm))
                .get(MainActivityViewModel::class.java)
    }

    private val sessionStartupStateObserver = Observer<SessionStartupState> {
        it?.let { state ->
            Log.i("MainActivity", "Session startup state: $state")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setSupportActionBar(toolbar)
        notificationManager.createServiceNotificationChannel() // Android O requirement

        setNavStartDestination()

        navController.addOnNavigatedListener { _, destination ->
            currentFragmentDisplaysProgressDialog =
                    destination.label == getString(R.string.sessions) ||
                    destination.label == getString(R.string.apps) ||
                    destination.label == getString(R.string.filesystems)
            if (!currentFragmentDisplaysProgressDialog) killProgressBar()
        }

        setupWithNavController(bottom_nav_view, navController)

        viewModel.getSessionStartupState().observe(this, sessionStartupStateObserver)
    }

    private fun setNavStartDestination() {
        val navHostFragment = nav_host_fragment as NavHostFragment
        val inflater = navHostFragment.navController.navInflater
        val graph = inflater.inflate(R.navigation.nav_graph)

        val userPreference = defaultSharedPreferences.getString("pref_default_nav_location", "Apps")
        graph.startDestination = when (userPreference) {
            getString(R.string.sessions) -> R.id.session_list_fragment
            else -> R.id.app_list_fragment
        }
        navHostFragment.navController.graph = graph
    }

    override fun onSupportNavigateUp() = navController.navigateUp()

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_options, menu)
        return true
    }

    override fun onStart() {
        super.onStart()
        LocalBroadcastManager.getInstance(this)
                .registerReceiver(serverServiceBroadcastReceiver, IntentFilter(ServerService.SERVER_SERVICE_RESULT))
    }

    override fun onResume() {
        super.onResume()

        val intent = Intent(this, ServerService::class.java)
                .putExtra("type", "isProgressBarActive")
        this.startService(intent)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.terms_and_conditions) {
            val intent = Intent("android.intent.action.VIEW", Uri.parse("https://userland.tech/eula"))
            startActivity(intent)
        }
        return NavigationUI.onNavDestinationSelected(item,
                Navigation.findNavController(this, R.id.nav_host_fragment)) ||
                super.onOptionsItemSelected(item)
    }

    override fun onStop() {
        super.onStop()
        LocalBroadcastManager.getInstance(this)
                .unregisterReceiver(serverServiceBroadcastReceiver)
    }

    override fun appHasBeenSelected(app: App) {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun sessionHasBeenSelected(session: Session) {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

//    private fun startSession(session: Session) {
//        val filesystem = filesystemList.find { it.name == session.filesystemName }
//        val serviceIntent = Intent(activityContext, ServerService::class.java)
//        serviceIntent.putExtra("type", "start")
//        serviceIntent.putExtra("session", session)
//        serviceIntent.putExtra("filesystem", filesystem)
//        activityContext.startService(serviceIntent)
//    }
//
//    private fun restartRunningSession(session: Session) {
//        val serviceIntent = Intent(activityContext, ServerService::class.java)
//        serviceIntent.putExtra("type", "restartRunningSession")
//        serviceIntent.putExtra("session", session)
//        activityContext.startService(serviceIntent)
//    }

    private fun showToast(intent: Intent) {
        val content = intent.getIntExtra("id", -1)
        if (content == -1) return
        Toast.makeText(this, content, Toast.LENGTH_LONG).show()
    }

    private fun showDialog(intent: Intent) {
        when (intent.getStringExtra("dialogType")) {
            "wifiRequired" -> displayNetworkChoicesDialog()

            "errorFetchingAssetLists" ->
                displayGenericErrorDialog(this, R.string.alert_network_unavailable_title,
                        R.string.alert_network_unavailable_message)
            "extractionFailed" ->
                displayGenericErrorDialog(this, R.string.alert_extraction_failure_title,
                        R.string.alert_extraction_failure_message)
            "filesystemIsMissingRequiredAssets" ->
                displayGenericErrorDialog(this, R.string.alert_filesystem_missing_requirements_title,
                    R.string.alert_filesystem_missing_requirements_message)
            "playStoreMissingForClient" ->
                displayGenericErrorDialog(this, R.string.alert_need_client_app_title,
                    R.string.alert_need_client_app_message)
            "networkTooWeakForDownloads" ->
                displayGenericErrorDialog(this, R.string.general_error_title,
                        R.string.alert_network_strength_too_low_for_downloads)
        }
    }

    private fun killProgressBar() {
        val outAnimation = AlphaAnimation(1f, 0f)
        outAnimation.duration = 200
        layout_progress.animation = outAnimation
        layout_progress.visibility = View.GONE
        layout_progress.isFocusable = false
        layout_progress.isClickable = false
        progressBarIsVisible = false
    }

    private fun updateProgressBar(step: String, details: String) {
        if (!currentFragmentDisplaysProgressDialog) return

        if (!progressBarIsVisible) {
            val inAnimation = AlphaAnimation(0f, 1f)
            inAnimation.duration = 200
            layout_progress.animation = inAnimation

            layout_progress.visibility = View.VISIBLE
            layout_progress.isFocusable = true
            layout_progress.isClickable = true
            progressBarIsVisible = true
        }

        text_session_list_progress_step.text = step
        text_session_list_progress_details.text = details
    }

    private fun syncProgressBarDisplayedWithService(intent: Intent) {
        val isActive = intent.getBooleanExtra("isProgressBarActive", false)
        if (isActive) updateProgressBar("", "")
        else killProgressBar()
    }

    private fun displayNetworkChoicesDialog() {
        val builder = AlertDialog.Builder(this)
        builder.setMessage(R.string.alert_wifi_disabled_message)
                .setTitle(R.string.alert_wifi_disabled_title)
                .setPositiveButton(R.string.alert_wifi_disabled_continue_button) {
                    dialog, _ ->
                    dialog.dismiss()
                    val serviceIntent = Intent(this, ServerService::class.java)
                    serviceIntent.putExtra("type", "forceDownloads")
                    this.startService(serviceIntent)
                }
                .setNegativeButton(R.string.alert_wifi_disabled_turn_on_wifi_button) {
                    dialog, _ ->
                    dialog.dismiss()
                    startActivity(Intent(WifiManager.ACTION_PICK_WIFI_NETWORK))
                    killProgressBar()
                }
                .setNeutralButton(R.string.alert_wifi_disabled_cancel_button) {
                    dialog, _ ->
                    dialog.dismiss()
                    killProgressBar()
                }
                .setOnCancelListener {
                    killProgressBar()
                }
                .create()
                .show()
    }
}